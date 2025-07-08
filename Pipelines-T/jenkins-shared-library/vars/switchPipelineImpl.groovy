// vars/switchPipelineImpl.groovy

// Implementation functions for switchPipeline

def initialize(Map config) {
    def buildId = currentBuild.number
    echo "Current Build ID: ${buildId}"
}

def checkout(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'ecs')) {

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
    if (config.implementation == 'ec2') {
        ec2Utils.detectChanges(config)
    } else if (config.implementation == 'ecs') {
        ecsUtils.detectChanges(config)
    }
}

def fetchResources(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            ec2Utils.fetchResources(config)
        } else if (config.implementation == 'ecs') {
            def resourceInfo = ecsUtils.fetchResources(config)

            // Store for later use in environment variables
            env.ECS_CLUSTER     = resourceInfo.ECS_CLUSTER
            env.BLUE_TG_ARN     = resourceInfo.BLUE_TG_ARN
            env.GREEN_TG_ARN    = resourceInfo.GREEN_TG_ARN
            env.ALB_ARN         = resourceInfo.ALB_ARN
            env.LISTENER_ARN    = resourceInfo.LISTENER_ARN
            env.LIVE_ENV        = resourceInfo.LIVE_ENV
            env.IDLE_ENV        = resourceInfo.IDLE_ENV
            env.LIVE_TG_ARN     = resourceInfo.LIVE_TG_ARN
            env.IDLE_TG_ARN     = resourceInfo.IDLE_TG_ARN
            env.LIVE_SERVICE    = resourceInfo.LIVE_SERVICE
            env.IDLE_SERVICE    = resourceInfo.IDLE_SERVICE
            
            // Store app-specific information
            env.APP_NAME        = resourceInfo.APP_NAME
            env.APP_SUFFIX      = resourceInfo.APP_SUFFIX

            // CRITICAL: Set the target environment for traffic switch
            env.TARGET_ENV = resourceInfo.IDLE_ENV

            // Optionally update config for downstream stages
            config.ECS_CLUSTER  = env.ECS_CLUSTER
            config.BLUE_TG_ARN  = env.BLUE_TG_ARN
            config.GREEN_TG_ARN = env.GREEN_TG_ARN
            config.ALB_ARN      = env.ALB_ARN
            config.LISTENER_ARN = env.LISTENER_ARN
            config.LIVE_ENV     = env.LIVE_ENV
            config.IDLE_ENV     = env.IDLE_ENV
            config.LIVE_TG_ARN  = env.LIVE_TG_ARN
            config.IDLE_TG_ARN  = env.IDLE_TG_ARN
            config.LIVE_SERVICE = env.LIVE_SERVICE
            config.IDLE_SERVICE = env.IDLE_SERVICE
            config.APP_NAME     = env.APP_NAME
            config.APP_SUFFIX   = env.APP_SUFFIX
        }
    }
}


def ensureTargetGroupAssociation(Map config) {
    if (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true') {
        // Pass the updated config with required parameters explicitly
        ecsUtils.ensureTargetGroupAssociation([
            IDLE_TG_ARN: config.IDLE_TG_ARN,
            LISTENER_ARN: config.LISTENER_ARN,
            IDLE_ENV: config.IDLE_ENV,
            APP_NAME: config.APP_NAME,
            APP_SUFFIX: config.APP_SUFFIX
        ])
    }
}

def manualApprovalBeforeSwitchTrafficEC2(Map config) {
    if (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') {
        approvals.switchTrafficApprovalEC2(config)
    }
}

def updateApplication(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            echo "🔄 Updating application on EC2..."
            ec2Utils.updateApplication(config)
        } else if (config.implementation == 'ecs') {
            echo "🔄 Updating application on ECS..."

            // Run ECS update logic (discover ECS cluster, build & push image, update idle service)
            ecsUtils.updateApplication(config)

            // Dynamically set config values from environment
            config.ecsCluster        = env.ECS_CLUSTER ?: ''
            config.rollbackVersionTag = env.PREVIOUS_VERSION_TAG ?: ''
            config.newImageUri       = env.IMAGE_URI ?: ''
            config.activeEnv         = env.ACTIVE_ENV ?: ''
            config.idleEnv           = env.IDLE_ENV ?: ''
            config.idleService       = env.IDLE_SERVICE ?: ''
            config.appName           = env.APP_NAME ?: ''

            echo """
            ✅ ECS Application Update Summary:
            ----------------------------------
            🧱 ECS Cluster        : ${config.ecsCluster}
            🔵 Active Environment : ${config.activeEnv}
            🟢 Idle Environment   : ${config.idleEnv}
            ⚙️  Idle Service       : ${config.idleService}
            📱 App Name           : ${config.appName}
            🔁 Rollback Version   : ${config.rollbackVersionTag}
            🚀 New Image URI      : ${config.newImageUri}
            """
        } else {
            error "❌ Unsupported implementation type: ${config.implementation}"
        }
    }
}

