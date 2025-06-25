variable "resource_group_name" {
  description = "Name of the Azure Resource Group"
  type        = string
}

variable "location" {
  description = "Azure region for resources"
  type        = string
}

variable "subnet_id" {
  description = "Subnet ID for VM deployment"
  type        = string
}

variable "vm_size" {
  description = "Size of the Azure VMs"
  type        = string
  default     = "Standard_B2s"
}

variable "admin_username" {
  description = "Admin username for VMs"
  type        = string
  default     = "azureuser"
}

variable "ssh_key_name" {
  description = "Name of the SSH key resource"
  type        = string
}

# Key Vault Configuration
variable "key_vault_name" {
  description = "Name of the Azure Key Vault containing SSH keys"
  type        = string
}

variable "ssh_key_secret_name" {
  description = "Name of the Key Vault secret containing SSH keys JSON"
  type        = string
  default     = "blue-green-ssh-keys"
}

variable "os_disk_type" {
  description = "OS disk storage type"
  type        = string
  default     = "Premium_LRS"
}

variable "vm_image_publisher" {
  description = "VM image publisher"
  type        = string
  default     = "Canonical"
}

variable "vm_image_offer" {
  description = "VM image offer"
  type        = string
  default     = "0001-com-ubuntu-server-focal"
}

variable "vm_image_sku" {
  description = "VM image SKU"
  type        = string
  default     = "20_04-lts-gen2"
}

variable "vm_image_version" {
  description = "VM image version"
  type        = string
  default     = "latest"
}

variable "application" {
  description = "Map of applications and their VM configurations"
  type = map(object({
    blue_vm_name  = string
    green_vm_name = string
    app_port      = optional(number, 5000)
  }))
}

variable "additional_tags" {
  description = "Additional tags for VM resources"
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
  default     = "azure-vm"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}