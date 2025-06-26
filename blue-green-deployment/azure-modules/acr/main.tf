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

  dynamic "georeplications" {
    for_each = var.georeplications
    content {
      location                = georeplications.value.location
      zone_redundancy_enabled = georeplications.value.zone_redundancy_enabled
      tags                    = georeplications.value.tags
    }
  }

  dynamic "network_rule_set" {
    for_each = var.network_rule_set_enabled ? [1] : []
    content {
      default_action = var.network_rule_default_action

      dynamic "ip_rule" {
        for_each = var.network_rule_ip_rules
        content {
          action   = ip_rule.value.action
          ip_range = ip_rule.value.ip_range
        }
      }

      dynamic "virtual_network" {
        for_each = var.network_rule_virtual_networks
        content {
          action    = virtual_network.value.action
          subnet_id = virtual_network.value.subnet_id
        }
      }
    }
  }

  dynamic "retention_policy" {
    for_each = var.retention_policy_enabled ? [1] : []
    content {
      days    = var.retention_policy_days
      enabled = var.retention_policy_enabled
    }
  }

  dynamic "trust_policy" {
    for_each = var.trust_policy_enabled ? [1] : []
    content {
      enabled = var.trust_policy_enabled
    }
  }

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
# Container Images Build (Optional)
############################################

# Build and push container images for each application
resource "null_resource" "build_and_push_images" {
  for_each = var.skip_docker_build ? {} : var.application

  triggers = {
    dockerfile_hash = filemd5("${path.root}/modules/ecs/scripts/Dockerfile")
    app_hash        = filemd5("${path.root}/modules/ecs/scripts/app_${replace(each.key, "app_", "")}.py")
    always_run      = timestamp()
  }

  provisioner "local-exec" {
    command = <<-EOT
      # Login to Azure Container Registry
      az acr login --name ${azurerm_container_registry.main.name}
      
      # Build and tag the image
      docker build -t ${azurerm_container_registry.main.login_server}/${each.value.image_name}:${each.value.image_tag} \
        --build-arg APP_NAME=${each.key} \
        -f ${path.root}/modules/ecs/scripts/Dockerfile \
        ${path.root}/modules/ecs/scripts/
      
      # Push the image
      docker push ${azurerm_container_registry.main.login_server}/${each.value.image_name}:${each.value.image_tag}
    EOT
  }

  depends_on = [azurerm_container_registry.main]
}