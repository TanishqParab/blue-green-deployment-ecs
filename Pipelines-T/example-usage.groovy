// Example usage of centralized configuration in Jenkinsfile

@Library('jenkins-shared-library-temp1') _

pipeline {
    agent any
    
    environment {
        DEPLOYMENT_ENV = 'prod' // Change to 'dev', 'staging', or 'prod'
    }
    
    stages {
        stage('Example Usage') {
            steps {
                script {
                    // Get centralized configuration for the environment
                    def config = deploymentVars(env.DEPLOYMENT_ENV)
                    
                    // Now you can use all the centralized values
                    echo "AWS Region: ${config.awsRegion}"
                    echo "ALB Name: ${config.albName}"
                    echo "ECR Repo: ${config.ecrRepoName}"
                    echo "Email Recipient: ${config.emailRecipient}"
                    
                    // Add any runtime-specific values
                    config.appName = params.APP_NAME ?: 'app1'
                    config.implementation = 'ec2' // or 'ecs'
                    
                    // Use the config with your shared library functions
                    // terraformInit(config)
                    // ec2Utils.switchTraffic(config)
                    // etc.
                }
            }
        }
    }
}