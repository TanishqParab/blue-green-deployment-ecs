variable "registry_name" {
  description = "Name of the Azure Container Registry"
  type        = string
}

variable "resource_group_name" {
  description = "Name of the Azure Resource Group"
  type        = string
}

variable "location" {
  description = "Azure region for resources"
  type        = string
}

variable "sku" {
  description = "SKU for the Azure Container Registry"
  type        = string
  default     = "Standard"
  validation {
    condition     = contains(["Basic", "Standard", "Premium"], var.sku)
    error_message = "SKU must be one of: Basic, Standard, or Premium."
  }
}

variable "admin_enabled" {
  description = "Enable admin user for the registry"
  type        = bool
  default     = true
}



variable "skip_docker_build" {
  description = "Skip Docker image build and push"
  type        = bool
  default     = false
}

variable "app_py_path_prefix" {
  description = "Prefix path to the app_[app_name].py files"
  type        = string
  default     = ""
}

variable "dockerfile_path" {
  description = "Path to the Dockerfile"
  type        = string
  default     = ""
}

variable "image_name" {
  description = "Name for the Docker image"
  type        = string
  default     = "blue-green-app"
}

variable "image_tag" {
  description = "Tag for the Docker image"
  type        = string
  default     = "latest"
}

variable "docker_build_args" {
  description = "Additional build arguments for Docker build"
  type        = string
  default     = ""
}

variable "file_not_found_message" {
  description = "Message to use when a file is not found"
  type        = string
  default     = "file-not-found"
}

variable "application" {
  description = "Map of applications and their image configurations"
  type = map(object({
    image_name = string
    image_tag  = string
  }))
  default = {}
}

variable "additional_tags" {
  description = "Additional tags for ACR resources"
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
  default     = "azure-acr"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}

variable "app_gateway_name" {
  description = "Application Gateway name for automatic registration"
  type        = string
  default     = ""
}
