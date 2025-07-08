// azure-aci-pipeline.groovy - Azure ACI pipeline for apply, switch, and rollback operations

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
            name: 'INITIAL_DEPLOYMENT',
            choices: ['NO', 'YES'],
            description: 'YES: Deploy initial application to Blue containers after infrastructure is created'
        )
        choice(
            name: 'CONFIRM_ROLLBACK',
            choices: ['NO', 'YES'],
            description: 'Confirm you want to rollback to previous version?'
        )
        choice(
            name: 'APP_NAME',
            choices: ['app_1', 'app_2', 'app_3', 'all'],
            description: 'Select which application to deploy/rollback (app_1 is the default app, "all" deploys all apps)'
        )
    }
    
    triggers {
        githubPush()
    }
    
    environment {
        IMPLEMENTATION = 'azure-aci'
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
                        echo "🚀 GitHub push detected - automatically using SWITCH operation"
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
                            env.APP_NAME = "app_1"
                            echo "🟢 Detected changes in app_1.py"
                        } else if (changedFiles.contains("app_2.py")) {
                            env.APP_NAME = "app_2"
                            echo "🟡 Detected changes in app_2.py"
                        } else if (changedFiles.contains("app_3.py")) {
                            env.APP_NAME = "app_3"
                            echo "🔵 Detected changes in app_3.py"
                        } else {
                            env.APP_NAME = "all"
                            echo "⚪ No specific app file changed. Defaulting to all"
                        }
                    } else {
                        echo "🔧 Manual/Parameter build detected - using selected operation: ${operation}"
                        env.APP_NAME = params.APP_NAME
                    }
        
                    env.SELECTED_OPERATION = operation
                    env.IMPLEMENTATION = 'azure-aci'
                    env.SELECTED_IMPLEMENTATION = 'azure-aci'
                    echo "DEBUG: Set implementation to Azure ACI"
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
                    config.implementation = 'azure-aci'
                    config.cloudProvider = 'azure'
                    config.tfVarsFile = 'terraform-azure.tfvars'
                    
                    echo "DEBUG: Config implementation: ${config.implementation}"
                    
                    azureBasePipelineImpl.initialize(config)
                    azureBasePipelineImpl.checkout(config)
                    
                    // Update main.tf to use ACI implementation
                    sh """
                        # Comment out VM implementation
                        sed -i 's/^module "azure_vm_implementation"/# module "azure_vm_implementation"/' main.tf
                        sed -i '/^module "azure_vm_implementation"/,/^}/ s/^/# /' main.tf
                        
                        # Uncomment ACI implementation
                        sed -i 's/^# module "azure_aci_implementation"/module "azure_aci_implementation"/' main.tf
                        sed -i '/^# module "azure_aci_implementation"/,/^# }/ s/^# //' main.tf
                        
                        # Uncomment Azure provider and comment AWS
                        sed -i 's/^# provider "azurerm"/provider "azurerm"/' main.tf
                        sed -i 's/^provider "aws"/# provider "aws"/' main.tf
                        
                        # Comment out azure-auto-register.tf if skip_docker_build is true
                        if grep -q 'skip_docker_build = true' terraform-azure.tfvars; then
                            echo "Commenting out azure-auto-register.tf for production deployment"
                            sed -i 's/^resource "null_resource"/# resource "null_resource"/' azure-auto-register.tf
                            sed -i '/^resource "null_resource"/,/^}/ s/^/# /' azure-auto-register.tf
                        fi
                    """
                    
                    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
                        echo "DEBUG: Before terraform - Implementation: ${config.implementation}"
                        config.implementation = 'azure-aci'
                        echo "DEBUG: Executing Terraform for Azure ACI"
                        azureTerraformInit(config)
                        
                        dir(config.tfWorkingDir) {
                            sh "terraform plan -var-file='${config.tfVarsFile}' -out=tfplan"
                        }
                        
                        azureApprovals.terraformApplyApproval(config)
                        
                        dir(config.tfWorkingDir) {
                            sh "terraform apply -auto-approve tfplan"
                            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
                        }
                    }
                    
                    if (params.MANUAL_BUILD == 'DESTROY') {
                        azureApprovals.terraformDestroyApproval(config)
                        azureAciUtils.cleanResources(config)
                        azureTerraformDestroy(config)
                    }
                }
            }
        }

        stage('Execute Initial Deployment') {
            when {
                allOf {
                    expression { env.SELECTED_OPERATION == 'APPLY' }
                    expression { params.INITIAL_DEPLOYMENT == 'YES' }
                    expression { params.MANUAL_BUILD != 'DESTROY' }
                }
            }
            steps {
                script {
                    def config = azureDeploymentVars()
                    config.implementation = 'azure-aci'
                    config.cloudProvider = 'azure'
                    config.tfVarsFile = 'terraform-azure.tfvars'
                    
                    echo "🚀 Executing initial deployment for Azure ACI..."
                    
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Deploying initial application: ${appName}"
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            azureAciInitialDeploymentImpl.deployInitialApplication(appConfig)
                        }
                    } else {
                        config.appName = params.APP_NAME
                        azureAciInitialDeploymentImpl.deployInitialApplication(config)
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
                    config.implementation = 'azure-aci'
                    config.cloudProvider = 'azure'
                    config.tfVarsFile = 'terraform-azure.tfvars'
                    
                    def changedFiles = []
                    try {
                        def gitDiff = sh(
                            script: "git diff --name-only HEAD~1 HEAD",
                            returnStdout: true
                        ).trim()
                        
                        if (gitDiff) {
                            changedFiles = gitDiff.split('\\n')
                            echo "📝 Changed files: ${changedFiles.join(', ')}"
                        }
                    } catch (Exception e) {
                        echo "⚠️ Could not determine changed files: ${e.message}"
                    }
                    
                    def hasAppSpecificChanges = { appName ->
                        if (changedFiles.isEmpty()) return true
                        
                        def appSuffix = appName.replace("app_", "")
                        for (def file : changedFiles) {
                            if (file.contains("app_${appSuffix}.py") || 
                                file.contains("app${appSuffix}.py") || 
                                file.contains("app${appSuffix}/") ||
                                (appSuffix == "1" && file.contains("app.py"))) {
                                return true
                            }
                        }
                        return false
                    }
                    
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Switching application: ${appName}"
                            
                            if (!hasAppSpecificChanges(appName)) {
                                echo "📄 No changes detected for ${appName}. Skipping deployment."
                                return
                            }
                            
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            
                            azureSwitchPipelineImpl.initialize(appConfig)
                            azureSwitchPipelineImpl.checkout(appConfig)
                            azureSwitchPipelineImpl.detectChanges(appConfig)
                            
                            if (env.DEPLOY_NEW_VERSION == 'true') {
                                echo "🚀 Deploying changes for ${appName}"
                                azureSwitchPipelineImpl.fetchResources(appConfig)
                                azureSwitchPipelineImpl.ensureBackendPoolAssociation(appConfig)
                                azureSwitchPipelineImpl.updateApplication(appConfig)
                                azureSwitchPipelineImpl.testEnvironment(appConfig)
                                azureSwitchPipelineImpl.manualApprovalBeforeSwitchTrafficAzureACI(appConfig)
                                azureSwitchPipelineImpl.switchTraffic(appConfig)
                                azureSwitchPipelineImpl.postSwitchActions(appConfig)
                            }
                        }
                    } else {
                        config.appName = params.APP_NAME
                        
                        azureSwitchPipelineImpl.initialize(config)
                        azureSwitchPipelineImpl.checkout(config)
                        azureSwitchPipelineImpl.detectChanges(config)
                        
                        if (env.DEPLOY_NEW_VERSION == 'true') {
                            azureSwitchPipelineImpl.fetchResources(config)
                            azureSwitchPipelineImpl.ensureBackendPoolAssociation(config)
                            azureSwitchPipelineImpl.updateApplication(config)
                            azureSwitchPipelineImpl.testEnvironment(config)
                            azureSwitchPipelineImpl.manualApprovalBeforeSwitchTrafficAzureACI(config)
                            azureSwitchPipelineImpl.switchTraffic(config)
                            azureSwitchPipelineImpl.postSwitchActions(config)
                        }
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
                    config.implementation = 'azure-aci'
                    config.cloudProvider = 'azure'
                    config.tfVarsFile = 'terraform-azure.tfvars'
                    
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Rolling back application: ${appName}"
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            
                            azureRollbackPipelineImpl.initialize(appConfig)
                            azureRollbackPipelineImpl.fetchResources(appConfig)
                            azureRollbackPipelineImpl.prepareRollback(appConfig)
                            azureRollbackPipelineImpl.testRollbackEnvironment(appConfig)
                            azureRollbackPipelineImpl.manualApprovalBeforeRollbackAzureACI(appConfig)
                            azureRollbackPipelineImpl.executeRollback(appConfig)
                            azureRollbackPipelineImpl.postRollbackActions(appConfig)
                        }
                    } else {
                        config.appName = params.APP_NAME
                        
                        azureRollbackPipelineImpl.initialize(config)
                        azureRollbackPipelineImpl.fetchResources(config)
                        azureRollbackPipelineImpl.prepareRollback(config)
                        azureRollbackPipelineImpl.testRollbackEnvironment(config)
                        azureRollbackPipelineImpl.manualApprovalBeforeRollbackAzureACI(config)
                        azureRollbackPipelineImpl.executeRollback(config)
                        azureRollbackPipelineImpl.postRollbackActions(config)
                    }
                }
            }
        }
    }
}