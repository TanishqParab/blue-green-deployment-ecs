############################################
# Azure VNet Resources
############################################

# Resource Group
resource "azurerm_resource_group" "main" {
  name     = var.resource_group_name
  location = var.location

  tags = merge(
    {
      Name        = var.resource_group_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

# Virtual Network (VNet) - Azure equivalent of AWS VPC
resource "azurerm_virtual_network" "main" {
  name                = var.vnet_name
  address_space       = [var.vnet_cidr]
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  tags = merge(
    {
      Name        = var.vnet_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

############################################
# Public Subnet Resources
############################################

# Public Subnets
resource "azurerm_subnet" "public_subnets" {
  for_each = { for idx, cidr in var.public_subnet_cidrs : idx => cidr }

  name                 = "${var.subnet_name_prefix}-${each.key + 1}"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = [each.value]
}

############################################
# Private Subnet Resources (Optional)
############################################

# Private Subnets (if enabled)
resource "azurerm_subnet" "private_subnets" {
  for_each = var.create_private_subnets ? { for idx, cidr in var.private_subnet_cidrs : idx => cidr } : {}

  name                 = "${var.private_subnet_name_prefix}-${each.key + 1}"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = [each.value]
}

# Public IP for NAT Gateway (if private subnets are needed)
resource "azurerm_public_ip" "nat_gateway" {
  count = var.create_private_subnets && var.create_nat_gateway ? 1 : 0

  name                = "${var.vnet_name}-${var.nat_gateway_ip_suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  allocation_method   = "Static"
  sku                 = "Standard"

  tags = merge(
    {
      Name        = "${var.vnet_name}-${var.nat_gateway_ip_suffix}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

# NAT Gateway (if private subnets are needed)
resource "azurerm_nat_gateway" "main" {
  count = var.create_private_subnets && var.create_nat_gateway ? 1 : 0

  name                = "${var.vnet_name}-${var.nat_gateway_name_suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku_name            = "Standard"

  tags = merge(
    {
      Name        = "${var.vnet_name}-${var.nat_gateway_name_suffix}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

# Associate Public IP with NAT Gateway
resource "azurerm_nat_gateway_public_ip_association" "main" {
  count = var.create_private_subnets && var.create_nat_gateway ? 1 : 0

  nat_gateway_id       = azurerm_nat_gateway.main[0].id
  public_ip_address_id = azurerm_public_ip.nat_gateway[0].id
}

# Associate NAT Gateway with private subnets
resource "azurerm_subnet_nat_gateway_association" "private" {
  for_each = var.create_private_subnets && var.create_nat_gateway ? azurerm_subnet.private_subnets : {}

  subnet_id      = each.value.id
  nat_gateway_id = azurerm_nat_gateway.main[0].id
}