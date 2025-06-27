############################################
# Azure Application Gateway Resources
############################################

# Public IP for Application Gateway
resource "azurerm_public_ip" "app_gateway" {
  name                = "${var.app_gateway_name}-ip"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"

  tags = merge(
    {
      Name        = "${var.app_gateway_name}-ip"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

# Application Gateway - Azure equivalent of AWS ALB
resource "azurerm_application_gateway" "main" {
  name                = var.app_gateway_name
  location            = var.location
  resource_group_name = var.resource_group_name

  sku {
    name     = var.sku_name
    tier     = var.sku_tier
    capacity = var.capacity
  }

  gateway_ip_configuration {
    name      = "gateway-ip-config"
    subnet_id = var.gateway_subnet_id
  }

  frontend_port {
    name = "http-port"
    port = var.frontend_port
  }

  frontend_ip_configuration {
    name                 = "frontend-ip-config"
    public_ip_address_id = azurerm_public_ip.app_gateway.id
  }

  # Blue Backend Pools for each application
  dynamic "backend_address_pool" {
    for_each = var.application_target_groups
    content {
      name = "${backend_address_pool.key}-blue-pool"
    }
  }

  # Green Backend Pools for each application
  dynamic "backend_address_pool" {
    for_each = var.application_target_groups
    content {
      name = "${backend_address_pool.key}-green-pool"
    }
  }

  # Default Backend Pool for static welcome message
  backend_address_pool {
    name = "default-static-pool"
  }



  # HTTP Settings for each application
  dynamic "backend_http_settings" {
    for_each = var.application_target_groups
    content {
      name                  = "${backend_http_settings.key}-http-settings"
      cookie_based_affinity = var.cookie_based_affinity
      path                  = var.backend_path
      port                  = backend_http_settings.value.backend_port
      protocol              = var.backend_protocol
      request_timeout       = var.request_timeout
      probe_name            = "${backend_http_settings.key}-health-probe"
    }
  }

  # Default HTTP Settings for static message server
  backend_http_settings {
    name                  = "default-static-http-settings"
    cookie_based_affinity = var.cookie_based_affinity
    path                  = var.backend_path
    port                  = 80
    protocol              = var.backend_protocol
    request_timeout       = var.request_timeout
  }



  http_listener {
    name                           = "http-listener"
    frontend_ip_configuration_name = "frontend-ip-config"
    frontend_port_name             = "http-port"
    protocol                       = var.listener_protocol
  }

  # Individual routing rules for each application
  dynamic "request_routing_rule" {
    for_each = var.application_paths
    content {
      name                       = "${request_routing_rule.key}-routing-rule"
      rule_type                  = "PathBasedRouting"
      http_listener_name         = "http-listener"
      url_path_map_name          = "main-path-map"
      priority                   = request_routing_rule.value.priority
    }
  }

  # Default routing rule for unmatched paths (static welcome message)
  request_routing_rule {
    name                       = "default-static-routing-rule"
    rule_type                  = "Basic"
    http_listener_name         = "http-listener"
    backend_address_pool_name  = "default-static-pool"
    backend_http_settings_name = "default-static-http-settings"
    priority                   = 1000
  }

  # Single URL Path Map with all application rules
  url_path_map {
    name                               = "main-path-map"
    default_backend_address_pool_name  = "default-static-pool"
    default_backend_http_settings_name = "default-static-http-settings"

    dynamic "path_rule" {
      for_each = var.application_paths
      content {
        name                       = "${path_rule.key}-path-rule"
        paths                      = [path_rule.value.path_pattern]
        backend_address_pool_name  = "${path_rule.key}-blue-pool"
        backend_http_settings_name = "${path_rule.key}-http-settings"
      }
    }
  }

  # Health Probes for each application
  dynamic "probe" {
    for_each = var.application_target_groups
    content {
      name                = "${probe.key}-health-probe"
      protocol            = var.health_check_protocol
      path                = var.health_check_path
      host                = var.health_check_host
      interval            = var.health_check_interval
      timeout             = var.health_check_timeout
      unhealthy_threshold = var.unhealthy_threshold

      match {
        status_code = [var.health_check_status_code]
      }
    }
  }

  tags = merge(
    {
      Name        = var.app_gateway_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}