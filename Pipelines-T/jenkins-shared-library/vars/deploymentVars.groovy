// vars/deploymentVars.groovy

def call(String environment = 'default') {
    // Your existing configuration - no changes needed
    return [
        // AWS Configuration
        awsRegion: 'us-east-1',
        awsCredentialsId: 'aws-credentials',
        
        // ALB Configuration
        albName: 'blue-green-alb',
        
        // ECR Configuration
        ecrRepoName: 'blue-green-app',
        
        // ECS Configuration
        containerName: 'blue-green-container',
        containerPort: '80',
        
        // EC2 Configuration
        sshKeyId: 'blue-green-key',
        
        // Email Configuration
        emailRecipient: 'tanishqparab2001@gmail.com',
        
        // Repository Configuration
        repoUrl: 'https://github.com/EncoraDigital/COE-AWS-BlueGreenDeployment-POC',
        repoBranch: 'Multi-App',
        repoCredentialsId: 'github-repo-enc',
        
        // Working Directory - updated to match actual Jenkins workspace
        tfWorkingDirEC2: '/var/lib/jenkins/workspace/EC2-Unified-Pipeline/blue-green-deployment',
        tfWorkingDirECS: '/var/lib/jenkins/workspace/ECS-Unified-Pipeline/blue-green-deployment',
        
        // Application Files
        appFile: 'app.py',
        dockerfile: 'Dockerfile'
    ]
}