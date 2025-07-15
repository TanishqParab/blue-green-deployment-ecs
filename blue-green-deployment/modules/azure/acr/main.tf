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
# REMOVED: ACR module runs before ACI containers are created
# Registration must happen after all modules complete
# Use manual registration or separate post-deployment script
