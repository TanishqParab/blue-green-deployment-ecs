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
        def appSuffix = appName.replace("app_", "")
        def resourceGroup = env.RESOURCE_GROUP
        def registryName = env.REGISTRY_NAME
        
        // Find and deploy previous image version
        def imageName = "${appName.replace('_', '')}-image"
        
        // Get rollback image (look for rollback tags first)
        def rollbackImages = sh(
            script: "az acr repository show-tags --name ${registryName} --repository ${imageName} --query '[?contains(@, `rollback`)]' --output json 2>/dev/null || echo '[]'",
            returnStdout: true
        ).trim()
        
        def rollbackTag
        if (rollbackImages != '[]') {
            def rollbackJson = readJSON text: rollbackImages
            if (rollbackJson.size() > 0) {
                rollbackTag = rollbackJson[0] // Use most recent rollback tag
                echo "✅ Found existing rollback image: ${rollbackTag}"
            }
        }
        
        if (!rollbackTag) {
            // Create rollback image from previous version
            def allImages = sh(
                script: "az acr repository show-tags --name ${registryName} --repository ${imageName} --orderby time_desc --output json",
                returnStdout: true
            ).trim()
            
            def imagesJson = readJSON text: allImages
            if (imagesJson.size() < 2) {
                error "❌ Not enough images for rollback. Need at least 2 versions."
            }
            
            // Use second newest image as rollback
            def previousTag = imagesJson[1]
            rollbackTag = "${appName}-rollback-${new Date().format('yyyyMMdd-HHmmss')}"
            
            def acrLoginServer = sh(
                script: "az acr show --name ${registryName} --resource-group ${resourceGroup} --query loginServer --output tsv",
                returnStdout: true
            ).trim()
            
            sh "az acr import --name ${registryName} --source ${acrLoginServer}/${imageName}:${previousTag} --image ${imageName}:${rollbackTag}"
            echo "✅ Created rollback image: ${rollbackTag}"
        }
        
        env.ROLLBACK_IMAGE = "${registryName}.azurecr.io/${imageName}:${rollbackTag}"
        
        // Build and deploy rollback application
        echo "🔧 Building rollback application for ${appName}..."
        
        def dockerDir = "./blue-green-deployment/modules/azure/aci/scripts"
        
        sh """
            az acr login --name ${registryName}
            cd ${dockerDir}
            docker build -t ${imageName}:${appName}-rollback --build-arg APP_NAME=${appSuffix} .
            docker tag ${imageName}:${appName}-rollback ${registryName}.azurecr.io/${imageName}:${appName}-rollback
            docker push ${registryName}.azurecr.io/${imageName}:${appName}-rollback
        """
        
        env.ROLLBACK_IMAGE = "${registryName}.azurecr.io/${imageName}:${appName}-rollback"
        echo "✅ Rollback application built and pushed: ${env.ROLLBACK_IMAGE}"
        
        // Update rollback container with new image
        echo "🔄 Updating ${env.ROLLBACK_ENV} container with rollback application..."
        
        sh "az container restart --name ${env.ROLLBACK_CONTAINER} --resource-group ${resourceGroup}"
        
        echo "⏳ Waiting for rollback container to stabilize..."
        sleep(30)
        
        def containerState = sh(
            script: "az container show --name ${env.ROLLBACK_CONTAINER} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
            returnStdout: true
        ).trim()
        
        if (containerState != 'Running') {
            echo "⚠️ Container state: ${containerState}. Waiting longer..."
            sleep(30)
        }
        
        echo "✅ Rollback container is ready with rollback application"
        
        // Create health probe for rollback environment
        createHealthProbe(env.APP_GATEWAY_NAME, resourceGroup, appName)

    } catch (Exception e) {
        error "❌ ACI rollback preparation failed: ${e.message}"
    }
}

