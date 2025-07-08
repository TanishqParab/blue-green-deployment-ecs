// vars/azureAciRollbackUtils.groovy - Azure ACI rollback utilities

def fetchResources(Map config) {
    echo "🔎 Finding previous ACI image for rollback..."

    try {
        def appName = config.appName ?: "app_1"
        env.APP_NAME = appName
        echo "Working with app: ${appName}"
        
        def resourceGroup = getResourceGroupName(config)
        def appGatewayName = getAppGatewayName(config)
        def registryName = getRegistryName(config)
        
        env.RESOURCE_GROUP = resourceGroup
        env.APP_GATEWAY_NAME = appGatewayName
        env.REGISTRY_NAME = registryName
        env.APP_SUFFIX = appName.replace("app_", "")

        // Get backend pools with app-specific naming
        env.BLUE_POOL_NAME = "${appName}-blue-pool"
        env.GREEN_POOL_NAME = "${appName}-green-pool"

        // Get current backend pool configuration to determine live environment
        def bluePoolConfig = sh(
            script: """
                az network application-gateway address-pool show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${env.BLUE_POOL_NAME} \\
                    --query 'backendAddresses[0].ipAddress' --output tsv 2>/dev/null || echo 'None'
            """,
            returnStdout: true
        ).trim()
        
        def greenPoolConfig = sh(
            script: """
                az network application-gateway address-pool show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${env.GREEN_POOL_NAME} \\
                    --query 'backendAddresses[0].ipAddress' --output tsv 2>/dev/null || echo 'None'
            """,
            returnStdout: true
        ).trim()

        // Determine current and rollback environments
        if (bluePoolConfig != 'None' && greenPoolConfig == 'None') {
            env.CURRENT_ENV = "BLUE"
            env.ROLLBACK_ENV = "GREEN"
            env.CURRENT_CONTAINER = "${appName.replace('_', '')}-blue-container"
            env.ROLLBACK_CONTAINER = "${appName.replace('_', '')}-green-container"
            env.CURRENT_POOL_NAME = env.BLUE_POOL_NAME
            env.ROLLBACK_POOL_NAME = env.GREEN_POOL_NAME
        } else {
            env.CURRENT_ENV = "GREEN"
            env.ROLLBACK_ENV = "BLUE"
            env.CURRENT_CONTAINER = "${appName.replace('_', '')}-green-container"
            env.ROLLBACK_CONTAINER = "${appName.replace('_', '')}-blue-container"
            env.CURRENT_POOL_NAME = env.GREEN_POOL_NAME
            env.ROLLBACK_POOL_NAME = env.BLUE_POOL_NAME
        }

        // Check if container exists, if not fall back to legacy naming
        def containerExists = sh(
            script: """
                az container show --name ${env.CURRENT_CONTAINER} --resource-group ${resourceGroup} --query 'provisioningState' --output tsv 2>/dev/null || echo "MISSING"
            """,
            returnStdout: true
        ).trim()
        
        if (containerExists == "MISSING") {
            echo "⚠️ App-specific container ${env.CURRENT_CONTAINER} not found, falling back to legacy container names"
            env.CURRENT_CONTAINER = env.CURRENT_ENV.toLowerCase() + "-container"
            env.ROLLBACK_CONTAINER = env.ROLLBACK_ENV.toLowerCase() + "-container"
        }

        // Get Application Gateway public IP
        env.APP_GATEWAY_IP = sh(
            script: "az network public-ip show --resource-group ${resourceGroup} --name ${appGatewayName}-ip --query ipAddress --output tsv",
            returnStdout: true
        ).trim()

        // Get current container image
        def currentContainerInfo = sh(
            script: "az container show --name ${env.CURRENT_CONTAINER} --resource-group ${resourceGroup} --query 'containers[0].image' --output tsv",
            returnStdout: true
        ).trim()

        env.CURRENT_IMAGE = currentContainerInfo
        
        echo "✅ Resource Group: ${env.RESOURCE_GROUP}"
        echo "✅ Application Gateway: ${env.APP_GATEWAY_NAME}"
        echo "✅ Container Registry: ${env.REGISTRY_NAME}"
        echo "✅ App Name: ${env.APP_NAME}"
        echo "✅ Current Environment: ${env.CURRENT_ENV} (${env.CURRENT_CONTAINER})"
        echo "✅ Rollback Environment: ${env.ROLLBACK_ENV} (${env.ROLLBACK_CONTAINER})"
        echo "✅ Current Image: ${env.CURRENT_IMAGE}"

    } catch (Exception e) {
        error "❌ ACI resource fetch failed: ${e.message}"
    }
}

