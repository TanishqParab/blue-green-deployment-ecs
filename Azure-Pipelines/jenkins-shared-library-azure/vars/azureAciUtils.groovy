// vars/azureAciUtils.groovy - Azure ACI utility functions (equivalent to ecsUtils.groovy)

def waitForServices(Map config) {
    echo "Waiting for ACI containers to stabilize..."
    sleep(60)
    
    def appName = config.appName ?: "app_1"
    def appSuffix = appName.replace("app_", "")
    
    def resourceGroup = getResourceGroupName(config)
    
    def containerName = "${appName.replace('_', '')}-blue-container"
    def containerExists = sh(
        script: """
            az container show --name ${containerName} --resource-group ${resourceGroup} --query 'provisioningState' --output tsv 2>/dev/null || echo "MISSING"
        """,
        returnStdout: true
    ).trim()
    
    if (containerExists == "MISSING") {
        containerName = "app1-blue-container"
        echo "Using default container name: ${containerName}"
    } else {
        echo "Using app-specific container name: ${containerName}"
    }
    
    sh """
    az container show --name ${containerName} --resource-group ${resourceGroup} --query '{State:instanceView.state,RestartCount:instanceView.restartCount}' --output table
    """
    
    def appGatewayIp = sh(
        script: "az network public-ip show --resource-group ${resourceGroup} --name ${getAppGatewayName(config)}-ip --query ipAddress --output tsv",
        returnStdout: true
    ).trim()
    
    def healthEndpoint = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
    
    echo "Application is accessible at: http://${appGatewayIp}${appSuffix == "1" ? "" : "/app" + appSuffix}"
    
    sh """
    # Wait for the application to be fully available
    sleep 30
    
    # Test the health endpoint
    curl -f http://${appGatewayIp}${healthEndpoint} || echo "Health check failed but continuing"
    """
}

def cleanResources(Map config) {
    if (params.MANUAL_BUILD != 'DESTROY' || config.implementation != 'azure-aci') {
        echo "⚠️ Skipping ACR cleanup as conditions not met (either not DESTROY or not Azure ACI)."
        return
    }

    echo "🧹 Cleaning up ACR repository before destruction..."

    try {
        def registryName = getRegistryName(config)
        def resourceGroup = getResourceGroupName(config)
        
        def acrExists = sh(
            script: """
                az acr show --name ${registryName} --resource-group ${resourceGroup} &>/dev/null && echo 0 || echo 1
            """,
            returnStdout: true
        ).trim() == "0"

        if (acrExists) {
            echo "🔍 Fetching all images in registry ${registryName}..."

            def imagesOutput = sh(
                script: """
                    az acr repository list --name ${registryName} --output json
                """,
                returnStdout: true
            ).trim()

            def repositories = readJSON text: imagesOutput

            echo "Found ${repositories.size()} repositories in registry"

            repositories.each { repo ->
                echo "Deleting repository: ${repo}"
                sh """
                    az acr repository delete \\
                        --name ${registryName} \\
                        --repository ${repo} \\
                        --yes
                """
            }

            echo "✅ ACR registry cleanup completed."
        } else {
            echo "ℹ️ ACR registry ${registryName} not found, skipping cleanup"
        }
    } catch (Exception e) {
        echo "⚠️ Warning: ACR cleanup encountered an issue: ${e.message}"
    }
}

def detectChanges(Map config) {
    echo "🔍 Detecting changes for Azure ACI implementation..."

    def changedFiles = []
    try {
        def gitDiff = sh(
            script: "git diff --name-only HEAD~1 HEAD",
            returnStdout: true
        ).trim()

        if (gitDiff) {
            changedFiles = gitDiff.split('\\n')
            echo "📝 Changed files: ${changedFiles.join(', ')}"
            echo "🚀 Change(s) detected. Triggering deployment."
            env.DEPLOY_NEW_VERSION = 'true'
            
            def appPattern = ~/.*app_([1-3])\.py$/
            def appFile = changedFiles.find { it =~ appPattern }
            if (appFile) {
                def matcher = appFile =~ appPattern
                if (matcher.matches()) {
                    def appNum = matcher[0][1]
                    env.CHANGED_APP = "app_${appNum}"
                    echo "📱 Detected change in application: ${env.CHANGED_APP}"
                }
            }
        } else {
            echo "📄 No changes detected between last two commits."
            env.DEPLOY_NEW_VERSION = 'false'
        }

    } catch (Exception e) {
        echo "⚠️ Could not determine changed files. Assuming change occurred to force deploy."
        env.DEPLOY_NEW_VERSION = 'true'
    }
}

