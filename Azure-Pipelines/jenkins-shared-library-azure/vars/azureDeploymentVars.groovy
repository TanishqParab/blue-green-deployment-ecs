// vars/azureDeploymentVars.groovy - Azure deployment configuration

def call(String environment = 'default') {
    return [
        // Azure Configuration
        azureRegion: 'East US',
        azureCredentialsId: 'azure-credentials',
        
        // Application Gateway Configuration
        appGatewayName: 'blue-green-appgw',
        
        // Azure Container Registry Configuration
        registryName: 'bluegreenacrregistry',
        
        // Azure Container Instance Configuration
        containerName: 'blue-green-container',
        containerPort: '80',
        
        // Azure VM Configuration
        vmPasswordId: 'azure-vm-password',
        vmUsername: 'azureuser',
        sshPublicKeyId: 'azure-vm-public-key',  // SSH public key for VM creation
        
        // Email Configuration
        emailRecipient: 'tanishqparab2001@gmail.com',
        
        // Repository Configuration
        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment-ecs',
        repoBranch: 'main',
        repoCredentialsId: 'github-repo-enc',
        
        // Working Directory - Azure specific
        tfWorkingDirAzureVM: '/var/lib/jenkins/workspace/Azure-VM-Pipeline/blue-green-deployment',
        tfWorkingDirAzureACI: '/var/lib/jenkins/workspace/Azure-ACI-Pipeline/blue-green-deployment',
        
        // Application Files
        appFile: 'app.py',
        dockerfile: 'Dockerfile',
        
        // Azure-specific tfvars file
        tfVarsFile: 'terraform-azure.tfvars',
        
        // SSH Configuration for Azure VMs
        sshAuthType: 'password',  // 'password' or 'key'
        // Note: Even with password auth, SSH public key is required for VM creation
    ]
}