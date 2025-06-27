############################################
# Azure Container Instances Resources
############################################

############################################
# Blue Container Groups
############################################

# Blue Container Groups for each application
resource "azurerm_container_group" "blue" {
  for_each = var.application

  name                = each.value.blue_container_group_name
  location            = var.location
  resource_group_name = var.resource_group_name
  ip_address_type     = var.ip_address_type
  dns_name_label      = "${each.value.blue_container_group_name}-${random_string.dns_suffix[each.key].result}"
  os_type             = var.os_type
  restart_policy      = var.restart_policy

  container {
    name   = each.value.container_name
    image  = "${var.container_registry_url}/${each.value.image_name}:${each.key}-latest"
    cpu    = var.cpu
    memory = var.memory

    ports {
      port     = each.value.container_port
      protocol = var.container_protocol
    }

    environment_variables = merge(
      {
        DEPLOYMENT_TYPE = "blue"
        APP_NAME        = each.key
      },
      var.container_environment_variables
    )

    dynamic "volume" {
      for_each = var.container_volumes
      content {
        name                 = volume.value.name
        mount_path           = volume.value.mount_path
        read_only            = volume.value.read_only
        empty_dir            = volume.value.empty_dir
        storage_account_name = volume.value.storage_account_name
        storage_account_key  = volume.value.storage_account_key
        share_name           = volume.value.share_name
      }
    }

    liveness_probe {
      http_get {
        path   = var.health_check_path
        port   = each.value.container_port
        scheme = var.health_check_scheme
      }
      initial_delay_seconds = var.health_check_initial_delay
      period_seconds        = var.health_check_period
      failure_threshold     = var.health_check_failure_threshold
      success_threshold     = var.health_check_success_threshold
      timeout_seconds       = var.health_check_timeout
    }

    readiness_probe {
      http_get {
        path   = var.health_check_path
        port   = each.value.container_port
        scheme = var.health_check_scheme
      }
      initial_delay_seconds = var.health_check_initial_delay
      period_seconds        = var.health_check_period
      failure_threshold     = var.health_check_failure_threshold
      success_threshold     = var.health_check_success_threshold
      timeout_seconds       = var.health_check_timeout
    }
  }

  image_registry_credential {
    server   = var.container_registry_url
    username = var.container_registry_username
    password = var.container_registry_password
  }

  tags = merge(
    {
      Name           = each.value.blue_container_group_name
      Environment    = var.environment
      Module         = var.module_name
      Terraform      = var.terraform_managed
      DeploymentType = "blue"
      App            = each.key
    },
    var.additional_tags
  )
}

############################################
# Green Container Groups
############################################

# Green Container Groups for each application
resource "azurerm_container_group" "green" {
  for_each = var.application

  name                = each.value.green_container_group_name
  location            = var.location
  resource_group_name = var.resource_group_name
  ip_address_type     = var.ip_address_type
  dns_name_label      = "${each.value.green_container_group_name}-${random_string.dns_suffix[each.key].result}"
  os_type             = var.os_type
  restart_policy      = var.restart_policy

  container {
    name   = "${each.value.container_name}-green"
    image  = "${var.container_registry_url}/${each.value.image_name}:${each.key}-latest"
    cpu    = var.cpu
    memory = var.memory

    ports {
      port     = each.value.container_port
      protocol = var.container_protocol
    }

    environment_variables = merge(
      {
        DEPLOYMENT_TYPE = "green"
        APP_NAME        = each.key
      },
      var.container_environment_variables
    )

    dynamic "volume" {
      for_each = var.container_volumes
      content {
        name                 = volume.value.name
        mount_path           = volume.value.mount_path
        read_only            = volume.value.read_only
        empty_dir            = volume.value.empty_dir
        storage_account_name = volume.value.storage_account_name
        storage_account_key  = volume.value.storage_account_key
        share_name           = volume.value.share_name
      }
    }

    liveness_probe {
      http_get {
        path   = var.health_check_path
        port   = each.value.container_port
        scheme = var.health_check_scheme
      }
      initial_delay_seconds = var.health_check_initial_delay
      period_seconds        = var.health_check_period
      failure_threshold     = var.health_check_failure_threshold
      success_threshold     = var.health_check_success_threshold
      timeout_seconds       = var.health_check_timeout
    }

    readiness_probe {
      http_get {
        path   = var.health_check_path
        port   = each.value.container_port
        scheme = var.health_check_scheme
      }
      initial_delay_seconds = var.health_check_initial_delay
      period_seconds        = var.health_check_period
      failure_threshold     = var.health_check_failure_threshold
      success_threshold     = var.health_check_success_threshold
      timeout_seconds       = var.health_check_timeout
    }
  }

  image_registry_credential {
    server   = var.container_registry_url
    username = var.container_registry_username
    password = var.container_registry_password
  }

  tags = merge(
    {
      Name           = each.value.green_container_group_name
      Environment    = var.environment
      Module         = var.module_name
      Terraform      = var.terraform_managed
      DeploymentType = "green"
      App            = each.key
    },
    var.additional_tags
  )
}

############################################
# Random DNS Suffix
############################################

# Random string for unique DNS names
resource "random_string" "dns_suffix" {
  for_each = var.application

  length  = 8
  special = false
  upper   = false
}