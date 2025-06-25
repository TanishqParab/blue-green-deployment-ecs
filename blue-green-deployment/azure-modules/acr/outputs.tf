output "registry_id" {
  description = "The ID of the Azure Container Registry"
  value       = azurerm_container_registry.main.id
}

output "registry_name" {
  description = "The name of the Azure Container Registry"
  value       = azurerm_container_registry.main.name
}

output "login_server" {
  description = "The login server URL of the Azure Container Registry"
  value       = azurerm_container_registry.main.login_server
}

output "admin_username" {
  description = "The admin username of the Azure Container Registry"
  value       = azurerm_container_registry.main.admin_username
  sensitive   = true
}

output "admin_password" {
  description = "The admin password of the Azure Container Registry"
  value       = azurerm_container_registry.main.admin_password
  sensitive   = true
}

output "image_urls" {
  description = "Map of full image URLs for each application"
  value = {
    for app_name, config in var.application :
    app_name => "${azurerm_container_registry.main.login_server}/${config.image_name}:${config.image_tag}"
  }
}