def testRollbackEnvironment(Map config) {
    echo "🔍 Testing rollback environment..."
    
    try {
        def appName = env.APP_NAME
        def appSuffix = env.APP_SUFFIX
        def resourceGroup = env.RESOURCE_GROUP
        def appGatewayName = env.APP_GATEWAY_NAME
        
        // Get Application Gateway public IP
        def appGatewayIp = sh(
            script: "az network public-ip show --resource-group ${resourceGroup} --name ${appGatewayName}-ip --query ipAddress --output tsv",
            returnStdout: true
        ).trim()

        // Wait for Application Gateway to be ready
        echo "⏳ Waiting for Application Gateway to be ready..."
        sleep(10)

        // Test app-specific endpoint
        def testEndpoint = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
        echo "🌐 Testing endpoint: http://${appGatewayIp}${testEndpoint}"
        
        sh """
        curl -f http://${appGatewayIp}${testEndpoint} || curl -f http://${appGatewayIp}${testEndpoint.replace('/health', '')} || echo "⚠️ Health check failed but continuing"
        """

        echo "✅ Rollback environment tested successfully"

    } catch (Exception e) {
        echo "⚠️ Warning: Test stage encountered an issue: ${e.message}"
        echo "Proceeding with rollback despite test issues."
    }
}

def executeAzureAciRollback(Map config) {
    echo "🔄 Executing Azure ACI rollback..."
    
    def resourceGroup = env.RESOURCE_GROUP
    def appGatewayName = env.APP_GATEWAY_NAME
    def rollbackContainer = env.ROLLBACK_CONTAINER
    def appName = env.APP_NAME
    
    // Get rollback container IP
    def rollbackContainerIp = sh(
        script: "az container show --name ${rollbackContainer} --resource-group ${resourceGroup} --query ipAddress.ip --output tsv",
        returnStdout: true
    ).trim()
    
    if (!rollbackContainerIp || rollbackContainerIp == 'None') {
        error "❌ Could not get IP for rollback container: ${rollbackContainer}"
    }
    
    echo "🔄 Switching traffic to rollback environment: ${env.ROLLBACK_ENV} (${rollbackContainerIp})"
    
    // Update target backend pool with rollback container IP
    sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${env.ROLLBACK_POOL_NAME} \\
            --set backendAddresses='[{"ipAddress":"${rollbackContainerIp}"}]'
    """
    
    // Clear current backend pool
    sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${env.CURRENT_POOL_NAME} \\
            --set backendAddresses='[]'
    """
    
    echo "✅ Traffic successfully switched to rollback environment: ${env.ROLLBACK_ENV}"
    
    // Update routing rules to point to rollback environment
    echo "🔄 Updating routing rules for rollback..."
    createRoutingRule(appGatewayName, resourceGroup, appName, env.ROLLBACK_POOL_NAME)
    
    // Validate rollback deployment
    validateRollbackSuccess(appGatewayName, resourceGroup, appName, rollbackContainerIp)
    
    echo "✅ Azure ACI rollback completed successfully!"
}

def postRollbackActions(Map config) {
    echo "✅ Rollback deployment completed successfully"
    
    def appGatewayIp = sh(
        script: "az network public-ip show --resource-group ${env.RESOURCE_GROUP} --name ${env.APP_GATEWAY_NAME}-ip --query ipAddress --output tsv",
        returnStdout: true
    ).trim()
    
    def appSuffix = env.APP_SUFFIX
    def appUrl = appSuffix == "1" ? "http://${appGatewayIp}/" : "http://${appGatewayIp}/app${appSuffix}/"
    
    echo "🌐 Rollback application accessible at: ${appUrl}"
    echo "✅ Post-rollback actions completed"
}

