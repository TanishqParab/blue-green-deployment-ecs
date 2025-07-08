output "blue_container_group_ids" {
  description = "Map of blue container group IDs"
  value = {
    for app_name, cg in azurerm_container_group.blue :
    app_name => cg.id
  }
}

output "green_container_group_ids" {
  description = "Map of green container group IDs"
  value = {
    for app_name, cg in azurerm_container_group.green :
    app_name => cg.id
  }
}

output "blue_container_group_ips" {
  description = "Map of blue container group IP addresses"
  value = {
    for app_name, cg in azurerm_container_group.blue :
    app_name => cg.ip_address
  }
}

output "green_container_group_ips" {
  description = "Map of green container group IP addresses"
  value = {
    for app_name, cg in azurerm_container_group.green :
    app_name => cg.ip_address
  }
}

output "blue_container_group_fqdns" {
  description = "Map of blue container group FQDNs"
  value = {
    for app_name, cg in azurerm_container_group.blue :
    app_name => cg.fqdn
  }
}

output "green_container_group_fqdns" {
  description = "Map of green container group FQDNs"
  value = {
    for app_name, cg in azurerm_container_group.green :
    app_name => cg.fqdn
  }
}

output "static_welcome_container_ip" {
  description = "IP address of the static welcome container"
  value       = var.skip_docker_build ? null : azurerm_container_group.static_welcome[0].ip_address
}