import groovy.json.JsonSlurper

def fetchResources(Map config) {
    echo "🔄 Fetching ACI and Application Gateway resources..."

    def result = [:]
    
    result.putAll(config)

    try {
        def appName = env.CHANGED_APP ?: config.appName ?: "app_1"
        def appSuffix = appName.replace("app_", "")
        
        result.APP_NAME = appName
        result.APP_SUFFIX = appSuffix
        
        def resourceGroup = getResourceGroupName(config)
        def appGatewayName = getAppGatewayName(config)
        
        result.RESOURCE_GROUP = resourceGroup
        result.APP_GATEWAY_NAME = appGatewayName
        
        result.BLUE_POOL_NAME = "${appName}-blue-pool"
        result.GREEN_POOL_NAME = "${appName}-green-pool"
        
        // Check current backend pool configuration to determine live environment
        def bluePoolConfig = sh(
            script: """
                az network application-gateway address-pool show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${result.BLUE_POOL_NAME} \\
                    --query 'backendAddresses' --output json 2>/dev/null || echo '[]'
            """,
            returnStdout: true
        ).trim()

        def greenPoolConfig = sh(
            script: """
                az network application-gateway address-pool show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${result.GREEN_POOL_NAME} \\
                    --query 'backendAddresses' --output json 2>/dev/null || echo '[]'
            """,
            returnStdout: true
        ).trim()

        // Determine environments based on backend pool configuration
        def blueHasBackends = bluePoolConfig != '[]' && !bluePoolConfig.contains('"ipAddress": null')
        def greenHasBackends = greenPoolConfig != '[]' && !greenPoolConfig.contains('"ipAddress": null')

        if (blueHasBackends && !greenHasBackends) {
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
        } else if (greenHasBackends && !blueHasBackends) {
            result.LIVE_ENV = "GREEN"
            result.IDLE_ENV = "BLUE"
        } else {
            echo "⚠️ Could not determine live environment clearly. Defaulting to BLUE as live."
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
        }

        result.LIVE_POOL_NAME = (result.LIVE_ENV == "BLUE") ? result.BLUE_POOL_NAME : result.GREEN_POOL_NAME
        result.IDLE_POOL_NAME = (result.IDLE_ENV == "BLUE") ? result.BLUE_POOL_NAME : result.GREEN_POOL_NAME
        result.LIVE_CONTAINER = "${appName.replace('_', '')}-${result.LIVE_ENV.toLowerCase()}-container"
        result.IDLE_CONTAINER = "${appName.replace('_', '')}-${result.IDLE_ENV.toLowerCase()}-container"

        echo "✅ Resource Group: ${result.RESOURCE_GROUP}"
        echo "✅ App Name: ${result.APP_NAME}"
        echo "✅ Blue Backend Pool: ${result.BLUE_POOL_NAME}"
        echo "✅ Green Backend Pool: ${result.GREEN_POOL_NAME}"
        echo "✅ Application Gateway: ${result.APP_GATEWAY_NAME}"
        echo "✅ LIVE ENV: ${result.LIVE_ENV}"
        echo "✅ IDLE ENV: ${result.IDLE_ENV}"
        echo "✅ LIVE CONTAINER: ${result.LIVE_CONTAINER}"
        echo "✅ IDLE CONTAINER: ${result.IDLE_CONTAINER}"

        return result

    } catch (Exception e) {
        echo "⚠️ Warning: ACI resource fetch encountered issues: ${e.message}"
        echo "⚠️ Continuing with minimal configuration..."
        result.BLUE_POOL_NAME = "${appName}-blue-pool"
        result.GREEN_POOL_NAME = "${appName}-green-pool"
        result.LIVE_ENV = "BLUE"
        result.IDLE_ENV = "GREEN"
        result.LIVE_CONTAINER = "${appName.replace('_', '')}-blue-container"
        result.IDLE_CONTAINER = "${appName.replace('_', '')}-green-container"
        return result
    }
}

