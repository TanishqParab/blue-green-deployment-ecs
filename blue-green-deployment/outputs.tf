# outputs.tf - Terraform outputs for Azure resources

/*
# Azure VM Specific Outputs (commented out for ACI implementation)
output "resource_group_name" {
  description = "Name of the Azure resource group"
  value       = try(module.azure_vm_implementation[0].resource_group_name, null)
}

output "app_gateway_name" {
  description = "Name of the Azure Application Gateway"
  value       = try(module.azure_vm_implementation[0].app_gateway_name, null)
}

output "app_gateway_public_ip" {
  description = "Public IP of the Azure Application Gateway"
  value       = try(module.azure_vm_implementation[0].app_gateway_public_ip, null)
}

output "blue_vm_ips" {
  description = "Public IPs of blue VMs"
  value       = try(module.azure_vm_implementation[0].blue_vm_ips, {})
}

output "green_vm_ips" {
  description = "Public IPs of green VMs"
  value       = try(module.azure_vm_implementation[0].green_vm_ips, {})
}
*/

# Azure ACI Specific Outputs
output "resource_group_name" {
  description = "Name of the Azure resource group"
  value       = try(module.azure_aci_implementation[0].resource_group_name, null)
}

output "app_gateway_name" {
  description = "Name of the Azure Application Gateway"
  value       = try(module.azure_aci_implementation[0].app_gateway_name, null)
}

output "app_gateway_public_ip" {
  description = "Public IP of the Azure Application Gateway"
  value       = try(module.azure_aci_implementation[0].app_gateway_public_ip, null)
}

output "blue_container_ips" {
  description = "IPs of blue containers"
  value       = try(module.azure_aci_implementation[0].blue_container_ips, {})
}

output "green_container_ips" {
  description = "IPs of green containers"
  value       = try(module.azure_aci_implementation[0].green_container_ips, {})
}

output "registry_name" {
  description = "Name of the Azure Container Registry"
  value       = try(module.azure_aci_implementation[0].registry_name, null)
}

# Backend Pool Names
output "backend_pools" {
  description = "Application Gateway backend pool names"
  value = {
    app_1_blue  = "app_1-blue-pool"
    app_1_green = "app_1-green-pool"
    app_2_blue  = "app_2-blue-pool"
    app_2_green = "app_2-green-pool"
    app_3_blue  = "app_3-blue-pool"
    app_3_green = "app_3-green-pool"
  }
}