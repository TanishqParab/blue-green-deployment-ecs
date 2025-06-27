variable "nsg_name" {
  description = "Name of the Network Security Group"
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

variable "subnet_ids" {
  description = "List of subnet IDs to associate with the NSG"
  type        = list(string)
  default     = []
}

variable "ingress_rules" {
  description = "List of ingress rules for the NSG"
  type = list(object({
    name                   = string
    priority               = number
    protocol               = string
    destination_port_range = string
    source_address_prefix  = string
  }))
  default = [
    {
      name                   = "HTTP"
      priority               = 1001
      protocol               = "Tcp"
      destination_port_range = "80"
      source_address_prefix  = "*"
    },
    {
      name                   = "HTTPS"
      priority               = 1002
      protocol               = "Tcp"
      destination_port_range = "443"
      source_address_prefix  = "*"
    },
    {
      name                   = "Flask"
      priority               = 1003
      protocol               = "Tcp"
      destination_port_range = "5000"
      source_address_prefix  = "*"
    },
    {
      name                   = "SSH"
      priority               = 1004
      protocol               = "Tcp"
      destination_port_range = "22"
      source_address_prefix  = "*"
    }
  ]
}

variable "additional_tags" {
  description = "Additional tags for NSG resources"
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
  default     = "azure-nsg"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}