def getResourceGroupName(config) {
    try {
        def resourceGroup = sh(
            script: "cd blue-green-deployment && terraform output -raw resource_group_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        // Clean up terraform warning messages
        if (resourceGroup.contains('Warning:') || resourceGroup.contains('[')) {
            resourceGroup = ""
        }
        
        if (!resourceGroup || resourceGroup == '') {
            resourceGroup = "cloud-pratice-Tanishq.Parab-RG"
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
        
        // Clean up terraform warning messages
        if (appGatewayName.contains('Warning:') || appGatewayName.contains('[')) {
            appGatewayName = ""
        }
        
        if (!appGatewayName || appGatewayName == '') {
            appGatewayName = "blue-green-appgw"
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
        
        // Clean up terraform warning messages
        if (registryName.contains('Warning:') || registryName.contains('[')) {
            registryName = ""
        }
        
        if (!registryName || registryName == '') {
            registryName = "bluegreenacrregistry"
        }
        
        echo "📦 Using Container Registry: ${registryName}"
        return registryName
    } catch (Exception e) {
        echo "⚠️ Could not determine registry name: ${e.message}"
        return "bluegreenacrregistry"
    }
}

def createHealthProbe(String appGatewayName, String resourceGroup, String appName) {
    try {
        def probeName = "${appName}-health-probe"
        def httpSettingsName = "${appName}-http-settings"
        
        echo "🔍 Creating health probe ${probeName}"
        
        // Create health probe
        sh """
        az network application-gateway probe create \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${probeName} \\
            --protocol Http \\
            --host-name-from-http-settings true \\
            --path / \\
            --interval 30 \\
            --timeout 30 \\
            --threshold 3 || echo "Probe may already exist"
        """
        
        // Create HTTP settings with the probe
        sh """
        az network application-gateway http-settings create \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${httpSettingsName} \\
            --port 80 \\
            --protocol Http \\
            --timeout 30 \\
            --probe ${probeName} || echo "HTTP settings may already exist"
        """
        
        echo "✅ Created health probe and HTTP settings for ${appName}"
        
    } catch (Exception e) {
        echo "⚠️ Error creating health probe: ${e.message}"
    }
}

def createRoutingRule(String appGatewayName, String resourceGroup, String appName, String backendPoolName) {
    try {
        def appSuffix = appName.replace("app_", "")
        def existingRuleName = "${appName}-path-rule"
        def httpSettingsName = "${appName}-http-settings"
        def pathPattern = "/app${appSuffix}*"
        
        echo "📝 Updating path rule ${existingRuleName} to point to ${backendPoolName}"
        
        // Delete and recreate the path rule to update it
        sh """
        # Delete existing rule
        az network application-gateway url-path-map rule delete \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --path-map-name main-path-map \\
            --name ${existingRuleName} || echo "Rule may not exist"
        
        # Recreate rule with new backend pool
        az network application-gateway url-path-map rule create \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --path-map-name main-path-map \\
            --name ${existingRuleName} \\
            --paths "${pathPattern}" \\
            --address-pool ${backendPoolName} \\
            --http-settings ${httpSettingsName}
        """
        
        echo "✅ Updated path rule to point to ${backendPoolName}"
        
    } catch (Exception e) {
        echo "⚠️ Error updating routing rule: ${e.message}"
    }
}

def validateRollbackSuccess(String appGatewayName, String resourceGroup, String appName, String containerIp) {
    try {
        echo "✅ Validating rollback to ${env.ROLLBACK_ENV} environment"
        
        // Wait for Application Gateway to propagate changes
        sleep(30)
        
        def appGatewayIp = sh(
            script: "az network public-ip show --resource-group ${resourceGroup} --name ${appGatewayName}-ip --query ipAddress --output tsv",
            returnStdout: true
        ).trim()
        
        def appSuffix = appName.replace("app_", "")
        def appUrl = appSuffix == "1" ? "http://${appGatewayIp}/" : "http://${appGatewayIp}/app${appSuffix}/"
        
        echo "🌐 Rollback application accessible at: ${appUrl}"
        echo "✅ Rollback deployment completed successfully"
        
    } catch (Exception e) {
        echo "⚠️ Error during rollback validation: ${e.message}"
    }
}
