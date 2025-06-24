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

variable "georeplications" {
  description = "List of geo-replication configurations"
  type = list(object({
    location                = string
    zone_redundancy_enabled = optional(bool, false)
    tags                    = optional(map(string), {})
  }))
  default = []
}

variable "network_rule_set_enabled" {
  description = "Enable network rule set"
  type        = bool
  default     = false
}

variable "network_rule_default_action" {
  description = "Default action for network rule set"
  type        = string
  default     = "Allow"
}

variable "network_rule_ip_rules" {
  description = "List of IP rules for network rule set"
  type = list(object({
    action   = string
    ip_range = string
  }))
  default = []
}

variable "network_rule_virtual_networks" {
  description = "List of virtual network rules"
  type = list(object({
    action    = string
    subnet_id = string
  }))
  default = []
}

variable "retention_policy_enabled" {
  description = "Enable retention policy"
  type        = bool
  default     = false
}

variable "retention_policy_days" {
  description = "Number of days for retention policy"
  type        = number
  default     = 30
}

variable "trust_policy_enabled" {
  description = "Enable trust policy"
  type        = bool
  default     = false
}

variable "skip_docker_build" {
  description = "Skip Docker image build and push"
  type        = bool
  default     = false
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