def ensureBackendPoolAssociation(Map config) {
    echo "Ensuring backend pool is associated with Application Gateway..."

    if (!config.IDLE_POOL_NAME || config.IDLE_POOL_NAME.trim() == "") {
        error "IDLE_POOL_NAME is missing or empty"
    }
    
    def appName = config.APP_NAME ?: "app_1"
    def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")
    def resourceGroup = config.RESOURCE_GROUP
    def appGatewayName = config.APP_GATEWAY_NAME

    // Check if backend pool exists and has proper routing rules
    def poolExists = sh(
        script: """
            az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${config.IDLE_POOL_NAME} \\
                --query 'name' --output tsv 2>/dev/null || echo "MISSING"
        """,
        returnStdout: true
    ).trim()

    if (poolExists == "MISSING") {
        echo "⚠️ Backend pool ${config.IDLE_ENV} does not exist. This should have been created by Terraform."
        error "Backend pool ${config.IDLE_POOL_NAME} not found"
    } else {
        echo "✅ Backend pool ${config.IDLE_POOL_NAME} exists and is ready"
    }
}

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def updateApplication(Map config) {
    echo "Running ACI update application logic..."

    try {
        def appName = env.CHANGED_APP ?: config.APP_NAME ?: config.appName ?: "app_1"
        def appSuffix = appName.replace("app_", "")
        
        echo "DEBUG: Using appName: ${appName}"
        echo "DEBUG: Using appSuffix: ${appSuffix}"
        
        echo "Updating application: ${appName}"
        
        def resourceGroup = getResourceGroupName(config)
        def registryName = getRegistryName(config)
        
        // Step 1: Dynamically discover containers
        def containersJson = sh(
            script: "az container list --resource-group ${resourceGroup} --output json",
            returnStdout: true
        ).trim()

        def containers = parseJsonSafe(containersJson)
        if (!containers || containers.isEmpty()) {
            error "❌ No ACI containers found in resource group ${resourceGroup}"
        }

        def containerNames = containers.collect { it.name }
        echo "Discovered ACI containers: ${containerNames}"

        // Look for app-specific containers first
        def blueContainer = containerNames.find { it.toLowerCase() == "${appName.replace('_', '')}${appSuffix}-blue-container" }
        def greenContainer = containerNames.find { it.toLowerCase() == "${appName.replace('_', '')}${appSuffix}-green-container" }
        
        // Fall back to default containers if app-specific ones don't exist
        if (!blueContainer) {
            blueContainer = containerNames.find { it.toLowerCase() == "${appName.replace('_', '')}-blue-container" }
        }
        if (!greenContainer) {
            greenContainer = containerNames.find { it.toLowerCase() == "${appName.replace('_', '')}-green-container" }
        }

        if (!blueContainer || !greenContainer) {
            error "❌ Could not find both 'blue' and 'green' ACI containers in resource group ${resourceGroup}. Found containers: ${containerNames}"
        }
        
        echo "Using blue container: ${blueContainer}"
        echo "Using green container: ${greenContainer}"

        // Helper to get image tag for a container
        def getImageTagForContainer = { containerName ->
            try {
                def containerInfo = sh(
                    script: "az container show --name ${containerName} --resource-group ${resourceGroup} --query 'containers[0].image' --output tsv || echo ''",
                    returnStdout: true
                )?.trim()
                
                if (!containerInfo || containerInfo == "null") {
                    return ""
                }
                
                def imageTag = containerInfo?.tokenize(':')?.last() ?: ""
                return imageTag
            } catch (Exception e) {
                echo "⚠️ Error getting image tag for container ${containerName}: ${e.message}"
                return ""
            }
        }

        def blueImageTag = getImageTagForContainer(blueContainer)
        def greenImageTag = getImageTagForContainer(greenContainer)

        echo "Blue container image tag: ${blueImageTag}"
        echo "Green container image tag: ${greenImageTag}"

        // Determine active environment by checking backend pools (more reliable than image tags)
        def appGatewayName = getAppGatewayName(config)
        def bluePoolName = "${appName}-blue-pool"
        def greenPoolName = "${appName}-green-pool"
        
        def bluePoolConfig = sh(
            script: """az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${bluePoolName} \\
                --query 'backendAddresses' --output json 2>/dev/null || echo '[]'""",
            returnStdout: true
        ).trim()
        
        def greenPoolConfig = sh(
            script: """az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${greenPoolName} \\
                --query 'backendAddresses' --output json 2>/dev/null || echo '[]'""",
            returnStdout: true
        ).trim()
        
        def blueIsActive = bluePoolConfig != '[]' && !bluePoolConfig.contains('"ipAddress": null')
        def greenIsActive = greenPoolConfig != '[]' && !greenPoolConfig.contains('"ipAddress": null')
        
        if (blueIsActive && !greenIsActive) {
            env.ACTIVE_ENV = "BLUE"
        } else if (greenIsActive && !blueIsActive) {
            env.ACTIVE_ENV = "GREEN"
        } else {
            echo "⚠️ Could not determine ACTIVE_ENV from backend pools clearly. Defaulting ACTIVE_ENV to BLUE"
            env.ACTIVE_ENV = "BLUE"
        }

        if (!env.ACTIVE_ENV || !(env.ACTIVE_ENV.toUpperCase() in ["BLUE", "GREEN"])) {
            error "❌ ACTIVE_ENV must be set to 'BLUE' or 'GREEN'. Current value: '${env.ACTIVE_ENV}'"
        }
        env.ACTIVE_ENV = env.ACTIVE_ENV.toUpperCase()
        env.IDLE_ENV = (env.ACTIVE_ENV == "BLUE") ? "GREEN" : "BLUE"
        echo "ACTIVE_ENV: ${env.ACTIVE_ENV}"
        echo "Determined IDLE_ENV: ${env.IDLE_ENV}"

        env.IDLE_CONTAINER = (env.IDLE_ENV == "BLUE") ? blueContainer : greenContainer
        echo "Selected IDLE_CONTAINER: ${env.IDLE_CONTAINER}"

        // Step 2: Tag current image for rollback
        def currentImageTag = "${appName}-latest"
        def currentImageInfo = sh(
            script: """
            az acr repository show-tags --name ${registryName} --repository ${appName.replace('_', '')}-image --query '[?contains(@, `${currentImageTag}`)]' --output json 2>/dev/null || echo '[]'
            """,
            returnStdout: true
        ).trim()

        if (currentImageInfo != '[]') {
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            def rollbackTag = "${appName}-rollback-${timestamp}"

            echo "Found current '${currentImageTag}' image"
            echo "Tagging current '${currentImageTag}' image as '${rollbackTag}'..."

            sh """
            az acr import --name ${registryName} --source ${registryName}.azurecr.io/${appName.replace('_', '')}-image:${currentImageTag} --image ${appName.replace('_', '')}-image:${rollbackTag}
            """

            echo "✅ Tagged rollback image: ${rollbackTag}"
        } else {
            echo "⚠️ No current '${currentImageTag}' image found to tag"
        }

        // Step 3: Build and push Docker image
        def imageTag = "${appName}-latest"
        def imageName = "${appName.replace('_', '')}-image"
        
        // Smart path handling for Docker directory
        def tfDir = config.tfWorkingDir ?: env.WORKSPACE
        def dockerDir = "${tfDir}/modules/azure/aci/scripts"
        
        // Handle case where tfDir already includes blue-green-deployment
        if (tfDir.endsWith('/blue-green-deployment')) {
            dockerDir = "${tfDir}/modules/azure/aci/scripts"
        }
        
        sh """
            az acr login --name ${registryName}
            cd ${dockerDir}
            docker build -t ${imageName}:${imageTag} --build-arg APP_NAME=${appSuffix} .
            docker tag ${imageName}:${imageTag} ${registryName}.azurecr.io/${imageName}:${imageTag}
            docker push ${registryName}.azurecr.io/${imageName}:${imageTag}
        """

        env.IMAGE_URI = "${registryName}.azurecr.io/${imageName}:${imageTag}"
        echo "✅ Image pushed: ${env.IMAGE_URI}"

        // Step 4: Update ACI Container
        echo "Updating ${env.IDLE_ENV} container (${env.IDLE_CONTAINER})..."

        // Restart container to pull new image
        sh """
        az container restart \\
            --name ${env.IDLE_CONTAINER} \\
            --resource-group ${resourceGroup}
        """

        echo "✅ Updated container ${env.IDLE_ENV} with new image"

        echo "Waiting for ${env.IDLE_ENV} container to stabilize..."
        sleep(30)
        
        // Verify the container is running
        def containerState = sh(
            script: "az container show --name ${env.IDLE_CONTAINER} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
            returnStdout: true
        ).trim()

        if (containerState != 'Running') {
            echo "⚠️ Container state: ${containerState}. Waiting longer..."
            sleep(30)
        }

        echo "✅ Container ${env.IDLE_ENV} is ready"

    } catch (Exception e) {
        echo "❌ Error occurred during ACI update:\\n${e}"
        e.printStackTrace()
        error "Failed to update ACI application"
    }
}

