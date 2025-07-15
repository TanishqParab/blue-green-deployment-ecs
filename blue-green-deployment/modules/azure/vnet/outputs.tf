output "resource_group_name" {
  description = "The name of the Azure Resource Group"
  value       = data.azurerm_resource_group.main.name
}

output "resource_group_location" {
  description = "The location of the Azure Resource Group"
  value       = data.azurerm_resource_group.main.location
}

output "vnet_id" {
  description = "The ID of the Azure Virtual Network"
  value       = azurerm_virtual_network.main.id
}

output "vnet_name" {
  description = "The name of the Azure Virtual Network"
  value       = azurerm_virtual_network.main.name
}

output "vnet_address_space" {
  description = "The address space of the Azure Virtual Network"
  value       = azurerm_virtual_network.main.address_space
}

output "public_subnet_ids" {
  description = "List of IDs of Azure public subnets"
  value       = [for subnet in azurerm_subnet.public_subnets : subnet.id]
}

output "public_subnet_names" {
  description = "List of names of Azure public subnets"
  value       = [for subnet in azurerm_subnet.public_subnets : subnet.name]
}

output "private_subnet_ids" {
  description = "List of IDs of Azure private subnets"
  value       = var.create_private_subnets ? [for subnet in azurerm_subnet.private_subnets : subnet.id] : []
}

output "private_subnet_names" {
  description = "List of names of Azure private subnets"
  value       = var.create_private_subnets ? [for subnet in azurerm_subnet.private_subnets : subnet.name] : []
}

output "nat_gateway_id" {
  description = "The ID of the Azure NAT Gateway"
  value       = var.create_private_subnets && var.create_nat_gateway ? azurerm_nat_gateway.main[0].id : null
}

output "nat_gateway_public_ip" {
  description = "The public IP address of the Azure NAT Gateway"
  value       = var.create_private_subnets && var.create_nat_gateway ? azurerm_public_ip.nat_gateway[0].ip_address : null
}

output "app_gateway_subnet_id" {
  description = "The ID of the Application Gateway subnet"
  value       = azurerm_subnet.app_gateway_subnet.id
}