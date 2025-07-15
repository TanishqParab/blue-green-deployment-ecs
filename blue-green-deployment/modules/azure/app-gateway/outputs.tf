output "app_gateway_id" {
  description = "The ID of the Application Gateway"
  value       = azurerm_application_gateway.main.id
}

output "app_gateway_name" {
  description = "The name of the Application Gateway"
  value       = azurerm_application_gateway.main.name
}

output "app_gateway_public_ip" {
  description = "The public IP address of the Application Gateway"
  value       = azurerm_public_ip.app_gateway.ip_address
}

output "blue_backend_pool_ids" {
  description = "Map of blue backend pool IDs for each application"
  value = {
    for app_name in keys(var.application_target_groups) :
    app_name => "${azurerm_application_gateway.main.id}/backendAddressPools/${app_name}-blue-pool"
  }
}

output "green_backend_pool_ids" {
  description = "Map of green backend pool IDs for each application"
  value = {
    for app_name in keys(var.application_target_groups) :
    app_name => "${azurerm_application_gateway.main.id}/backendAddressPools/${app_name}-green-pool"
  }
}

output "default_static_backend_pool_id" {
  description = "The ID of the default static backend pool for welcome message"
  value       = "${azurerm_application_gateway.main.id}/backendAddressPools/default-static-pool"
}