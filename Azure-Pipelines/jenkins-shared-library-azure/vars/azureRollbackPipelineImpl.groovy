// vars/azureRollbackPipelineImpl.groovy - Azure rollback pipeline implementation

def initialize(Map config) {
    if (params.CONFIRM_ROLLBACK == 'NO') {
        currentBuild.result = 'ABORTED'
        error('Rollback was not confirmed - aborting pipeline')
    }
    
    echo "Starting Azure rollback to previous version"
    currentBuild.displayName = " #${currentBuild.number} - Azure Rollback"
    
    // Set execution type for Azure VM
    if (config.implementation == 'azure-vm') {
        env.EXECUTION_TYPE = 'ROLLBACK'
    }
}

def checkout(Map config) {
    // Only perform checkout for Azure VM rollback
    if (config.implementation == 'azure-vm') {
        if (env.CODE_ALREADY_CHECKED_OUT == 'true') {
            echo "⏩ Skipping checkout for rollback (already done)"
            return
        }

        def repoUrl = config.repoUrl
        def repoBranch = config.repoBranch ?: 'Multi-App'
        def credentialsId = config.repoCredentialsId ?: env.GITHUB_CREDENTIAL_ID

        if (!repoUrl || !credentialsId) {
            error "❌ Missing repoUrl or credentialsId. Cannot proceed with Azure VM rollback checkout."
        }

        echo "🔄 Checking out code from ${repoUrl} [branch: ${repoBranch}] for Azure VM rollback..."

        checkout([$class: 'GitSCM',
            branches: [[name: "*/${repoBranch}"]],
            extensions: [],
            userRemoteConfigs: [[
                url: repoUrl,
                credentialsId: credentialsId
            ]]
        ])

        env.CODE_ALREADY_CHECKED_OUT = 'true'
    } else {
        echo "ℹ️ Skipping checkout: not required for Azure ACI rollback."
    }
}

def fetchResources(Map config) {
    def rollbackConfig = config.clone()
    rollbackConfig.tfWorkingDir = config.tfWorkingDir
    rollbackConfig.sshKeyId = config.sshKeyId
    
    if (config.implementation == 'azure-vm') {
        azureVmRollbackUtils.fetchResources(rollbackConfig)
    } else if (config.implementation == 'azure-aci') {
        azureAciRollbackUtils.fetchResources(rollbackConfig)
        
        // Store app-specific information from the result if available
        if (env.APP_NAME) {
            config.APP_NAME = env.APP_NAME
            config.APP_SUFFIX = env.APP_NAME.replace("app_", "")
        }
    }
}

def manualApprovalBeforeRollbackAzureVM(Map config) {
    if (config.implementation == 'azure-vm') {
        azureApprovals.rollbackApprovalAzureVM(config)
    }
}

def prepareRollback(Map config) {
    def rollbackConfig = config.clone()
    rollbackConfig.tfWorkingDir = config.tfWorkingDir
    rollbackConfig.sshKeyId = config.sshKeyId
    
    if (config.implementation == 'azure-vm') {
        azureVmRollbackUtils.prepareRollback(rollbackConfig)
    } else if (config.implementation == 'azure-aci') {
        azureAciRollbackUtils.prepareRollback(rollbackConfig)
    }
}

def testRollbackEnvironment(Map config) {
    if (config.implementation == 'azure-aci') {
        azureAciRollbackUtils.testRollbackEnvironment(config)
    }
}

def manualApprovalBeforeRollbackAzureACI(Map config) {
    if (config.implementation == 'azure-aci') {
        def appInfo = config.appName ? " for ${config.appName}" : ""
        echo "Requesting approval for Azure ACI rollback${appInfo}..."
        azureApprovals.rollbackApprovalAzureACI(config)
    }
}

def executeRollback(Map config) {
    def rollbackConfig = config.clone()
    rollbackConfig.tfWorkingDir = config.tfWorkingDir
    rollbackConfig.sshKeyId = config.sshKeyId
    
    if (config.implementation == 'azure-vm') {
        azureVmRollbackUtils.executeAzureVmRollback(rollbackConfig)
    } else if (config.implementation == 'azure-aci') {
        azureAciRollbackUtils.executeAzureAciRollback(rollbackConfig)
    }
}

def postRollbackActions(Map config) {
    def rollbackConfig = config.clone()
    
    if (config.implementation == 'azure-aci') {
        azureAciRollbackUtils.postRollbackActions(rollbackConfig)
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
            expression { return config.implementation == 'azure-vm' }
        }
        steps {
            script {
                checkout(config)
            }
        }
    }
    
    stages << stage('Fetch Resources') {
        steps {
            script {
                fetchResources(config)
            }
        }
    }
    
    stages << stage('Manual Approval Before Rollback Azure VM') {
        when {
            expression { return config.implementation == 'azure-vm' }
        }
        steps {
            script {
                manualApprovalBeforeRollbackAzureVM(config)
            }
        }
    }
    
    stages << stage('Prepare Rollback') {
        steps {
            script {
                prepareRollback(config)
            }
        }
    }
    
    stages << stage('Test Rollback Environment') {
        when {
            expression { config.implementation == 'azure-aci' }
        }
        steps {
            script {
                testRollbackEnvironment(config)
            }
        }
    }
    
    stages << stage('Manual Approval Before Rollback Azure ACI') {
        when {
            expression { config.implementation == 'azure-aci' }
        }
        steps {
            script {
                manualApprovalBeforeRollbackAzureACI(config)
            }
        }
    }
    
    stages << stage('Execute Rollback') {
        steps {
            script {
                executeRollback(config)
            }
        }
    }
    
    stages << stage('Post-Rollback Actions') {
        steps {
            script {
                postRollbackActions(config)
            }
        }
    }
    
    return stages
}