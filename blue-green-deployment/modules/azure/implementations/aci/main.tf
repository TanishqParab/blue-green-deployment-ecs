# Azure ACI Implementation Module
# This module provides a clean interface for ACI-based deployments

module "azure_vnet" {
  source = "../../vnet"

  resource_group_name = var.azure_vnet.resource_group_name
  location            = var.location
  vnet_name           = var.azure_vnet.vnet_name
  vnet_cidr           = var.azure_vnet.vnet_cidr

  public_subnet_cidrs = var.azure_vnet.public_subnet_cidrs
  subnet_name_prefix  = var.azure_vnet.subnet_name_prefix

  create_private_subnets     = var.azure_vnet.create_private_subnets
  private_subnet_cidrs       = var.azure_vnet.private_subnet_cidrs
  private_subnet_name_prefix = var.azure_vnet.private_subnet_name_prefix
  create_nat_gateway         = var.azure_vnet.create_nat_gateway

  environment       = var.environment
  module_name       = var.azure_vnet.module_name
  terraform_managed = var.azure_vnet.terraform_managed
  additional_tags   = var.additional_tags
}

module "azure_nsg" {
  source = "../../nsg"

  nsg_name            = var.azure_nsg.nsg_name
  location            = var.location
  resource_group_name = module.azure_vnet.resource_group_name
  subnet_ids          = []

  ingress_rules = var.azure_nsg.ingress_rules

  environment       = var.environment
  module_name       = var.azure_nsg.module_name
  terraform_managed = var.azure_nsg.terraform_managed
  additional_tags   = var.additional_tags

  depends_on = [module.azure_vnet]
}

resource "azurerm_subnet_network_security_group_association" "dynamic_nsg_association" {
  count                     = length(module.azure_vnet.public_subnet_ids)
  subnet_id                 = module.azure_vnet.public_subnet_ids[count.index]
  network_security_group_id = module.azure_nsg.nsg_id

  depends_on = [module.azure_nsg, module.azure_vnet]
}

module "azure_app_gateway" {
  source = "../../app-gateway"

  app_gateway_name    = var.azure_app_gateway.app_gateway_name
  location            = var.location
  resource_group_name = module.azure_vnet.resource_group_name
  gateway_subnet_id   = module.azure_vnet.app_gateway_subnet_id

  sku_name = var.azure_app_gateway.app_gateway_sku_name
  sku_tier = var.azure_app_gateway.app_gateway_sku_tier
  capacity = var.azure_app_gateway.app_gateway_capacity

  frontend_port     = var.azure_app_gateway.frontend_port
  backend_port      = var.azure_app_gateway.backend_port
  backend_protocol  = var.azure_app_gateway.backend_protocol
  listener_protocol = var.azure_app_gateway.listener_protocol

  application_target_groups = var.azure_app_gateway.application_target_groups
  application_paths         = var.azure_app_gateway.application_paths

  health_check_path     = var.azure_app_gateway.health_check_path
  health_check_interval = var.azure_app_gateway.health_check_interval
  health_check_timeout  = var.azure_app_gateway.health_check_timeout
  unhealthy_threshold   = var.azure_app_gateway.unhealthy_threshold

  environment       = var.environment
  module_name       = var.azure_app_gateway.module_name
  terraform_managed = var.azure_app_gateway.terraform_managed
  additional_tags   = var.additional_tags
}

module "azure_acr" {
  source = "../../acr"

  registry_name       = var.azure_acr.registry_name
  resource_group_name = module.azure_vnet.resource_group_name
  location            = var.location
  sku                 = var.azure_acr.sku
  admin_enabled       = var.azure_acr.admin_enabled

  app_py_path_prefix = var.app_py_path_prefix
  dockerfile_path    = var.dockerfile_path
  image_name         = var.azure_acr.image_name
  skip_docker_build  = var.azure_acr.skip_docker_build
  image_tag          = var.azure_acr.image_tag

  application = var.azure_acr.application

  app_gateway_name = var.azure_app_gateway.app_gateway_name

  environment       = var.environment
  module_name       = var.azure_acr.module_name
  terraform_managed = var.azure_acr.terraform_managed
  additional_tags   = var.additional_tags
}

module "azure_aci" {
  source = "../../aci"

  resource_group_name = module.azure_vnet.resource_group_name
  location            = var.location

  container_registry_url      = module.azure_acr.login_server
  container_registry_username = module.azure_acr.admin_username
  container_registry_password = module.azure_acr.admin_password

  ip_address_type = var.azure_aci.ip_address_type
  os_type         = var.azure_aci.os_type
  restart_policy  = var.azure_aci.restart_policy
  cpu             = var.azure_aci.cpu
  memory          = var.azure_aci.memory

  application       = var.azure_aci.application
  skip_docker_build = var.azure_acr.skip_docker_build

  health_check_path              = var.azure_aci.health_check_path
  health_check_initial_delay     = var.azure_aci.health_check_initial_delay
  health_check_period            = var.azure_aci.health_check_period
  health_check_failure_threshold = var.azure_aci.health_check_failure_threshold

  environment       = var.environment
  module_name       = var.azure_aci.module_name
  terraform_managed = var.azure_aci.terraform_managed
  additional_tags   = var.additional_tags

  depends_on = [module.azure_acr]
}