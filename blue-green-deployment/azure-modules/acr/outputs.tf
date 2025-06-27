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

output "repository_url" {
  description = "URL of the ACR registry (equivalent to ECR repository_url)"
  value       = azurerm_container_registry.main.login_server
}

output "image_urls" {
  description = "Map of URLs of the built Docker images"
  value       = { for k, v in var.application : k => "${azurerm_container_registry.main.login_server}:${k}" }
}

output "repository_urls" {
  description = "Map of URLs of the ACR repositories (for backward compatibility)"
  value       = { for k, v in var.application : k => azurerm_container_registry.main.login_server }
}