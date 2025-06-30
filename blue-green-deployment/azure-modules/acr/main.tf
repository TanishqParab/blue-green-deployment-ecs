############################################
# Azure Container Registry Resources
############################################

# Azure Container Registry - equivalent to AWS ECR
resource "azurerm_container_registry" "main" {
  name                = var.registry_name
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = var.sku
  admin_enabled       = var.admin_enabled

  tags = merge(
    {
      Name        = var.registry_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

############################################
# Docker Build and Push
############################################

resource "null_resource" "docker_build_push" {
  for_each = var.skip_docker_build ? {} : var.application

  triggers = {
    app_py_sha1      = fileexists("${var.app_py_path_prefix}${each.key}.py") ? filesha1("${var.app_py_path_prefix}${each.key}.py") : var.file_not_found_message
    dockerfile_sha1  = fileexists(var.dockerfile_path) ? filesha1(var.dockerfile_path) : var.file_not_found_message
    acr_login_server = azurerm_container_registry.main.login_server
    always_run       = timestamp()
  }

  provisioner "local-exec" {
    command = <<-EOT
      # Login to Azure Container Registry
      az acr login --name ${azurerm_container_registry.main.name}
      
      # Navigate to the directory with Dockerfile
      cd ${dirname(var.dockerfile_path)}
      
      # Build the Docker image
      docker build -t ${each.value.image_name} ${var.docker_build_args} --build-arg APP_NAME=${replace(each.key, "app_", "")} .
      
      # Tag the image with app-specific latest tag
      docker tag ${each.value.image_name}:${each.value.image_tag} ${azurerm_container_registry.main.login_server}/${each.value.image_name}:${each.key}-latest
      
      # Push the app-specific latest tag
      docker push ${azurerm_container_registry.main.login_server}/${each.value.image_name}:${each.key}-latest
    EOT
  }

  depends_on = [azurerm_container_registry.main]
}

############################################
# Automatic Load Balancer Registration for Testing
############################################
# Only runs when skip_docker_build = false (testing mode)

# Automatic Blue Container Registration
resource "null_resource" "auto_register_blue" {
  for_each = var.skip_docker_build ? {} : var.application

  triggers = {
    app_gateway_name = var.app_gateway_name
    resource_group   = var.resource_group_name
    container_name   = "${replace(each.key, "_", "")}-blue-container"
    always_run       = timestamp()
  }

  provisioner "local-exec" {
    command = <<-EOT
      # Wait for containers to be ready
      sleep 60
      
      # Get container IP dynamically
      CONTAINER_IP=$(az container show --name ${replace(each.key, "_", "")}-blue-container --resource-group ${var.resource_group_name} --query ipAddress.ip --output tsv)
      
      # Register blue container IP to blue backend pool
      az network application-gateway address-pool address add \
        --gateway-name ${var.app_gateway_name} \
        --resource-group ${var.resource_group_name} \
        --pool-name ${each.key}-blue-pool \
        --ip-address $CONTAINER_IP
      
      echo "Registered ${each.key} blue container IP $CONTAINER_IP to ${each.key}-blue-pool"
    EOT
  }

  # Cleanup on destroy
  provisioner "local-exec" {
    when    = destroy
    command = <<-EOT
      # Get container IP for cleanup
      CONTAINER_IP=$(az container show --name ${self.triggers.container_name} --resource-group ${self.triggers.resource_group} --query ipAddress.ip --output tsv 2>/dev/null || echo "")
      
      if [ ! -z "$CONTAINER_IP" ]; then
        # Remove blue container IP from backend pool
        az network application-gateway address-pool address remove \
          --gateway-name ${self.triggers.app_gateway_name} \
          --resource-group ${self.triggers.resource_group} \
          --pool-name ${each.key}-blue-pool \
          --ip-address $CONTAINER_IP || true
        
        echo "Removed ${each.key} blue container IP $CONTAINER_IP from backend pool"
      fi
    EOT
  }

  depends_on = [null_resource.docker_build_push]
}

# Register first blue container as default fallback
resource "null_resource" "auto_register_default" {
  count = var.skip_docker_build ? 0 : 1

  triggers = {
    app_gateway_name = var.app_gateway_name
    resource_group   = var.resource_group_name
    first_app        = replace(keys(var.application)[0], "_", "")
    always_run       = timestamp()
  }

  provisioner "local-exec" {
    command = <<-EOT
      # Wait for containers to be ready
      sleep 60
      
      # Get first container IP dynamically
      CONTAINER_IP=$(az container show --name ${replace(keys(var.application)[0], "_", "")}-blue-container --resource-group ${var.resource_group_name} --query ipAddress.ip --output tsv)
      
      # Register first blue container as default fallback
      az network application-gateway address-pool address add \
        --gateway-name ${var.app_gateway_name} \
        --resource-group ${var.resource_group_name} \
        --pool-name default-static-pool \
        --ip-address $CONTAINER_IP
      
      echo "Registered default static pool with IP $CONTAINER_IP"
    EOT
  }

  # Cleanup on destroy
  provisioner "local-exec" {
    when    = destroy
    command = <<-EOT
      # Get container IP for cleanup
      CONTAINER_IP=$(az container show --name ${self.triggers.first_app}-blue-container --resource-group ${self.triggers.resource_group} --query ipAddress.ip --output tsv 2>/dev/null || echo "")
      
      if [ ! -z "$CONTAINER_IP" ]; then
        # Remove IP from default backend pool
        az network application-gateway address-pool address remove \
          --gateway-name ${self.triggers.app_gateway_name} \
          --resource-group ${self.triggers.resource_group} \
          --pool-name default-static-pool \
          --ip-address $CONTAINER_IP || true
        
        echo "Removed default static pool IP $CONTAINER_IP"
      fi
    EOT
  }

  depends_on = [null_resource.docker_build_push]
}