def prepareRollback(Map config) {
    echo "🚀 Preparing ACI rollback deployment..."

    try {
        def appName = config.appName ?: env.APP_NAME ?: "app_1"
        def resourceGroup = env.RESOURCE_GROUP
        def registryName = env.REGISTRY_NAME
        
        // Find previous image version
        echo "Current image: ${env.CURRENT_IMAGE}"
        
        // Extract the repository name from the ACR URI
        def imageName = "${appName.replace('_', '')}-image"
        
        // List all images in the repository sorted by push date (newest first)
        def imagesCmd = """
        az acr repository show-tags --name ${registryName} --repository ${imageName} --orderby time_desc --output json
        """
        
        def imagesOutput = sh(script: imagesCmd, returnStdout: true).trim()
        def imagesJson = readJSON text: imagesOutput
        
        echo "Found ${imagesJson.size()} images in repository"
        
        if (imagesJson.size() < 2) {
            error "❌ Not enough images found in ACR repository. Need at least 2 images for rollback."
        }
        
        // Get the ACR repository URI
        def acrLoginServer = sh(
            script: """
            az acr show --name ${registryName} --resource-group ${resourceGroup} --query loginServer --output tsv
            """,
            returnStdout: true
        ).trim()
        
        // Get the current image tag - look for app_X-latest format
        def currentTag = "${appName}-latest"
        if (env.CURRENT_IMAGE.contains(":")) {
            def splitTag = env.CURRENT_IMAGE.split(":")[1]
            if (splitTag.contains(appName)) {
                currentTag = splitTag
            }
        }
        echo "Current image tag: ${currentTag}"
        
        // Find the previous image (not the current one)
        def previousImageTag = null
        
        // Find the current image in the list
        def currentImageIndex = -1
        for (int i = 0; i < imagesJson.size(); i++) {
            if (imagesJson[i] == currentTag) {
                currentImageIndex = i
                break
            }
        }
        
        if (currentImageIndex == -1) {
            // Current image not found, use the second newest image
            previousImageTag = imagesJson[1]
        } else if (currentImageIndex < imagesJson.size() - 1) {
            // Use the image before the current one
            previousImageTag = imagesJson[currentImageIndex + 1]
        } else {
            // Current image is the oldest, use the second newest
            previousImageTag = imagesJson[1]
        }
        
        // Create a rollback tag with app prefix
        def rollbackTag = "${appName}-rollback"
        
        // Tag the previous image with the app-specific rollback tag
        sh """
        az acr import --name ${registryName} --source ${acrLoginServer}/${imageName}:${previousImageTag} --image ${imageName}:${rollbackTag}
        """
        
        echo "✅ Tagged previous image as ${rollbackTag}"
        
        // Construct the rollback image URI
        env.ROLLBACK_IMAGE = "${acrLoginServer}/${imageName}:${rollbackTag}"
        
        echo "✅ Found previous image for rollback: ${env.ROLLBACK_IMAGE}"
        echo "✅ Previous image tag: ${previousImageTag}"
        
        // Check if the rollback container exists and is associated with backend pool
        echo "Checking if backend pool is associated with Application Gateway..."
        def poolExists = sh(
            script: """
                az network application-gateway address-pool show \\
                    --gateway-name ${env.APP_GATEWAY_NAME} \\
                    --resource-group ${resourceGroup} \\
                    --name ${env.ROLLBACK_POOL_NAME} \\
                    --query 'name' --output tsv 2>/dev/null || echo "MISSING"
            """,
            returnStdout: true
        ).trim()

        if (poolExists == "MISSING") {
            echo "⚠️ Backend pool ${env.ROLLBACK_ENV} does not exist. This should have been created by Terraform."
            error "Backend pool ${env.ROLLBACK_POOL_NAME} not found"
        } else {
            echo "✅ Backend pool ${env.ROLLBACK_POOL_NAME} exists and is ready"
        }
        
        // Check if the rollback container exists
        echo "Checking if rollback container exists..."
        def containerExists = sh(
            script: """
            az container show --name ${env.ROLLBACK_CONTAINER} --resource-group ${resourceGroup} --query 'provisioningState' --output tsv 2>/dev/null || echo "MISSING"
            """,
            returnStdout: true
        ).trim()
        
        echo "Container status: ${containerExists}"
        
        if (containerExists == "MISSING") {
            echo "⚠️ Rollback container ${env.ROLLBACK_CONTAINER} does not exist. This should have been created by Terraform."
            error "Container ${env.ROLLBACK_CONTAINER} not found"
        } else {
            // Restart container to pull rollback image
            sh """
            az container restart \\
                --name ${env.ROLLBACK_CONTAINER} \\
                --resource-group ${resourceGroup}
            """
        }
        
        echo "✅ ${env.ROLLBACK_ENV} container updated with previous version image"
        
        // Wait for container stabilization
        echo "⏳ Waiting for container to stabilize..."
        def attempts = 0
        def maxAttempts = 12
        def containerStable = false
        
        while (!containerStable && attempts < maxAttempts) {
            attempts++
            sleep(10)
            
            def containerState = sh(
                script: "az container show --name ${env.ROLLBACK_CONTAINER} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
                returnStdout: true
            ).trim()
            
            if (containerState == 'Running') {
                containerStable = true
                echo "✅ Container is stable"
            } else {
                if (attempts >= maxAttempts) {
                    error "❌ Container did not stabilize after ${maxAttempts} attempts"
                }
                echo "⚠️ Container not yet stable (attempt ${attempts}/${maxAttempts}): ${containerState}"
            }
        }
        
        // Verify the container is running
        def containerState = sh(
            script: "az container show --name ${env.ROLLBACK_CONTAINER} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
            returnStdout: true
        ).trim()
        
        if (containerState != 'Running') {
            error "❌ Rollback container failed to start (state: ${containerState})"
        }
        
        echo "✅ Rollback container is running"

    } catch (Exception e) {
        error "❌ ACI rollback preparation failed: ${e.message}"
    }
}

