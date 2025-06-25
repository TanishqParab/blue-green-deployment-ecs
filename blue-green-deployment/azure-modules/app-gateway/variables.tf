variable "app_gateway_name" {
  description = "Name of the Application Gateway"
  type        = string
}

variable "location" {
  description = "Azure region for resources"
  type        = string
}

variable "resource_group_name" {
  description = "Name of the Azure Resource Group"
  type        = string
}

variable "gateway_subnet_id" {
  description = "Subnet ID for the Application Gateway"
  type        = string
}

variable "sku_name" {
  description = "SKU name for Application Gateway"
  type        = string
  default     = "Standard_v2"
}

variable "sku_tier" {
  description = "SKU tier for Application Gateway"
  type        = string
  default     = "Standard_v2"
}

variable "capacity" {
  description = "Capacity for Application Gateway"
  type        = number
  default     = 2
}

variable "frontend_port" {
  description = "Frontend port for Application Gateway"
  type        = number
  default     = 80
}

variable "backend_port" {
  description = "Backend port for Application Gateway"
  type        = number
  default     = 5000
}

variable "backend_protocol" {
  description = "Backend protocol for Application Gateway"
  type        = string
  default     = "Http"
}

variable "listener_protocol" {
  description = "Listener protocol for Application Gateway"
  type        = string
  default     = "Http"
}

variable "backend_path" {
  description = "Backend path for health checks"
  type        = string
  default     = "/"
}

variable "cookie_based_affinity" {
  description = "Cookie based affinity setting"
  type        = string
  default     = "Disabled"
}

variable "request_timeout" {
  description = "Request timeout in seconds"
  type        = number
  default     = 60
}

variable "health_check_path" {
  description = "Health check path"
  type        = string
  default     = "/health"
}

variable "health_check_protocol" {
  description = "Health check protocol"
  type        = string
  default     = "Http"
}

variable "health_check_host" {
  description = "Health check host"
  type        = string
  default     = "127.0.0.1"
}

variable "health_check_interval" {
  description = "Health check interval in seconds"
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "Health check timeout in seconds"
  type        = number
  default     = 10
}

variable "unhealthy_threshold" {
  description = "Unhealthy threshold count"
  type        = number
  default     = 3
}

variable "health_check_status_code" {
  description = "Expected health check status code"
  type        = string
  default     = "200"
}

variable "application_target_groups" {
  description = "Map of applications and their target group configurations"
  type = map(object({
    blue_backend_pool_name  = string
    green_backend_pool_name = string
    backend_port            = number
  }))
  default = {}
}

variable "application_paths" {
  description = "Map of applications and their path-based routing configurations"
  type = map(object({
    priority     = number
    path_pattern = string
  }))
  default = {}
}

variable "additional_tags" {
  description = "Additional tags for Application Gateway resources"
  type        = map(string)
  default     = {}
}

variable "environment" {
  description = "Environment name (e.g., dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "module_name" {
  description = "Name of the module for tagging"
  type        = string
  default     = "azure-app-gateway"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}