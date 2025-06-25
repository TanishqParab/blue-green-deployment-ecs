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

  backend_http_settings {
    name                  = "http-settings"
    cookie_based_affinity = var.cookie_based_affinity
    path                  = var.backend_path
    port                  = var.backend_port
    protocol              = var.backend_protocol
    request_timeout       = var.request_timeout

    probe_name = "health-probe"
  }

  http_listener {
    name                           = "http-listener"
    frontend_ip_configuration_name = "frontend-ip-config"
    frontend_port_name             = "http-port"
    protocol                       = var.listener_protocol
  }

  # Default routing rule
  request_routing_rule {
    name                       = "default-rule"
    rule_type                  = "Basic"
    http_listener_name         = "http-listener"
    backend_address_pool_name  = "${keys(var.application_target_groups)[0]}-blue-pool"
    backend_http_settings_name = "http-settings"
    priority                   = 1
  }

  # Path-based routing rules for each application
  dynamic "request_routing_rule" {
    for_each = var.application_paths
    content {
      name                        = "${request_routing_rule.key}-rule"
      rule_type                   = "PathBasedRouting"
      http_listener_name          = "http-listener"
      url_path_map_name           = "${request_routing_rule.key}-path-map"
      priority                    = request_routing_rule.value.priority + 1
    }
  }

  # URL Path Maps for path-based routing
  dynamic "url_path_map" {
    for_each = var.application_paths
    content {
      name                               = "${url_path_map.key}-path-map"
      default_backend_address_pool_name  = "${url_path_map.key}-blue-pool"
      default_backend_http_settings_name = "http-settings"

      path_rule {
        name                       = "${url_path_map.key}-path-rule"
        paths                      = [url_path_map.value.path_pattern]
        backend_address_pool_name  = "${url_path_map.key}-blue-pool"
        backend_http_settings_name = "http-settings"
      }
    }
  }

  # Health Probe
  probe {
    name                = "health-probe"
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