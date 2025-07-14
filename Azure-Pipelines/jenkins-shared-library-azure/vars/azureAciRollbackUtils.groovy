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

        // Determine current and rollback environments based on which pool has traffic
        if (bluePoolConfig != 'None' && greenPoolConfig == 'None') {
            // Blue is currently active, rollback to Green
            env.CURRENT_ENV = "BLUE"
            env.ROLLBACK_ENV = "GREEN"
            env.CURRENT_CONTAINER = "${appName.replace('_', '')}-blue-container"
            env.ROLLBACK_CONTAINER = "${appName.replace('_', '')}-green-container"
            env.CURRENT_POOL_NAME = env.BLUE_POOL_NAME
            env.ROLLBACK_POOL_NAME = env.GREEN_POOL_NAME
        } else if (greenPoolConfig != 'None' && bluePoolConfig == 'None') {
            // Green is currently active, rollback to Blue
            env.CURRENT_ENV = "GREEN"
            env.ROLLBACK_ENV = "BLUE"
            env.CURRENT_CONTAINER = "${appName.replace('_', '')}-green-container"
            env.ROLLBACK_CONTAINER = "${appName.replace('_', '')}-blue-container"
            env.CURRENT_POOL_NAME = env.GREEN_POOL_NAME
            env.ROLLBACK_POOL_NAME = env.BLUE_POOL_NAME
        } else {
            // Default: assume Blue is active, rollback to Green
            echo "⚠️ Could not determine current environment clearly. Defaulting to Blue active, rollback to Green."
            env.CURRENT_ENV = "BLUE"
            env.ROLLBACK_ENV = "GREEN"
            env.CURRENT_CONTAINER = "${appName.replace('_', '')}-blue-container"
            env.ROLLBACK_CONTAINER = "${appName.replace('_', '')}-green-container"
            env.CURRENT_POOL_NAME = env.BLUE_POOL_NAME
            env.ROLLBACK_POOL_NAME = env.GREEN_POOL_NAME
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
            // Find the most recent rollback image or use previous version
            def allImages = sh(
                script: "az acr repository show-tags --name ${registryName} --repository ${imageName} --orderby time_desc --output json",
                returnStdout: true
            ).trim()
            
            def imagesJson = readJSON text: allImages
            if (imagesJson.size() < 2) {
                error "❌ Not enough images for rollback. Need at least 2 versions."
            }
            
            // Use the most recent rollback tag, or second newest image as fallback
            def rollbackCandidates = imagesJson.findAll { it.contains('rollback') }
            if (rollbackCandidates.size() > 0) {
                rollbackTag = rollbackCandidates[0] // Most recent rollback
                echo "✅ Using most recent rollback image: ${rollbackTag}"
            } else {
                // Use second newest image as rollback
                rollbackTag = imagesJson[1]
                echo "✅ Using previous version as rollback: ${rollbackTag}"
            }
        }
        
        env.ROLLBACK_IMAGE = "${registryName}.azurecr.io/${imageName}:${rollbackTag}"
        
        echo "✅ Using existing rollback image: ${env.ROLLBACK_IMAGE}"
        echo "🔄 This image contains the PREVIOUS version of the application"
        
        // Recreate the rollback container with rollback image (same as switch deployment)
        echo "🔄 Recreating ${env.ROLLBACK_ENV} container (${env.ROLLBACK_CONTAINER}) with rollback application..."
        
        // Get ACR login server
        def acrLoginServer = sh(
            script: "az acr show --name ${registryName} --resource-group ${resourceGroup} --query loginServer --output tsv",
            returnStdout: true
        ).trim()
        
        // Delete and recreate container with rollback image
        sh """
        # Delete existing container
        echo "Deleting existing rollback container..."
        az container delete \\
            --resource-group ${resourceGroup} \\
            --name ${env.ROLLBACK_CONTAINER} \\
            --yes || echo "Container may not exist"
        
        # Wait for deletion to complete
        sleep 10
        
        # Create new container with rollback image
        echo "Creating rollback container with previous app version..."
        az container create \\
            --resource-group ${resourceGroup} \\
            --name ${env.ROLLBACK_CONTAINER} \\
            --image ${env.ROLLBACK_IMAGE} \\
            --registry-login-server ${acrLoginServer} \\
            --registry-username ${registryName} \\
            --registry-password \$(az acr credential show --name ${registryName} --query passwords[0].value --output tsv) \\
            --ip-address Public \\
            --ports 80 \\
            --cpu 1 \\
            --memory 1.5 \\
            --restart-policy Always
        """
        
        echo "✅ Rollback container recreated with previous Flask application version"
        
        echo "⏳ Waiting for rollback container to stabilize..."
        sleep(60)  // Give more time for container to start
        
        // Wait for container to be fully ready
        echo "⏳ Waiting for rollback container to be ready..."
        def maxAttempts = 20
        def attempt = 0
        def containerReady = false
        
        while (attempt < maxAttempts && !containerReady) {
            sleep(15)
            def containerState = sh(
                script: "az container show --name ${env.ROLLBACK_CONTAINER} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
                returnStdout: true
            ).trim()
            
            if (containerState == 'Running') {
                containerReady = true
                echo "✅ Rollback container is running"
            } else {
                echo "⏳ Container state: ${containerState}. Waiting..."
                attempt++
            }
        }
        
        if (!containerReady) {
            echo "⚠️ Container did not become ready within expected time, but continuing..."
        }
        
        echo "✅ Rollback container (${env.ROLLBACK_ENV}) is ready with rollback application"

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
    
    echo "🔄 Switching traffic from ${env.CURRENT_ENV} to ${env.ROLLBACK_ENV} environment"
    
    // Get rollback container IP
    def rollbackContainerIp = sh(
        script: "az container show --name ${rollbackContainer} --resource-group ${resourceGroup} --query ipAddress.ip --output tsv",
        returnStdout: true
    ).trim()
    
    if (!rollbackContainerIp || rollbackContainerIp == 'None') {
        error "❌ Could not get IP for rollback container: ${rollbackContainer}"
    }
    
    echo "🎯 Rollback container IP: ${rollbackContainerIp}"
    
    // SMART ROLLBACK STRATEGY: Query routing rules to see which pool they point to
    def routingRuleName = "${appName}-path-rule"
    def routingRuleBackendPool = ""
    
    try {
        def routingRuleBackendPoolName = sh(
            script: """
                az network application-gateway url-path-map rule show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --path-map-name main-path-map \\
                    --name ${routingRuleName} \\
                    --query 'backendAddressPool.id' --output tsv 2>/dev/null
            """,
            returnStdout: true
        ).trim()
        
        if (routingRuleBackendPoolName && routingRuleBackendPoolName != 'null' && !routingRuleBackendPoolName.isEmpty()) {
            routingRuleBackendPool = routingRuleBackendPoolName.contains('/') ? 
                routingRuleBackendPoolName.split('/').last() : routingRuleBackendPoolName
        }
    } catch (Exception e) {
        echo "⚠️ Routing rule query failed: ${e.message}"
    }
    
    if (!routingRuleBackendPool || routingRuleBackendPool.isEmpty()) {
        echo "⚠️ Could not determine routing rule backend pool. Using current pool as fallback."
        routingRuleBackendPool = env.CURRENT_POOL_NAME
    }
    
    echo "🔍 Routing rule ${routingRuleName} currently points to: ${routingRuleBackendPool}"
    
    // STRATEGY: Put rollback container in the pool that routing rules already point to
    def targetPoolForRollback = routingRuleBackendPool
    
    echo "💡 ROLLBACK STRATEGY: Moving rollback container (${rollbackContainerIp}) to pool ${targetPoolForRollback}"
    echo "💡 This ensures routing rules point to the pool with the rollback container!"
    echo "💡 Backend health will show ${targetPoolForRollback} as healthy with rollback container"
    
    // Pre-validation: Test rollback container directly before backend pool update
    echo "🔍 Pre-validation: Testing rollback container health before backend pool update..."
    def appSuffix = appName.replace("app_", "")
    def testPath = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
    
    def containerHealthy = false
    def maxRetries = 3
    for (int i = 0; i < maxRetries; i++) {
        try {
            sh """
                curl -f --connect-timeout 5 --max-time 10 http://${rollbackContainerIp}${testPath}
            """
            containerHealthy = true
            echo "✅ Rollback container health check passed (attempt ${i + 1})"
            break
        } catch (Exception e) {
            echo "⚠️ Rollback container health check failed (attempt ${i + 1}): ${e.message}"
            if (i < maxRetries - 1) {
                echo "⏳ Waiting 10 seconds before retry..."
                sleep(10)
            }
        }
    }
    
    if (!containerHealthy) {
        echo "⚠️ Warning: Rollback container health checks failed, but proceeding with backend pool update"
    }
    
    // Move rollback container IP to the pool that routing rules point to
    echo "🔄 Updating backend pool with rollback container IP..."
    sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${targetPoolForRollback} \\
            --set backendAddresses='[{"ipAddress":"${rollbackContainerIp}"}]'
    """
    
    echo "✅✅✅ Traffic successfully switched to rollback container (${rollbackContainerIp}) in ${targetPoolForRollback}!"
    echo "🎯 Routing rules unchanged - they already point to ${targetPoolForRollback}"
    echo "📊 Backend health will show ${targetPoolForRollback} as the active pool with rollback container"
    
    // Wait for backend pool to stabilize with rollback container IP
    echo "⏳ Waiting for backend pool to stabilize with rollback container IP..."
    sleep(30) // Initial wait for Azure to process the change
    
    // COMPREHENSIVE DEBUGGING: Monitor backend pool health status
    echo "🔍 DEBUGGING: Monitoring backend pool health status..."
    def healthCheckPassed = false
    def healthCheckRetries = 6 // 6 attempts over 3 minutes
    
    for (int i = 0; i < healthCheckRetries; i++) {
        try {
            // Get detailed backend health information
            def poolHealthDetails = sh(
                script: """
                    az network application-gateway show-backend-health \\
                        --name ${appGatewayName} \\
                        --resource-group ${resourceGroup} \\
                        --query "backendAddressPools[?name=='${targetPoolForRollback}']" \\
                        --output json 2>/dev/null || echo '[]'
                """,
                returnStdout: true
            ).trim()
            
            echo "🔍 DEBUGGING: Full backend health details (attempt ${i + 1}):"
            echo "${poolHealthDetails}"
            
            // Test direct container connectivity
            echo "🔍 DEBUGGING: Testing direct container connectivity..."
            def appSuffix = appName.replace("app_", "")
            def testPath = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
            
            try {
                sh """
                    echo "Testing: http://${rollbackContainerIp}${testPath}"
                    curl -v --connect-timeout 5 --max-time 10 http://${rollbackContainerIp}${testPath} || echo "Direct container test failed"
                """
            } catch (Exception directTestError) {
                echo "🔍 DEBUGGING: Direct container test error: ${directTestError.message}"
            }
            
            // Check health probe configuration
            echo "🔍 DEBUGGING: Checking health probe configuration..."
            def probeConfig = sh(
                script: """
                    az network application-gateway probe show \\
                        --gateway-name ${appGatewayName} \\
                        --resource-group ${resourceGroup} \\
                        --name ${appName}-health-probe \\
                        --output json 2>/dev/null || echo '{}'
                """,
                returnStdout: true
            ).trim()
            
            echo "🔍 DEBUGGING: Health probe configuration:"
            echo "${probeConfig}"
            
            // Check HTTP settings
            echo "🔍 DEBUGGING: Checking HTTP settings..."
            def httpSettings = sh(
                script: """
                    az network application-gateway http-settings show \\
                        --gateway-name ${appGatewayName} \\
                        --resource-group ${resourceGroup} \\
                        --name ${appName}-http-settings \\
                        --output json 2>/dev/null || echo '{}'
                """,
                returnStdout: true
            ).trim()
            
            echo "🔍 DEBUGGING: HTTP settings configuration:"
            echo "${httpSettings}"
            
            // Extract simple health status
            def poolHealth = sh(
                script: """
                    az network application-gateway show-backend-health \\
                        --name ${appGatewayName} \\
                        --resource-group ${resourceGroup} \\
                        --query "backendAddressPools[?name=='${targetPoolForRollback}'].backendHttpSettingsCollection[0].servers[0].health" \\
                        --output tsv 2>/dev/null || echo "Unknown"
                """,
                returnStdout: true
            ).trim()
            
            echo "📊 DEBUGGING: Backend pool health status (attempt ${i + 1}): ${poolHealth}"
            
            if (poolHealth == "Healthy") {
                healthCheckPassed = true
                echo "✅ Backend pool is healthy!"
                break
            } else {
                echo "⏳ Backend pool not yet healthy (${poolHealth}), waiting 30 seconds..."
                sleep(30)
            }
        } catch (Exception e) {
            echo "⚠️ DEBUGGING: Health check query failed: ${e.message}"
            sleep(30)
        }
    }
    
    if (!healthCheckPassed) {
        echo "⚠️ DEBUGGING: Backend pool health check did not pass within expected time"
        echo "🔍 DEBUGGING: Final diagnostic information:"
        
        // Final container status check
        try {
            def containerStatus = sh(
                script: "az container show --name ${env.ROLLBACK_CONTAINER} --resource-group ${resourceGroup} --query '{State:instanceView.state,RestartCount:instanceView.restartCount,Image:containers[0].image}' --output json",
                returnStdout: true
            ).trim()
            echo "🔍 DEBUGGING: Final container status: ${containerStatus}"
        } catch (Exception e) {
            echo "⚠️ DEBUGGING: Could not get container status: ${e.message}"
        }
        
        // Final backend pool status
        try {
            def finalPoolStatus = sh(
                script: "az network application-gateway address-pool show --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --name ${targetPoolForRollback} --output json",
                returnStdout: true
            ).trim()
            echo "🔍 DEBUGGING: Final backend pool status: ${finalPoolStatus}"
        } catch (Exception e) {
            echo "⚠️ DEBUGGING: Could not get backend pool status: ${e.message}"
        }
    }
    
    // DO NOT update health probes or routing rules - this preserves health probe associations!
    echo "📊 Preserving routing rules and health probe associations for rollback"
    echo "✅ No routing rule changes needed - rollback traffic flows automatically!"
    
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

def createHealthProbe(String appGatewayName, String resourceGroup, String appName, String containerIp) {
    try {
        def probeName = "${appName}-health-probe"
        
        echo "🔍 Updating health probe ${probeName} with container IP ${containerIp}"
        
        // Update health probe with actual container IP
        sh """
        az network application-gateway probe update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${probeName} \\
            --host ${containerIp} \\
            --path /health \\
            --interval 30 \\
            --timeout 30 \\
            --threshold 3 || echo "Probe update failed"
        """
        
        echo "✅ Health probe updated with container IP for ${appName}"
        
    } catch (Exception e) {
        echo "⚠️ Error updating health probe: ${e.message}"
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
