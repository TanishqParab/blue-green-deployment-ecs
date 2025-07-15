variable "location" {
  description = "Azure location"
  type        = string
}

variable "environment" {
  description = "Environment tag"
  type        = string
}

variable "additional_tags" {
  description = "Additional tags"
  type        = map(string)
}

variable "app_py_path_prefix" {
  description = "Path prefix for app Python files"
  type        = string
}

variable "dockerfile_path" {
  description = "Path to Dockerfile"
  type        = string
}

variable "azure_vnet" {
  description = "Azure VNet configuration"
  type = object({
    resource_group_name            = string
    vnet_name                      = string
    vnet_cidr                      = string
    public_subnet_cidrs            = list(string)
    subnet_name_prefix             = string
    create_private_subnets         = bool
    private_subnet_cidrs           = list(string)
    private_subnet_name_prefix     = string
    create_nat_gateway             = bool
    module_name                    = string
    terraform_managed              = string
  })
}

variable "azure_nsg" {
  description = "Azure NSG configuration"
  type = object({
    nsg_name = string
    ingress_rules = list(object({
      name                   = string
      priority               = number
      protocol               = string
      destination_port_range = string
      source_address_prefix  = string
    }))
    module_name       = string
    terraform_managed = string
  })
}

variable "azure_app_gateway" {
  description = "Azure Application Gateway configuration"
  type = object({
    app_gateway_name     = string
    app_gateway_sku_name = string
    app_gateway_sku_tier = string
    app_gateway_capacity = number
    frontend_port        = number
    backend_port         = number
    backend_protocol     = string
    listener_protocol    = string
    health_check_path    = string
    health_check_interval = number
    health_check_timeout = number
    unhealthy_threshold  = number
    application_target_groups = map(object({
      blue_backend_pool_name  = string
      green_backend_pool_name = string
      backend_port            = number
    }))
    application_paths = map(object({
      priority     = number
      path_pattern = string
    }))
    module_name       = string
    terraform_managed = string
  })
}

variable "azure_acr" {
  description = "Azure Container Registry configuration"
  type = object({
    registry_name     = string
    sku              = string
    admin_enabled    = bool
    skip_docker_build = bool
    image_name       = string
    image_tag        = string
    application = map(object({
      image_name = string
      image_tag  = string
    }))
    module_name       = string
    terraform_managed = string
  })
}

variable "azure_aci" {
  description = "Azure Container Instances configuration"
  type = object({
    ip_address_type = string
    os_type         = string
    restart_policy  = string
    cpu             = string
    memory          = string
    application = map(object({
      blue_container_group_name  = string
      green_container_group_name = string
      container_name             = string
      image_name                 = string
      container_port             = number
    }))
    health_check_path              = string
    health_check_initial_delay     = number
    health_check_period            = number
    health_check_failure_threshold = number
    module_name       = string
    terraform_managed = string
  })
}