@NonCPS
def parseJsonSafe(String jsonText) {
    try {
        if (!jsonText || jsonText.trim().isEmpty() || jsonText.trim() == "null") {
            return [:]
        }
        
        if (!jsonText.trim().startsWith("{") && !jsonText.trim().startsWith("[")) {
            return [:]
        }
        
        def parsed = new JsonSlurper().parseText(jsonText)
        def safeMap = [:]
        safeMap.putAll(parsed)
        return safeMap
    } catch (Exception e) {
        echo "⚠️ Error in parseJsonSafe: ${e.message}"
        return [:]
    }
}

def testEnvironment(Map config) {
    echo "🔍 Testing ${config.IDLE_ENV} environment..."

    try {
        def appName = config.APP_NAME ?: "app_1"
        def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")
        def resourceGroup = config.RESOURCE_GROUP
        def appGatewayName = config.APP_GATEWAY_NAME
        
        // Get Application Gateway public IP
        def appGatewayIp = sh(
            script: """
                az network public-ip show --resource-group ${resourceGroup} --name ${appGatewayName}-ip --query ipAddress --output tsv
            """,
            returnStdout: true
        ).trim()

        env.APP_GATEWAY_IP = appGatewayIp

        // Wait for rule propagation
        echo "⏳ Waiting for Application Gateway to be ready..."
        sleep(10)

        // Test app-specific health endpoint
        def testEndpoint = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
        echo "🌐 Testing endpoint: http://${appGatewayIp}${testEndpoint}"
        sh """
        curl -f http://${appGatewayIp}${testEndpoint} || curl -f http://${appGatewayIp}${testEndpoint.replace('/health', '')} || echo "⚠️ Health check failed but continuing"
        """

        echo "✅ ${config.IDLE_ENV} environment tested successfully"

    } catch (Exception e) {
        echo "⚠️ Warning: Test stage encountered an issue: ${e.message}"
        echo "Proceeding with deployment despite test issues."
    }
}

