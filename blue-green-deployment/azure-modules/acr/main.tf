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
      docker tag ${each.value.image_name}:${each.value.image_tag} ${azurerm_container_registry.main.login_server}:${each.key}-latest
      
      # Push the app-specific latest tag
      docker push ${azurerm_container_registry.main.login_server}:${each.key}-latest
    EOT
  }

  depends_on = [azurerm_container_registry.main]
}

############################################
# Automatic Load Balancer Registration for Testing
############################################
# Only runs when skip_docker_build = false (testing mode)

# Data source to get ACI container IPs after they're created
data "azurerm_container_group" "blue_containers" {
  for_each = var.skip_docker_build ? {} : var.application

  name                = "${each.key}-blue-container"
  resource_group_name = var.resource_group_name
}

# Automatic Blue Container Registration
resource "null_resource" "auto_register_blue" {
  for_each = var.skip_docker_build ? {} : var.application

  triggers = {
    container_ip     = data.azurerm_container_group.blue_containers[each.key].ip_address
    app_gateway_name = var.app_gateway_name
    resource_group   = var.resource_group_name
    always_run       = timestamp()
  }

  provisioner "local-exec" {
    command = <<-EOT
      # Wait for containers to be ready
      sleep 45
      
      # Register blue container IP to blue backend pool
      az network application-gateway address-pool address add \
        --gateway-name ${var.app_gateway_name} \
        --resource-group ${var.resource_group_name} \
        --pool-name ${each.key}-blue-pool \
        --ip-address ${data.azurerm_container_group.blue_containers[each.key].ip_address}
      
      echo "Registered ${each.key} blue container IP ${data.azurerm_container_group.blue_containers[each.key].ip_address} to ${each.key}-blue-pool"
    EOT
  }

  # Cleanup on destroy
  provisioner "local-exec" {
    when    = destroy
    command = <<-EOT
      # Remove blue container IP from backend pool
      az network application-gateway address-pool address remove \
        --gateway-name ${self.triggers.app_gateway_name} \
        --resource-group ${self.triggers.resource_group} \
        --pool-name ${each.key}-blue-pool \
        --ip-address ${self.triggers.container_ip} || true
      
      echo "Removed ${each.key} blue container IP from backend pool"
    EOT
  }

  depends_on = [null_resource.docker_build_push, data.azurerm_container_group.blue_containers]
}

# Register first blue container as default fallback
resource "null_resource" "auto_register_default" {
  count = var.skip_docker_build ? 0 : 1

  triggers = {
    app_gateway_name = var.app_gateway_name
    resource_group   = var.resource_group_name
    default_ip       = data.azurerm_container_group.blue_containers[keys(var.application)[0]].ip_address
    always_run       = timestamp()
  }

  provisioner "local-exec" {
    command = <<-EOT
      # Wait for containers to be ready
      sleep 45
      
      # Register first blue container as default fallback
      az network application-gateway address-pool address add \
        --gateway-name ${var.app_gateway_name} \
        --resource-group ${var.resource_group_name} \
        --pool-name default-static-pool \
        --ip-address ${data.azurerm_container_group.blue_containers[keys(var.application)[0]].ip_address}
      
      echo "Registered default static pool with IP ${data.azurerm_container_group.blue_containers[keys(var.application)[0]].ip_address}"
    EOT
  }

  # Cleanup on destroy
  provisioner "local-exec" {
    when    = destroy
    command = <<-EOT
      # Remove IP from default backend pool
      az network application-gateway address-pool address remove \
        --gateway-name ${self.triggers.app_gateway_name} \
        --resource-group ${self.triggers.resource_group} \
        --pool-name default-static-pool \
        --ip-address ${self.triggers.default_ip} || true
      
      echo "Removed default static pool IP"
    EOT
  }

  depends_on = [null_resource.docker_build_push, data.azurerm_container_group.blue_containers]
}
