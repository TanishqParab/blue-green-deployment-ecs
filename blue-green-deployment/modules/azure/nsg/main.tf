############################################
# Azure Network Security Group Resources
############################################

# Network Security Group (NSG) - Azure equivalent of AWS Security Groups
resource "azurerm_network_security_group" "main" {
  name                = var.nsg_name
  location            = var.location
  resource_group_name = var.resource_group_name

  tags = merge(
    {
      Name        = var.nsg_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

# Security Rules
resource "azurerm_network_security_rule" "ingress_rules" {
  for_each = { for idx, rule in var.ingress_rules : idx => rule }

  name                        = each.value.name
  priority                    = each.value.priority
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = each.value.protocol
  source_port_range           = "*"
  destination_port_range      = each.value.destination_port_range
  source_address_prefix       = each.value.source_address_prefix
  destination_address_prefix  = "*"
  resource_group_name         = var.resource_group_name
  network_security_group_name = azurerm_network_security_group.main.name
}

# Default outbound rule (allow all)
resource "azurerm_network_security_rule" "egress_all" {
  name                        = "AllowAllOutbound"
  priority                    = 4096
  direction                   = "Outbound"
  access                      = "Allow"
  protocol                    = "*"
  source_port_range           = "*"
  destination_port_range      = "*"
  source_address_prefix       = "*"
  destination_address_prefix  = "*"
  resource_group_name         = var.resource_group_name
  network_security_group_name = azurerm_network_security_group.main.name
}

# Associate NSG with subnets
resource "azurerm_subnet_network_security_group_association" "main" {
  for_each = toset(var.subnet_ids)

  subnet_id                 = each.value
  network_security_group_id = azurerm_network_security_group.main.id
}