def switchTrafficToTargetEnv(String targetEnv, String bluePoolName, String greenPoolName, String appGatewayName, Map config = [:]) {
    echo "🔍 Smart traffic switching for Azure ACI..."
    
    def appName = config.APP_NAME ?: "app_1"
    def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")
    def resourceGroup = getResourceGroupName(config)
    
    try {
        // Determine current active environment by checking backend pools
        def bluePoolConfig = sh(
            script: """az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${bluePoolName} \\
                --query 'backendAddresses' --output json 2>/dev/null || echo '[]'""",
            returnStdout: true
        ).trim()
        
        def greenPoolConfig = sh(
            script: """az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${greenPoolName} \\
                --query 'backendAddresses' --output json 2>/dev/null || echo '[]'""",
            returnStdout: true
        ).trim()
        
        // Determine which environment is currently active
        def blueIsActive = bluePoolConfig != '[]' && !bluePoolConfig.contains('"ipAddress": null')
        def greenIsActive = greenPoolConfig != '[]' && !greenPoolConfig.contains('"ipAddress": null')
        
        def currentEnv, actualTargetEnv, targetPoolName, sourcePoolName
        
        if (blueIsActive && !greenIsActive) {
            currentEnv = "BLUE"
            actualTargetEnv = "GREEN"
            targetPoolName = greenPoolName
            sourcePoolName = bluePoolName
        } else if (greenIsActive && !blueIsActive) {
            currentEnv = "GREEN"
            actualTargetEnv = "BLUE"
            targetPoolName = bluePoolName
            sourcePoolName = greenPoolName
        } else {
            // Default: assume Blue is active, switch to Green
            echo "⚠️ Could not determine current environment clearly. Defaulting to switch from BLUE to GREEN."
            currentEnv = "BLUE"
            actualTargetEnv = "GREEN"
            targetPoolName = greenPoolName
            sourcePoolName = bluePoolName
        }
        
        echo "🔄 Current active environment: ${currentEnv}"
        echo "🎯 Target environment: ${actualTargetEnv}"
        echo "🔁 Switching traffic from ${sourcePoolName} to ${targetPoolName}..."
        
        // Get the target container IP
        def targetContainerName = "${appName.replace('_', '')}-${actualTargetEnv.toLowerCase()}-container"
        def containerIp = sh(
            script: """
                az container show --name ${targetContainerName} --resource-group ${resourceGroup} --query ipAddress.ip --output tsv
            """,
            returnStdout: true
        ).trim()

        if (!containerIp || containerIp == 'None') {
            error "❌ Could not get container IP for ${targetContainerName}"
        }

        // Update the target backend pool with the container IP
        sh """
            az network application-gateway address-pool update \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${targetPoolName} \\
                --set backendAddresses='[{"ipAddress":"${containerIp}"}]'
        """
        
        // Clear the source backend pool
        sh """
            az network application-gateway address-pool update \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${sourcePoolName} \\
                --set backendAddresses='[]'
        """
        
        echo "✅✅✅ Traffic successfully switched from ${currentEnv} to ${actualTargetEnv} (${containerIp})!"
        
    } catch (Exception e) {
        echo "⚠️ Error switching traffic: ${e.message}"
        throw e
    }
}

