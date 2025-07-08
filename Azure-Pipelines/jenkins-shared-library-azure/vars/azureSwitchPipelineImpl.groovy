// vars/azureSwitchPipelineImpl.groovy - Azure switch pipeline implementation

def initialize(Map config) {
    def buildId = currentBuild.number
    echo "Current Build ID: ${buildId}"
}

def checkout(Map config) {
    if ((config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'azure-aci')) {

        if (env.CODE_ALREADY_CHECKED_OUT == 'true') {
            echo "⏩ Skipping checkout (already done)"
            return
        }

        if (!config.repoUrl || !config.repoBranch) {
            error "❌ Missing repoUrl or repoBranch in config. Cannot proceed with checkout."
        }

        echo "🔄 Checking out the latest code from ${config.repoUrl} [branch: ${config.repoBranch}]"

        checkout([$class: 'GitSCM',
            branches: [[name: "*/${config.repoBranch}"]],
            extensions: [],
            userRemoteConfigs: [[
                url: config.repoUrl,
                credentialsId: config.repoCredentialsId ?: env.GITHUB_CREDENTIAL_ID
            ]]
        ])

        env.CODE_ALREADY_CHECKED_OUT = 'true'
    } else {
        echo "ℹ️ Skipping checkout: implementation=${config.implementation}, executionType=${env.EXECUTION_TYPE}"
    }
}

def detectChanges(Map config) {
    if (config.implementation == 'azure-vm') {
        azureVmUtils.detectChanges(config)
    } else if (config.implementation == 'azure-aci') {
        azureAciUtils.detectChanges(config)
    }
}

def fetchResources(Map config) {
    if ((config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'azure-vm') {
            azureVmUtils.fetchResources(config)
        } else if (config.implementation == 'azure-aci') {
            def resourceInfo = azureAciUtils.fetchResources(config)

            // Store for later use in environment variables
            env.RESOURCE_GROUP      = resourceInfo.RESOURCE_GROUP
            env.APP_GATEWAY_NAME    = resourceInfo.APP_GATEWAY_NAME
            env.BLUE_POOL_NAME      = resourceInfo.BLUE_POOL_NAME
            env.GREEN_POOL_NAME     = resourceInfo.GREEN_POOL_NAME
            env.LIVE_ENV            = resourceInfo.LIVE_ENV
            env.IDLE_ENV            = resourceInfo.IDLE_ENV
            env.LIVE_POOL_NAME      = resourceInfo.LIVE_POOL_NAME
            env.IDLE_POOL_NAME      = resourceInfo.IDLE_POOL_NAME
            env.LIVE_CONTAINER      = resourceInfo.LIVE_CONTAINER
            env.IDLE_CONTAINER      = resourceInfo.IDLE_CONTAINER
            
            // Store app-specific information
            env.APP_NAME            = resourceInfo.APP_NAME
            env.APP_SUFFIX          = resourceInfo.APP_SUFFIX

            // CRITICAL: Set the target environment for traffic switch
            env.TARGET_ENV = resourceInfo.IDLE_ENV

            // Update config for downstream stages
            config.RESOURCE_GROUP   = env.RESOURCE_GROUP
            config.APP_GATEWAY_NAME = env.APP_GATEWAY_NAME
            config.BLUE_POOL_NAME   = env.BLUE_POOL_NAME
            config.GREEN_POOL_NAME  = env.GREEN_POOL_NAME
            config.LIVE_ENV         = env.LIVE_ENV
            config.IDLE_ENV         = env.IDLE_ENV
            config.LIVE_POOL_NAME   = env.LIVE_POOL_NAME
            config.IDLE_POOL_NAME   = env.IDLE_POOL_NAME
            config.LIVE_CONTAINER   = env.LIVE_CONTAINER
            config.IDLE_CONTAINER   = env.IDLE_CONTAINER
            config.APP_NAME         = env.APP_NAME
            config.APP_SUFFIX       = env.APP_SUFFIX
        }
    }
}

def ensureBackendPoolAssociation(Map config) {
    if (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true') {
        azureAciUtils.ensureBackendPoolAssociation([
            IDLE_POOL_NAME: config.IDLE_POOL_NAME,
            RESOURCE_GROUP: config.RESOURCE_GROUP,
            APP_GATEWAY_NAME: config.APP_GATEWAY_NAME,
            IDLE_ENV: config.IDLE_ENV,
            APP_NAME: config.APP_NAME,
            APP_SUFFIX: config.APP_SUFFIX
        ])
    }
}

def manualApprovalBeforeSwitchTrafficAzureVM(Map config) {
    if (config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') {
        azureApprovals.switchTrafficApprovalAzureVM(config)
    }
}

