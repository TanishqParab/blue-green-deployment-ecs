output "blue_vm_ids" {
  description = "Map of blue VM IDs"
  value = {
    for app_name, vm in azurerm_linux_virtual_machine.blue_vm :
    app_name => vm.id
  }
}

output "green_vm_ids" {
  description = "Map of green VM IDs"
  value = {
    for app_name, vm in azurerm_linux_virtual_machine.green_vm :
    app_name => vm.id
  }
}

output "blue_vm_public_ips" {
  description = "Map of blue VM public IP addresses"
  value = {
    for app_name, ip in azurerm_public_ip.blue_vm_ip :
    app_name => ip.ip_address
  }
}

output "green_vm_public_ips" {
  description = "Map of green VM public IP addresses"
  value = {
    for app_name, ip in azurerm_public_ip.green_vm_ip :
    app_name => ip.ip_address
  }
}

output "blue_vm_private_ips" {
  description = "Map of blue VM private IP addresses"
  value = {
    for app_name, vm in azurerm_linux_virtual_machine.blue_vm :
    app_name => vm.private_ip_address
  }
}

output "green_vm_private_ips" {
  description = "Map of green VM private IP addresses"
  value = {
    for app_name, vm in azurerm_linux_virtual_machine.green_vm :
    app_name => vm.private_ip_address
  }
}