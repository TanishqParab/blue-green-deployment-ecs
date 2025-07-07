output "resource_group_name" {
  description = "Name of the resource group"
  value       = module.azure_vnet.resource_group_name
}

output "vnet_id" {
  description = "ID of the VNet"
  value       = module.azure_vnet.vnet_id
}

output "public_subnet_ids" {
  description = "IDs of public subnets"
  value       = module.azure_vnet.public_subnet_ids
}

output "app_gateway_public_ip" {
  description = "Public IP of Application Gateway"
  value       = module.azure_app_gateway.public_ip_address
}

output "app_gateway_fqdn" {
  description = "FQDN of Application Gateway"
  value       = module.azure_app_gateway.fqdn
}

output "acr_login_server" {
  description = "ACR login server URL"
  value       = module.azure_acr.login_server
}

output "blue_container_ips" {
  description = "IP addresses of blue containers"
  value       = module.azure_aci.blue_container_ips
}

output "green_container_ips" {
  description = "IP addresses of green containers"
  value       = module.azure_aci.green_container_ips
}

output "blue_container_fqdns" {
  description = "FQDNs of blue containers"
  value       = module.azure_aci.blue_container_fqdns
}

output "green_container_fqdns" {
  description = "FQDNs of green containers"
  value       = module.azure_aci.green_container_fqdns
}