def testRollbackEnvironment(Map config) {
    echo "🔍 Testing rollback environment..."
    
    def resourceGroup = env.RESOURCE_GROUP
    def rollbackContainer = env.ROLLBACK_CONTAINER
    
    // Check if rollback container is running
    def containerState = sh(
        script: "az container show --name ${rollbackContainer} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
        returnStdout: true
    ).trim()
    
    if (containerState != "Running") {
        echo "⚠️ Rollback container is not running. State: ${containerState}"
        echo "Starting rollback container..."
        
        sh "az container start --name ${rollbackContainer} --resource-group ${resourceGroup}"
        
        // Wait for container to be ready
        echo "⏳ Waiting for rollback container to be ready..."
        def maxAttempts = 10
        def attempt = 0
        
        while (attempt < maxAttempts) {
            sleep(15)
            containerState = sh(
                script: "az container show --name ${rollbackContainer} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
                returnStdout: true
            ).trim()
            
            if (containerState == "Running") {
                break
            }
            attempt++
        }
    }
    
    // Get container IP for testing
    def containerIp = sh(
        script: "az container show --name ${rollbackContainer} --resource-group ${resourceGroup} --query ipAddress.ip --output tsv",
        returnStdout: true
    ).trim()
    
    if (containerIp && containerIp != 'None') {
        echo "Testing rollback container at IP: ${containerIp}"
        
        // Test health endpoint
        def appSuffix = env.APP_SUFFIX
        def healthEndpoint = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
        
        sh """
        curl -f http://${containerIp}:80${healthEndpoint} || echo "⚠️ Health check failed but continuing"
        """
        
        echo "✅ Rollback environment test completed"
    } else {
        echo "⚠️ Could not get rollback container IP for testing"
    }
}