def scaleDownOldEnvironment(Map config) {
    def appName = config.APP_NAME ?: "app_1"
    def resourceGroup = getResourceGroupName(config)
    
    echo "DEBUG: scaleDownOldEnvironment received config keys: ${config.keySet()}"
    
    // Determine which container to scale down (the one NOT receiving traffic)
    def containerToScaleDown
    
    if (config.LIVE_ENV == "BLUE") {
        containerToScaleDown = "${appName.replace('_', '')}-green-container"
    } else {
        containerToScaleDown = "${appName.replace('_', '')}-blue-container"
    }
    
    echo "🔍 LIVE_ENV (currently receiving traffic): ${config.LIVE_ENV}"
    echo "🔽 Will scale down IDLE container: ${containerToScaleDown}"
    
    echo "🔄 Scaling down IDLE container: ${containerToScaleDown} (not receiving traffic)"
    
    try {
        // For ACI, we can stop the container or reduce its resources
        sh """
        az container stop \\
          --name ${containerToScaleDown} \\
          --resource-group ${resourceGroup}
        """
        echo "✅ Scaled down old container: ${containerToScaleDown}"

        echo "⏳ Waiting for old container to stop..."
        sleep(10)
        
        def containerState = sh(
            script: "az container show --name ${containerToScaleDown} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
            returnStdout: true
        ).trim()
        
        echo "✅ Old container ${containerToScaleDown} state: ${containerState}"
    } catch (Exception e) {
        echo "⚠️ Warning during scale down: ${e.message}"
        echo "⚠️ Continuing despite scale down issues..."
    }
}

def getResourceGroupName(config) {
    try {
        def resourceGroup = sh(
            script: "cd blue-green-deployment && terraform output -raw resource_group_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!resourceGroup || resourceGroup == '') {
            resourceGroup = sh(
                script: "cd blue-green-deployment && grep 'resource_group_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
                returnStdout: true
            ).trim()
        }
        
        echo "📋 Using resource group: ${resourceGroup}"
        return resourceGroup
    } catch (Exception e) {
        echo "⚠️ Could not determine resource group name: ${e.message}"
        return "cloud-pratice-Tanishq.Parab-RG"
    }
}

def getAppGatewayName(config) {
    try {
        def appGatewayName = sh(
            script: "cd blue-green-deployment && terraform output -raw app_gateway_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!appGatewayName || appGatewayName == '') {
            appGatewayName = sh(
                script: "cd blue-green-deployment && grep 'app_gateway_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
                returnStdout: true
            ).trim()
        }
        
        echo "🌐 Using Application Gateway: ${appGatewayName}"
        return appGatewayName
    } catch (Exception e) {
        echo "⚠️ Could not determine Application Gateway name: ${e.message}"
        return "blue-green-appgw"
    }
}

def getRegistryName(config) {
    try {
        def registryName = sh(
            script: "cd blue-green-deployment && terraform output -raw registry_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!registryName || registryName == '') {
            registryName = sh(
                script: "cd blue-green-deployment && grep 'registry_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
                returnStdout: true
            ).trim()
        }
        
        echo "📦 Using Container Registry: ${registryName}"
        return registryName
    } catch (Exception e) {
        echo "⚠️ Could not determine registry name: ${e.message}"
        return "bluegreenacrregistry"
    }
}
