# Azure Jenkins Pipelines

This directory contains Jenkins pipelines for Azure blue-green deployments using the new modular Terraform structure.

## Pipeline Files

### 1. `azure-vm-pipeline.groovy`
- **Purpose**: Azure Virtual Machine based blue-green deployment
- **Based on**: AWS EC2 pipeline structure
- **Implementation**: Uses `azure_vm_implementation` module
- **Features**:
  - Automatic provider switching (comments out AWS, uncomments Azure)
  - VM registration to Application Gateway backend pools
  - Multi-app support (app1, app2, app3)
  - GitHub push triggered deployments

### 2. `azure-aci-pipeline.groovy`
- **Purpose**: Azure Container Instances based blue-green deployment
- **Based on**: AWS ECS pipeline structure
- **Implementation**: Uses `azure_aci_implementation` module
- **Features**:
  - Container image building and deployment
  - Automatic backend pool registration
  - Multi-app container support (app_1, app_2, app_3)
  - Initial deployment support

## Key Differences from AWS Pipelines

### Azure-Specific Configurations
- **tfVarsFile**: Uses `terraform-azure.tfvars`
- **cloudProvider**: Set to `azure`
- **Provider Management**: Automatically switches between AWS and Azure providers
- **Module Selection**: Automatically comments/uncomments appropriate implementation modules

### Modular Structure Integration
- **Implementation Modules**: Uses new modular structure
  - `azure_vm_implementation` for VM deployments
  - `azure_aci_implementation` for container deployments
- **Auto-Registration**: Handles `azure-auto-register.tf` for testing scenarios
- **Clean Separation**: Each pipeline manages its own implementation type

## Usage

### Azure VM Pipeline
```groovy
// Parameters
OPERATION: ['APPLY', 'SWITCH', 'ROLLBACK']
MANUAL_BUILD: ['YES', 'DESTROY', 'NO']
APP_NAME: 'app1', 'app2', 'app3', or empty for all
```

### Azure ACI Pipeline
```groovy
// Parameters
OPERATION: ['APPLY', 'SWITCH', 'ROLLBACK']
MANUAL_BUILD: ['YES', 'DESTROY', 'NO']
INITIAL_DEPLOYMENT: ['NO', 'YES']
APP_NAME: ['app_1', 'app_2', 'app_3', 'all']
```

## Pipeline Flow

### 1. **Determine Operation**
- Detects GitHub push vs manual trigger
- Auto-detects changed application files
- Sets appropriate operation (APPLY/SWITCH/ROLLBACK)

### 2. **Execute Apply**
- Updates `main.tf` to use correct implementation
- Switches cloud providers
- Runs Terraform init/plan/apply
- Registers resources to load balancer

### 3. **Execute Switch**
- Detects application changes
- Updates and deploys new versions
- Switches traffic after approval
- Supports multi-app deployments

### 4. **Execute Rollback**
- Prepares rollback environment
- Requires manual approval
- Executes rollback to previous version
- Updates traffic routing

## Integration with Shared Library

Both pipelines use the existing Jenkins shared library with Azure-specific utility functions:

- `azureVmUtils`: VM management functions
- `azureAciUtils`: Container management functions
- `azureAciInitialDeploymentImpl`: Initial container deployment
- Approval functions for Azure resources

## Environment Variables

- `IMPLEMENTATION`: Set to `azure-vm` or `azure-aci`
- `CLOUD_PROVIDER`: Set to `azure`
- `SELECTED_OPERATION`: Current operation being executed
- `APP_NAME`: Target application for deployment

## Notes

- Pipelines automatically handle the new modular Terraform structure
- Provider switching is handled automatically
- Multi-app support matches the Terraform configuration
- Compatible with existing shared library functions