def deployToBlueEC2Instance(Map config) {
    if (config.implementation == 'ec2') {
        // Prepare the config parameters needed by deployToBlueInstance
        def deployConfig = [
            albName: config.albName ?: 'blue-green-alb',                
            blueTargetGroupName: config.blueTargetGroupName ?: 'blue-tg', 
            blueTag: config.blueTag ?: 'Blue-Instance',
            appName: config.appName ?: "",
            tfWorkingDir: config.tfWorkingDir,
            sshKeyId: config.sshKeyId
        ]
        ec2Utils.deployToBlueInstance(deployConfig)
    }
}

def testEnvironment(Map config) {
    if (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true') {
        // Use ALB name from centralized config
        config.albName = config.albName ?: 'blue-green-alb' 

        ecsUtils.testEnvironment(config)
    }
}

def manualApprovalBeforeSwitchTrafficECS(Map config) {
    if (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true') {
        echo """
        🟡 Awaiting Manual Approval to Switch Traffic in ECS
        ------------------------------------------------------
        📱 App Name            : ${config.appName}
        🔁 Rollback Version Tag : ${config.rollbackVersionTag}
        🚀 New Image URI        : ${config.newImageUri}
        📦 ECS Cluster          : ${config.ecsCluster}
        🔵 Active Environment   : ${config.activeEnv}
        🟢 Idle Environment     : ${config.idleEnv}
        ⚙️  Idle Service         : ${config.idleService}
        """

        approvals.switchTrafficApprovalECS(config)
    }
}

def switchTraffic(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            // Add appName to config if it exists
            def switchConfig = config.clone()
            switchConfig.appName = config.appName ?: ""
            ec2Utils.switchTraffic(switchConfig)
        } else if (config.implementation == 'ecs') {
            ecsUtils.switchTrafficToTargetEnv(
                env.TARGET_ENV,
                env.BLUE_TG_ARN,
                env.GREEN_TG_ARN,
                env.LISTENER_ARN,
                [
                    APP_NAME: config.APP_NAME,
                    APP_SUFFIX: config.APP_SUFFIX
                ]
            )
        }
    }
}


def postSwitchActions(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            // Call shared library method with parameters
            ec2Utils.tagSwapInstances([
                blueTag : 'Blue-Instance',
                greenTag: 'Green-Instance',
                appName: config.appName ?: ""
            ])
        } else if (config.implementation == 'ecs') {
            // Pass ALB name or other minimal config needed by scaleDownOldEnvironment
            ecsUtils.scaleDownOldEnvironment([
                ALB_NAME: config.albName,
                APP_NAME: config.APP_NAME,
                APP_SUFFIX: config.APP_SUFFIX
            ])
        }
    }
}

// Return all the stages for the pipeline
def getStages(Map config) {
    def stages = []
    
    // Initialize stage
    stages << stage('Initialize') {
        steps {
            script {
                initialize(config)
            }
        }
    }
    
    // Checkout stage
    stages << stage('Checkout') {
        when {
            expression { 
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'ecs')
            }
        }
        steps {
            script {
                checkout(config)
            }
        }
    }
    
    // Detect Changes stage
    stages << stage('Detect Changes') {
        steps {
            script {
                detectChanges(config)
            }
        }
    }
    
    // Fetch Resources stage
    stages << stage('Fetch Resources') {
        when {
            expression { 
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                fetchResources(config)
            }
        }
    }
    
    // Ensure Target Group Association stage
    stages << stage('Ensure Target Group Association') {
        when {
            expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
        }
        steps {
            script {
                ensureTargetGroupAssociation(config)
            }
        }
    }
    
    // Manual Approval Before Switch Traffic EC2 stage
    stages << stage('Manual Approval Before Switch Traffic EC2') {
        when { 
            expression { config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY' } 
        }
        steps {
            script {
                manualApprovalBeforeSwitchTrafficEC2(config)
            }
        }
    }
    
    // Update Application stage
    stages << stage('Update Application') {
        when {
            expression {
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                updateApplication(config)
            }
        }
    }
    
    // Deploy to Blue EC2 Instance stage
    stages << stage('Deploy to Blue EC2 Instance') {
        when {
            expression { config.implementation == 'ec2' }
        }
        steps {
            script {
                deployToBlueEC2Instance(config)
            }
        }
    }
    
    // Test Environment stage
    stages << stage('Test Environment') {
        when {
            expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
        }
        steps {
            script {
                testEnvironment(config)
            }
        }
    }
    
    // Manual Approval Before Switch Traffic ECS stage
    stages << stage('Manual Approval Before Switch Traffic ECS') {
        when { 
            expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' } 
        }
        steps {
            script {
                manualApprovalBeforeSwitchTrafficECS(config)
            }
        }
    }
    
    // Switch Traffic stage
    stages << stage('Switch Traffic') {
        when {
            expression { 
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                switchTraffic(config)
            }
        }
    }
    
    // Post-Switch Actions stage
    stages << stage('Post-Switch Actions') {
        when {
            expression {
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
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
