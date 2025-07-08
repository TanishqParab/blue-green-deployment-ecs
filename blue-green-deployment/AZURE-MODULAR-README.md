# Azure Modular Implementation Guide

## Overview

The Azure Terraform code has been restructured following the COE microservice pattern to provide a clean, modular approach for blue-green deployments. This eliminates the need to comment/uncomment large blocks of code when switching between different implementations.

## New Structure

```
modules/azure/implementations/
├── aci/                    # Container-based implementation
│   ├── main.tf
│   ├── variables.tf
│   └── outputs.tf
└── vm/                     # Virtual machine-based implementation
    ├── main.tf
    ├── variables.tf
    └── outputs.tf
```

## Usage

### 1. Provider Configuration

In `main-multi-cloud.tf`, uncomment the Azure provider and Azure implementation module:

```hcl
# Comment out AWS provider
# provider "aws" {
#   region = var.aws.region
# }

# Uncomment Azure provider
provider "azurerm" {
  features {}
  subscription_id                 = "your-subscription-id"
  resource_provider_registrations = "none"
}

# Uncomment Azure implementation modules
module "azure_aci_implementation" {
  source = "./modules/azure/implementations/aci"
  # ... configuration for container deployment
}

# OR uncomment for VM deployment
# module "azure_vm_implementation" {
#   source = "./modules/azure/implementations/vm"
#   # ... configuration for VM deployment
# }
```

### 2. Implementation Selection

Uncomment ONE of the Azure implementation modules in `main-multi-cloud.tf`:

```hcl
# For container-based deployment (ACI + ACR):
module "azure_aci_implementation" {
  source = "./modules/azure/implementations/aci"
  # ... configuration
}

# For VM-based deployment (comment out ACI and uncomment this):
# module "azure_vm_implementation" {
#   source = "./modules/azure/implementations/vm"
#   # ... configuration
# }
```

### 3. Configuration Files

Use the provided configuration files:

- `terraform-azure-modular.tfvars` - Example configuration
- Modify the `azure_implementation` variable to switch between implementations

### 4. Deployment Commands

```bash
# Initialize Terraform
terraform init

# Plan and apply with chosen implementation
terraform plan -var-file="terraform-azure.tfvars"
terraform apply -var-file="terraform-azure.tfvars"

# To switch implementations:
# 1. Comment out current implementation module
# 2. Uncomment desired implementation module
# 3. Run terraform plan/apply again
```

## Benefits

### 1. Clean Configuration
- No more commenting/uncommenting large blocks of code
- Single variable controls implementation type
- Consistent interface across implementations

### 2. Modular Design
- Each implementation is self-contained
- Easy to add new implementations
- Follows COE microservice patterns

### 3. Simplified Switching
- Change one variable to switch implementations
- No risk of forgetting to comment/uncomment sections
- Validation ensures only valid implementations are used

### 4. Maintainable Code
- Clear separation of concerns
- Reusable components
- Easier testing and debugging

## Implementation Details

### ACI Implementation (`aci`)
Includes:
- Azure VNet with subnets
- Network Security Group
- Application Gateway
- Azure Container Registry (ACR)
- Azure Container Instances (ACI)

### VM Implementation (`vm`)
Includes:
- Azure VNet with subnets
- Network Security Group
- Application Gateway
- Virtual Machines with blue-green setup

### Implementation Modules
- Self-contained ACI and VM implementations
- Clean separation between container and VM approaches
- Easy to switch by commenting/uncommenting

## Migration from Old Structure

1. Update `main-multi-cloud.tf` to use the new module structure
2. Set `azure_implementation` variable in your tfvars file
3. Remove old commented Azure sections
4. Test with `terraform plan` before applying

## Example Workflow

```bash
# 1. Edit main-multi-cloud.tf - uncomment Azure provider and ACI implementation
# 2. Deploy
terraform apply -var-file="terraform-azure.tfvars"

# 3. Switch to VM implementation
# - Comment out azure_aci_implementation module
# - Uncomment azure_vm_implementation module
# - Run: terraform apply -var-file="terraform-azure.tfvars"
```

This modular approach provides a clean, maintainable way to manage multi-cloud blue-green deployments while following enterprise-grade patterns.