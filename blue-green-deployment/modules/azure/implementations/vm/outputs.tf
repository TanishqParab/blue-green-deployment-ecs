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
  value       = module.azure_app_gateway.app_gateway_public_ip
}

output "blue_vm_ips" {
  description = "IP addresses of blue VMs"
  value       = module.azure_vm.blue_vm_public_ips
}

output "green_vm_ips" {
  description = "IP addresses of green VMs"
  value       = module.azure_vm.green_vm_public_ips
}

output "blue_vm_private_ips" {
  description = "Private IP addresses of blue VMs"
  value       = module.azure_vm.blue_vm_private_ips
}

output "green_vm_private_ips" {
  description = "Private IP addresses of green VMs"
  value       = module.azure_vm.green_vm_private_ips
}
