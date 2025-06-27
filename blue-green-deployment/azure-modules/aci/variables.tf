variable "resource_group_name" {
  description = "Name of the Azure Resource Group"
  type        = string
}

variable "location" {
  description = "Azure region for resources"
  type        = string
}

variable "container_registry_url" {
  description = "URL of the container registry"
  type        = string
}

variable "container_registry_username" {
  description = "Username for container registry"
  type        = string
}

variable "container_registry_password" {
  description = "Password for container registry"
  type        = string
  sensitive   = true
}

variable "ip_address_type" {
  description = "IP address type for container groups"
  type        = string
  default     = "Public"
}

variable "os_type" {
  description = "Operating system type for containers"
  type        = string
  default     = "Linux"
}

variable "restart_policy" {
  description = "Restart policy for container groups"
  type        = string
  default     = "Always"
}

variable "cpu" {
  description = "CPU allocation for containers"
  type        = string
  default     = "0.5"
}

variable "memory" {
  description = "Memory allocation for containers in GB"
  type        = string
  default     = "1.5"
}

variable "container_protocol" {
  description = "Protocol for container ports"
  type        = string
  default     = "TCP"
}

variable "health_check_path" {
  description = "Health check path"
  type        = string
  default     = "/health"
}

variable "health_check_scheme" {
  description = "Health check scheme"
  type        = string
  default     = "Http"
}

variable "health_check_initial_delay" {
  description = "Initial delay for health checks in seconds"
  type        = number
  default     = 30
}

variable "health_check_period" {
  description = "Period between health checks in seconds"
  type        = number
  default     = 10
}

variable "health_check_failure_threshold" {
  description = "Failure threshold for health checks"
  type        = number
  default     = 3
}

variable "health_check_success_threshold" {
  description = "Success threshold for health checks"
  type        = number
  default     = 1
}

variable "health_check_timeout" {
  description = "Timeout for health checks in seconds"
  type        = number
  default     = 5
}

variable "container_environment_variables" {
  description = "Environment variables for containers"
  type        = map(string)
  default     = {}
}

variable "container_volumes" {
  description = "Volumes for containers"
  type = list(object({
    name                 = string
    mount_path           = string
    read_only            = optional(bool, false)
    empty_dir            = optional(bool, false)
    storage_account_name = optional(string)
    storage_account_key  = optional(string)
    share_name           = optional(string)
  }))
  default = []
}

variable "application" {
  description = "Map of applications and their container configurations"
  type = map(object({
    blue_container_group_name  = string
    green_container_group_name = string
    container_name             = string
    image_name                 = string
    container_port             = number
  }))
}

variable "additional_tags" {
  description = "Additional tags for ACI resources"
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
  default     = "azure-aci"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}

variable "skip_docker_build" {
  description = "Skip Docker build and backend registration"
  type        = bool
  default     = false
}

variable "app_gateway_id" {
  description = "Application Gateway ID for backend pool registration"
  type        = string
  default     = ""
}