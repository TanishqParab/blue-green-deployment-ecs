// azure-vm-pipeline.groovy - Azure VM pipeline for apply, switch, and rollback operations

@Library('jenkins-shared-library-azure') _

pipeline {
    agent any
    
    parameters {
        choice(
            name: 'OPERATION',
            choices: ['APPLY', 'SWITCH', 'ROLLBACK'],
            description: 'Select the operation to perform: APPLY (deploy infrastructure), SWITCH (update and switch traffic), or ROLLBACK'
        )
        choice(
            name: 'MANUAL_BUILD', 
            choices: ['YES', 'DESTROY', 'NO'], 
            description: 'YES: Run Terraform, DESTROY: Destroy Infra, NO: Auto Deploy App Changes'
        )
        choice(
            name: 'CONFIRM_ROLLBACK',
            choices: ['NO', 'YES'],
            description: 'Confirm you want to rollback to previous version?'
        )
        string(
            name: 'APP_NAME',
            defaultValue: '',
            description: 'Application name for multi-app deployment (e.g., app1, app2, app3). Leave empty for default app.'
        )
    }
    
    triggers {
        githubPush()
    }
    
    environment {
        IMPLEMENTATION = 'azure-vm'
        CLOUD_PROVIDER = 'azure'
    }
    
    stages {
        stage('Determine Operation') {
            steps {
                script {
                    def operation = params.OPERATION ?: 'APPLY'
                    
                    def isGitHubPush = false
                    def causes = currentBuild.getBuildCauses()
                    causes.each { cause ->
                        if (cause._class?.contains('SCMTrigger') || 
                            cause._class?.contains('GitHubPush') || 
                            cause.shortDescription?.contains('push') ||
                            cause.shortDescription?.contains('SCM')) {
                            isGitHubPush = true
                        }
                    }
                    
                    if (isGitHubPush) {
                        echo "🚀 Build triggered by GitHub push - automatically using SWITCH operation"
                        operation = 'SWITCH'
                        
                        def config = azureDeploymentVars()
                        checkout([$class: 'GitSCM',
                            branches: [[name: "*/${config.repoBranch}"]],
                            extensions: [],
                            userRemoteConfigs: [[
                                url: config.repoUrl,
                                credentialsId: config.repoCredentialsId
                            ]]
                        ])
                        
                        def changedFiles = sh(script: "git diff --name-only HEAD~1 HEAD", returnStdout: true).trim()
                        echo "🔍 Changed files: ${changedFiles}"
                        
                        if (changedFiles.contains("app_1.py")) {
                            env.APP_NAME = "app1"
                        } else if (changedFiles.contains("app_2.py")) {
                            env.APP_NAME = "app2"
                        } else if (changedFiles.contains("app_3.py")) {
                            env.APP_NAME = "app3"
                        } else if (changedFiles.contains("app.py")) {
                            env.APP_NAME = ""
                        }
                    } else {
                        env.APP_NAME = params.APP_NAME
                    }
                    
                    env.SELECTED_OPERATION = operation
                }
            }
        }
        
        stage('Execute Apply') {
            when {
                expression { env.SELECTED_OPERATION == 'APPLY' }
            }
            steps {
                script {
                    def config = azureDeploymentVars()
                    config.implementation = 'azure-vm'
                    config.cloudProvider = 'azure'
                    config.appName = env.APP_NAME
                    config.tfVarsFile = 'terraform-azure.tfvars'
                    
                    azureBasePipelineImpl.initialize(config)
                    azureBasePipelineImpl.checkout(config)
                    
                    // Update main.tf to use VM implementation
                    sh """
                        # Comment out ACI implementation
                        sed -i 's/^module "azure_aci_implementation"/# module "azure_aci_implementation"/' main.tf
                        sed -i '/^module "azure_aci_implementation"/,/^}/ s/^/# /' main.tf
                        
                        # Uncomment VM implementation
                        sed -i 's/^# module "azure_vm_implementation"/module "azure_vm_implementation"/' main.tf
                        sed -i '/^# module "azure_vm_implementation"/,/^# }/ s/^# //' main.tf
                        
                        # Comment out Azure provider if needed
                        sed -i 's/^# provider "azurerm"/provider "azurerm"/' main.tf
                        sed -i 's/^provider "aws"/# provider "aws"/' main.tf
                    """
                    
                    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
                        azureTerraformInit(config)
                        dir(config.tfWorkingDir) {
                            sh "terraform plan -var-file='${config.tfVarsFile}' -out=tfplan"
                        }
                        azureApprovals.terraformApplyApproval(config)
                        dir(config.tfWorkingDir) {
                            sh "terraform apply -auto-approve tfplan"
                        }
                    }
                    
                    if (params.MANUAL_BUILD != 'DESTROY') {
                        if (!env.APP_NAME || env.APP_NAME.trim() == '') {
                            def appNames = ["app1", "app2", "app3"]
                            appNames.each { appName ->
                                def appConfig = config.clone()
                                appConfig.appName = appName
                                try {
                                    azureVmUtils.registerVMsToBackendPools(appConfig)
                                } catch (Exception e) {
                                    echo "⚠️ Warning: Could not register VMs for ${appName}: ${e.message}"
                                }
                            }
                        } else {
                            try {
                                azureVmUtils.registerVMsToBackendPools(config)
                            } catch (Exception e) {
                                echo "⚠️ Warning: Could not register VMs for ${env.APP_NAME}: ${e.message}"
                            }
                        }
                    }
                    
                    if (params.MANUAL_BUILD == 'DESTROY') {
                        azureApprovals.terraformDestroyApproval(config)
                        azureTerraformDestroy(config)
                    }
                }
            }
        }

        stage('Execute Switch') {
            when {
                expression { env.SELECTED_OPERATION == 'SWITCH' }
            }
            steps {
                script {
                    def config = azureDeploymentVars()
                    config.implementation = 'azure-vm'
                    config.cloudProvider = 'azure'
                    config.appName = env.APP_NAME
                    config.tfVarsFile = 'terraform-azure.tfvars'
                    
                    azureSwitchPipelineImpl.initialize(config)
                    azureSwitchPipelineImpl.detectChanges(config)
                    
                    if (env.EXECUTION_TYPE == 'APP_DEPLOY') {
                        azureSwitchPipelineImpl.checkout(config)
                        azureSwitchPipelineImpl.fetchResources(config)
                        azureSwitchPipelineImpl.manualApprovalBeforeSwitchTrafficAzureVM(config)
                        azureSwitchPipelineImpl.updateApplication(config)
                        azureSwitchPipelineImpl.deployToBlueAzureVM(config)
                        azureSwitchPipelineImpl.switchTraffic(config)
                        azureSwitchPipelineImpl.postSwitchActions(config)
                    }
                }
            }
        }
        
        stage('Execute Rollback') {
            when {
                expression { env.SELECTED_OPERATION == 'ROLLBACK' }
            }
            steps {
                script {
                    def config = azureDeploymentVars()
                    config.implementation = 'azure-vm'
                    config.cloudProvider = 'azure'
                    config.appName = env.APP_NAME
                    config.tfVarsFile = 'terraform-azure.tfvars'
                    
                    azureRollbackPipelineImpl.initialize(config)
                    azureRollbackPipelineImpl.checkout(config)
                    azureRollbackPipelineImpl.fetchResources(config)
                    azureRollbackPipelineImpl.manualApprovalBeforeRollbackAzureVM(config)
                    azureRollbackPipelineImpl.prepareRollback(config)
                    azureRollbackPipelineImpl.executeRollback(config)
                    azureRollbackPipelineImpl.postRollbackActions(config)
                }
            }
        }
    }
}