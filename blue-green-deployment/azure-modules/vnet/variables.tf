variable "resource_group_name" {
  description = "Name of the Azure Resource Group"
  type        = string
}

variable "location" {
  description = "Azure region for resources"
  type        = string
}

variable "vnet_name" {
  description = "Name of the Azure Virtual Network"
  type        = string
}

variable "vnet_cidr" {
  description = "CIDR block for the Azure VNet"
  type        = string
}

variable "public_subnet_cidrs" {
  description = "List of CIDR blocks for public subnets"
  type        = list(string)
}

variable "subnet_name_prefix" {
  description = "Prefix for subnet names"
  type        = string
  default     = "public-subnet"
}

variable "create_private_subnets" {
  description = "Whether to create private subnets"
  type        = bool
  default     = false
}

variable "private_subnet_cidrs" {
  description = "List of CIDR blocks for private subnets"
  type        = list(string)
  default     = []
}

variable "private_subnet_name_prefix" {
  description = "Prefix for private subnet names"
  type        = string
  default     = "private-subnet"
}

variable "create_nat_gateway" {
  description = "Whether to create a NAT Gateway for private subnets"
  type        = bool
  default     = false
}

variable "nat_gateway_name_suffix" {
  description = "Suffix for NAT Gateway name"
  type        = string
  default     = "nat-gateway"
}

variable "nat_gateway_ip_suffix" {
  description = "Suffix for NAT Gateway IP name"
  type        = string
  default     = "nat-gateway-ip"
}

variable "additional_tags" {
  description = "Additional tags for VNet resources"
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
  default     = "azure-vnet"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}