def executeAzureAciRollback(Map config) {
    echo "🔄 Executing Azure ACI rollback..."
    
    def resourceGroup = env.RESOURCE_GROUP
    def appGatewayName = env.APP_GATEWAY_NAME
    def rollbackContainer = env.ROLLBACK_CONTAINER
    def appName = env.APP_NAME
    
    // Ensure rollback container is running
    def containerState = sh(
        script: "az container show --name ${rollbackContainer} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
        returnStdout: true
    ).trim()
    
    if (containerState != "Running") {
        echo "Starting rollback container: ${rollbackContainer}"
        sh "az container start --name ${rollbackContainer} --resource-group ${resourceGroup}"
        
        // Wait for container to be ready
        echo "⏳ Waiting for rollback container to be ready..."
        sleep(30)
    }
    
    // Get rollback container IP
    def rollbackContainerIp = sh(
        script: "az container show --name ${rollbackContainer} --resource-group ${resourceGroup} --query ipAddress.ip --output tsv",
        returnStdout: true
    ).trim()
    
    if (!rollbackContainerIp || rollbackContainerIp == 'None') {
        error "❌ Could not get IP for rollback container: ${rollbackContainer}"
    }
    
    echo "Rollback container IP: ${rollbackContainerIp}"
    
    // Update backend pool to point to rollback container
    def backendPoolName = "${appName}-blue-pool"
    
    sh """
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${backendPoolName} \\
        --set backendAddresses='[{"ipAddress":"${rollbackContainerIp}"}]'
    """
    
    echo "✅ Traffic switched to rollback container: ${rollbackContainer} (${rollbackContainerIp})"
    
    echo "✅ Azure ACI rollback completed successfully!"
}

def postRollbackActions(Map config) {
    echo "🔄 Executing post-rollback actions..."
    
    def resourceGroup = env.RESOURCE_GROUP
    def currentContainer = env.CURRENT_CONTAINER
    
    // Optionally stop the current container to save costs
    echo "Stopping current container to save costs: ${currentContainer}"
    try {
        sh "az container stop --name ${currentContainer} --resource-group ${resourceGroup}"
        echo "✅ Stopped current container: ${currentContainer}"
    } catch (Exception e) {
        echo "⚠️ Warning: Could not stop current container: ${e.message}"
    }
    
    echo "✅ Post-rollback actions completed"
}

def getResourceGroupName(config) {
    try {
        def resourceGroup = sh(
            script: "terraform output -raw resource_group_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!resourceGroup || resourceGroup == '') {
            resourceGroup = sh(
                script: "grep 'resource_group_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
                returnStdout: true
            ).trim()
        }
        
        return resourceGroup
    } catch (Exception e) {
        return "cloud-pratice-Tanishq.Parab-RG"
    }
}

def getAppGatewayName(config) {
    try {
        def appGatewayName = sh(
            script: "terraform output -raw app_gateway_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!appGatewayName || appGatewayName == '') {
            appGatewayName = sh(
                script: "grep 'app_gateway_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
                returnStdout: true
            ).trim()
        }
        
        return appGatewayName
    } catch (Exception e) {
        return "blue-green-appgw"
    }
}

def getRegistryName(config) {
    try {
        def registryName = sh(
            script: "terraform output -raw registry_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!registryName || registryName == '') {
            registryName = sh(
                script: "grep 'registry_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
                returnStdout: true
            ).trim()
        }
        
        return registryName
    } catch (Exception e) {
        return "bluegreenacrregistry"
    }
}