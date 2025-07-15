# Azure VM Implementation Module
# This module provides a clean interface for VM-based deployments

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

module "azure_vm" {
  source = "../../vm"

  resource_group_name = module.azure_vnet.resource_group_name
  location            = var.location
  subnet_id           = module.azure_vnet.public_subnet_ids[0]

  vm_size        = var.azure_vm.vm_size
  admin_username = var.azure_vm.admin_username


  application = var.azure_vm.vm_application

  environment       = var.environment
  module_name       = var.azure_vm.module_name
  terraform_managed = var.azure_vm.terraform_managed
  additional_tags   = var.additional_tags
}