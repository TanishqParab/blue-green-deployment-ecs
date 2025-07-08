// vars/azureBasePipelineImpl.groovy - Azure base pipeline implementation

def initialize(Map config) {
    def buildId = currentBuild.number
    echo "Current Build ID: ${buildId}"
    
    // Set Execution Type
    env.EXECUTION_TYPE = 'SKIP'
    if (params.MANUAL_BUILD == 'DESTROY') {
        echo "❌ Destroy requested. Running destroy stage only."
        env.EXECUTION_TYPE = 'DESTROY'
    } else if (params.MANUAL_BUILD == 'YES') {
        echo "🛠️ Manual build requested. Running Terraform regardless of changes."
        env.EXECUTION_TYPE = 'MANUAL_APPLY'
    }
    echo "Final Execution Type: ${env.EXECUTION_TYPE}"
}

def checkout(Map config) {
    if ((config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'azure-aci')) {

        if (env.CODE_ALREADY_CHECKED_OUT == 'true') {
            echo "⏩ Skipping checkout (already done)"
            return
        }

        echo "🔄 Checking out the latest code from ${config.repoUrl} [branch: ${config.repoBranch}]"

        checkout([$class: 'GitSCM',
            branches: [[name: "*/${config.repoBranch}"]],
            extensions: [],
            userRemoteConfigs: [[
                url: config.repoUrl,
                credentialsId: env.GITHUB_CREDENTIAL_ID
            ]]
        ])
        
        env.CODE_ALREADY_CHECKED_OUT = 'true'
    }
}

def terraformInit(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        azureTerraformInit(config)
    }
}

def terraformPlan(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        azureTerraformPlan(config)
    }
}

def manualApproval(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        azureApprovals.terraformApplyApproval(config)
    }
}

def applyInfrastructure(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        azureTerraformApply(config)
    }
}

def registerAzureVMs(Map config) {
    if (config.implementation == 'azure-vm' && params.MANUAL_BUILD != 'DESTROY') {
        azureVmUtils.registerVMsToBackendPools(config)
    }
}

def manualApprovalForDestroy(Map config) {
    if (params.MANUAL_BUILD == 'DESTROY') {
        azureApprovals.terraformDestroyApproval(config)
    }
}

def cleanResources(Map config) {
    if (params.MANUAL_BUILD == 'DESTROY' && config.implementation == 'azure-aci') {
        // For multi-app support, clean resources for all apps if specified
        if (params.APP_NAME == 'all') {
            echo "Cleaning resources for all apps..."
            ['app_1', 'app_2', 'app_3'].each { appName ->
                def appConfig = config.clone()
                appConfig.appName = appName
                azureAciUtils.cleanResources(appConfig)
            }
        } else {
            // Clean resources for specific app
            config.appName = params.APP_NAME ?: "app_1"
            azureAciUtils.cleanResources(config)
        }
    }
}

def destroyInfrastructure(Map config) {
    if (params.MANUAL_BUILD == 'DESTROY') {
        azureTerraformDestroy(config)
    }
}

def getStages(Map config) {
    def stages = []
    
    stages << stage('Initialize') {
        steps {
            script {
                initialize(config)
            }
        }
    }
    
    stages << stage('Checkout') {
        when {
            expression { env.EXECUTION_TYPE != 'ROLLBACK' }
        }
        steps {
            script {
                checkout(config)
            }
        }
    }
    
    stages << stage('Terraform Init') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                terraformInit(config)
            }
        }
    }
    
    stages << stage('Terraform Plan') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                terraformPlan(config)
            }
        }
    }
    
    stages << stage('Manual Approval') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                manualApproval(config)
            }
        }
    }
    
    stages << stage('Apply Infrastructure') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                applyInfrastructure(config)
            }
        }
    }
    
    stages << stage('Register Azure VMs to Backend Pools') {
        when {
            allOf {
                expression { config.implementation == 'azure-vm' }
                expression { params.MANUAL_BUILD != 'DESTROY' }
            }
        }
        steps {
            script {
                registerAzureVMs(config)
            }
        }
    }
    
    stages << stage('Manual Approval for Destroy') {
        when {
            expression { params.MANUAL_BUILD == 'DESTROY' }
        }
        steps {
            script {
                manualApprovalForDestroy(config)
            }
        }
    }
    
    stages << stage('Clean Resources') {
        when {
            expression { params.MANUAL_BUILD == 'DESTROY' && config.implementation == 'azure-aci' }
        }
        steps {
            script {
                cleanResources(config)
            }
        }
    }
    
    stages << stage('Destroy Infrastructure') {
        when {
            expression { params.MANUAL_BUILD == 'DESTROY' }
        }
        steps {
            script {
                destroyInfrastructure(config)
            }
        }
    }
    
    return stages
}