def updateApplication(Map config) {
    if ((config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
        (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'azure-vm') {
            echo "🔄 Updating application on Azure VM..."
            azureVmUtils.updateApplication(config)
        } else if (config.implementation == 'azure-aci') {
            echo "🔄 Updating application on Azure ACI..."

            azureAciUtils.updateApplication(config)

            // Dynamically set config values from environment
            config.ecsCluster        = env.RESOURCE_GROUP ?: ''
            config.rollbackVersionTag = env.PREVIOUS_VERSION_TAG ?: ''
            config.newImageUri       = env.IMAGE_URI ?: ''
            config.activeEnv         = env.LIVE_ENV ?: ''
            config.idleEnv           = env.IDLE_ENV ?: ''
            config.idleService       = env.IDLE_CONTAINER ?: ''
            config.appName           = env.APP_NAME ?: ''

            echo """
            ✅ Azure ACI Application Update Summary:
            ----------------------------------
            🧱 Resource Group     : ${config.ecsCluster}
            🔵 Active Environment : ${config.activeEnv}
            🟢 Idle Environment   : ${config.idleEnv}
            ⚙️  Idle Container     : ${config.idleService}
            📱 App Name           : ${config.appName}
            🔁 Rollback Version   : ${config.rollbackVersionTag}
            🚀 New Image URI      : ${config.newImageUri}
            """
        } else {
            error "❌ Unsupported implementation type: ${config.implementation}"
        }
    }
}

def deployToTargetAzureVM(Map config) {
    if (config.implementation == 'azure-vm') {
        def deployConfig = [
            appGatewayName: config.appGatewayName ?: 'blue-green-appgw',                
            appName: config.appName ?: "",
            tfWorkingDir: config.tfWorkingDir,
            vmPasswordId: config.vmPasswordId ?: 'azure-vm-password'
        ]
        azureVmUtils.deployToTargetVM(deployConfig)
    }
}

def deployToBlueAzureVM(Map config) {
    // Alias for backward compatibility
    deployToTargetAzureVM(config)
}

def testEnvironment(Map config) {
    if (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true') {
        config.appGatewayName = config.appGatewayName ?: 'blue-green-appgw' 

        azureAciUtils.testEnvironment(config)
    }
}

def manualApprovalBeforeSwitchTrafficAzureACI(Map config) {
    if (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true') {
        echo """
        🟡 Awaiting Manual Approval to Switch Traffic in Azure ACI
        ------------------------------------------------------
        📱 App Name            : ${config.appName}
        🔁 Rollback Version Tag : ${config.rollbackVersionTag}
        🚀 New Image URI        : ${config.newImageUri}
        📦 Resource Group       : ${config.ecsCluster}
        🔵 Active Environment   : ${config.activeEnv}
        🟢 Idle Environment     : ${config.idleEnv}
        ⚙️  Idle Container       : ${config.idleService}
        """

        azureApprovals.switchTrafficApprovalAzureACI(config)
    }
}

def switchTraffic(Map config) {
    if ((config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'azure-vm') {
            def switchConfig = config.clone()
            switchConfig.appName = config.appName ?: ""
            azureVmUtils.switchTraffic(switchConfig)
        } else if (config.implementation == 'azure-aci') {
            azureAciUtils.switchTrafficToTargetEnv(
                env.TARGET_ENV,
                env.BLUE_POOL_NAME,
                env.GREEN_POOL_NAME,
                env.APP_GATEWAY_NAME,
                [
                    APP_NAME: config.APP_NAME,
                    APP_SUFFIX: config.APP_SUFFIX
                ]
            )
        }
    }
}

def postSwitchActions(Map config) {
    if ((config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
        (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'azure-vm') {
            azureVmUtils.tagSwapVMs([
                blueVmTag : 'app1-blue-vm',
                greenVmTag: 'app1-green-vm',
                appName: config.appName ?: ""
            ])
        } else if (config.implementation == 'azure-aci') {
            azureAciUtils.scaleDownOldEnvironment([
                APP_GATEWAY_NAME: config.appGatewayName,
                APP_NAME: config.APP_NAME,
                APP_SUFFIX: config.APP_SUFFIX,
                LIVE_ENV: config.LIVE_ENV
            ])
        }
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
            expression { 
                (config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'azure-aci')
            }
        }
        steps {
            script {
                checkout(config)
            }
        }
    }
    
    stages << stage('Detect Changes') {
        steps {
            script {
                detectChanges(config)
            }
        }
    }
    
    stages << stage('Fetch Resources') {
        when {
            expression { 
                (config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                fetchResources(config)
            }
        }
    }
    
    stages << stage('Ensure Backend Pool Association') {
        when {
            expression { config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true' }
        }
        steps {
            script {
                ensureBackendPoolAssociation(config)
            }
        }
    }
    
    stages << stage('Manual Approval Before Switch Traffic Azure VM') {
        when { 
            expression { config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY' } 
        }
        steps {
            script {
                manualApprovalBeforeSwitchTrafficAzureVM(config)
            }
        }
    }
    
    stages << stage('Update Application') {
        when {
            expression {
                (config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                updateApplication(config)
            }
        }
    }
    
    stages << stage('Deploy to Target Azure VM') {
        when {
            expression { config.implementation == 'azure-vm' }
        }
        steps {
            script {
                deployToTargetAzureVM(config)
            }
        }
    }
    
    stages << stage('Test Environment') {
        when {
            expression { config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true' }
        }
        steps {
            script {
                testEnvironment(config)
            }
        }
    }
    
    stages << stage('Manual Approval Before Switch Traffic Azure ACI') {
        when { 
            expression { config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true' } 
        }
        steps {
            script {
                manualApprovalBeforeSwitchTrafficAzureACI(config)
            }
        }
    }
    
    stages << stage('Switch Traffic') {
        when {
            expression { 
                (config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                switchTraffic(config)
            }
        }
    }
    
    stages << stage('Post-Switch Actions') {
        when {
            expression {
                (config.implementation == 'azure-vm' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                (config.implementation == 'azure-aci' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                postSwitchActions(config)
            }
        }
    }
    
    return stages
}
