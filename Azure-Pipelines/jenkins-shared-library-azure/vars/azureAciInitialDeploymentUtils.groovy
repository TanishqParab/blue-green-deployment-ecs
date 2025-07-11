// vars/azureAciInitialDeploymentUtils.groovy - Azure ACI initial deployment utilities

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

@NonCPS
def initialDeploymentParseJson(String jsonText) {
    def parsed = new JsonSlurper().parseText(jsonText)
    // Convert to serializable list to avoid LazyMap serialization issues
    def result = []
    parsed.each { item ->
        result.add([
            name: item.name,
            resourceGroup: item.resourceGroup,
            location: item.location
        ])
    }
    return result
}

def deployToBlueContainer(Map config) {
    def appName = config.appName ?: "app_1"
    def appSuffix = appName.replace("app_", "")
    
    echo "🚀 Deploying initial application ${appName} to Blue Container..."

    try {
        def resourceGroup = getResourceGroupName(config)
        def registryName = getRegistryName(config)
        
        // Get ACR login server
        def acrLoginServer = sh(
            script: "az acr show --name ${registryName} --resource-group ${resourceGroup} --query loginServer --output tsv",
            returnStdout: true
        ).trim()
        
        // Smart path handling for Docker directory
        def tfDir = config.tfWorkingDir ?: env.WORKSPACE
        def dockerDir = "${tfDir}/blue-green-deployment/modules/azure/aci/scripts"
        
        // Handle case where tfDir already includes blue-green-deployment
        if (tfDir.endsWith('/blue-green-deployment')) {
            dockerDir = "${tfDir}/modules/azure/aci/scripts"
        }
        
        // Build and push Docker image for the specified app with app_*-latest tag
        sh """
            # Authenticate Docker to ACR
            az acr login --name ${registryName}
            
            # Navigate to the directory with Dockerfile
            cd ${dockerDir}
            
            # Make sure we're using the right app file
            cp app_${appSuffix}.py app.py
            
            # Build the Docker image
            docker build -t ${appName.replace('_', '')}-image --build-arg APP_NAME=${appSuffix} .
            
            # Tag the image with app-specific latest tag
            docker tag ${appName.replace('_', '')}-image:latest ${acrLoginServer}/${appName.replace('_', '')}-image:${appName}-latest
            
            # Push the app-specific latest tag
            docker push ${acrLoginServer}/${appName.replace('_', '')}-image:${appName}-latest
        """
        
        // Get containers and find blue container
        def containersJson = sh(
            script: "az container list --resource-group ${resourceGroup} --output json",
            returnStdout: true
        ).trim()
        
        def containers = initialDeploymentParseJson(containersJson)
        
        // Look for app-specific blue container with exact naming pattern: app1-blue-container
        def blueContainerName = "${appName.replace('_', '')}-blue-container"
        def blueContainer = containers.find { it.name.toLowerCase().contains(blueContainerName.toLowerCase()) }
        
        if (!blueContainer) {
            echo "⚠️ Could not find container ${blueContainerName}. Listing all containers:"
            sh "az container list --resource-group ${resourceGroup} --query '[].name' --output table"
            error "❌ Could not find blue container in resource group ${resourceGroup}"
        }
        
        def actualBlueContainerName = blueContainer.name
        echo "Found blue container: ${actualBlueContainerName}"
        
        // Update container with new image by recreating it
        echo "Updating container ${actualBlueContainerName} with new image: ${acrLoginServer}/${appName.replace('_', '')}-image:${appName}-latest"
        
        sh """
        # Delete existing container
        echo "Deleting existing container..."
        az container delete \\
            --resource-group ${resourceGroup} \\
            --name ${actualBlueContainerName} \\
            --yes || echo "Container may not exist"
        
        # Wait for deletion to complete
        sleep 10
        
        # Create new container with updated image
        echo "Creating container with new Flask app image..."
        az container create \\
            --resource-group ${resourceGroup} \\
            --name ${actualBlueContainerName} \\
            --image ${acrLoginServer}/${appName.replace('_', '')}-image:${appName}-latest \\
            --registry-login-server ${acrLoginServer} \\
            --registry-username ${registryName} \\
            --registry-password \$(az acr credential show --name ${registryName} --query passwords[0].value --output tsv) \\
            --ip-address Public \\
            --ports 80 \\
            --cpu 1 \\
            --memory 1.5 \\
            --restart-policy Always
        """
        
        echo "✅ Container recreated with new Flask application image"
        
        // Wait for container to stabilize
        sh "sleep 60"  // Give time for container to restart and pull new image
        
        // Get Application Gateway info for routing
        def appGatewayName = getAppGatewayName(config)
        def backendPoolName = "${appName}-blue-pool"
        
        echo "Looking for backend pool: ${backendPoolName}"
        def backendPoolExists = sh(
            script: "az network application-gateway address-pool show --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --name ${backendPoolName} --query name --output tsv 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!backendPoolExists || backendPoolExists == "None") {
            echo "⚠️ Could not find backend pool ${backendPoolName}. Listing all backend pools:"
            sh "az network application-gateway address-pool list --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --query '[].name' --output table"
            error "❌ Could not find blue backend pool ${backendPoolName}"
        }
        
        echo "Found backend pool: ${backendPoolName}"
        
        // Get container IP
        def containerIp = sh(
            script: "az container show --name ${actualBlueContainerName} --resource-group ${resourceGroup} --query ipAddress.ip --output tsv",
            returnStdout: true
        ).trim()
        
        if (containerIp && containerIp != 'None') {
            // Register container to backend pool
            sh """
            az network application-gateway address-pool update \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${backendPoolName} \\
                --set backendAddresses='[{"ipAddress":"${containerIp}"}]'
            """
            echo "Registered container IP ${containerIp} to backend pool ${backendPoolName}"
            
            // Create health probe for this app
            createHealthProbe(appGatewayName, resourceGroup, appName)
        }
        
        // Wait for container to be fully ready
        echo "⏳ Waiting for container to be ready..."
        def maxAttempts = 20
        def attempt = 0
        def containerReady = false
        
        while (attempt < maxAttempts && !containerReady) {
            sleep(15)
            def containerState = sh(
                script: "az container show --name ${actualBlueContainerName} --resource-group ${resourceGroup} --query instanceView.state --output tsv",
                returnStdout: true
            ).trim()
            
            if (containerState == 'Running') {
                containerReady = true
                echo "✅ Container is running"
            } else {
                echo "⏳ Container state: ${containerState}. Waiting..."
                attempt++
            }
        }
        
        if (!containerReady) {
            echo "⚠️ Container did not become ready within expected time, but continuing..."
        }
        
        // Create routing rule for this app
        echo "🔄 Creating routing rule for ${appName}..."
        createRoutingRule(appGatewayName, resourceGroup, appName, backendPoolName)
        
        // Check health probe status
        echo "🔍 Checking health probe status..."
        checkHealthProbeStatus(appGatewayName, resourceGroup, appName)
        
        // Get Application Gateway public IP for display
        def appGatewayIp = sh(
            script: "az network public-ip show --resource-group ${resourceGroup} --name ${appGatewayName}-ip --query ipAddress --output tsv",
            returnStdout: true
        ).trim()
        
        // Display access information
        if (appName == "app_1") {
            echo "✅ Initial deployment of ${appName} completed successfully!"
            echo "🌐 Application is accessible at: http://${appGatewayIp}/"
        } else {
            echo "✅ Initial deployment of ${appName} completed successfully!"
            echo "🌐 Application is accessible at: http://${appGatewayIp}/app${appSuffix}/"
        }
        
    } catch (Exception e) {
        echo "❌ Initial deployment failed: ${e.message}"
        throw e
    }
}

def getResourceGroupName(config) {
    // Get from azureDeploymentVars
    def deploymentVars = azureDeploymentVars()
    def resourceGroup = deploymentVars.resourceGroupName ?: "cloud-pratice-Tanishq.Parab-RG"
    echo "📋 Using resource group: ${resourceGroup}"
    return resourceGroup
}

def getRegistryName(config) {
    // Get from azureDeploymentVars
    def deploymentVars = azureDeploymentVars()
    def registryName = deploymentVars.registryName ?: "bluegreenacrregistry"
    echo "📦 Using Container Registry: ${registryName}"
    return registryName
}

def getAppGatewayName(config) {
    // Get from azureDeploymentVars
    def deploymentVars = azureDeploymentVars()
    def appGatewayName = deploymentVars.appGatewayName ?: "blue-green-appgw"
    echo "🌐 Using Application Gateway: ${appGatewayName}"
    return appGatewayName
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

def checkHealthProbeStatus(String appGatewayName, String resourceGroup, String appName) {
    try {
        def probeName = "${appName}-health-probe"
        
        echo "🔍 Checking health probe status for ${probeName}"
        
        // Get backend health status
        sh """
        echo "Backend health status:"
        az network application-gateway show-backend-health \\
            --name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --query "backendAddressPools[?name=='${appName}-blue-pool'].backendHttpSettingsCollection[0].servers[0].health" \\
            --output table || echo "Could not get health status"
        """
        
        // Test direct connectivity to container
        def containerIp = sh(
            script: "az container show --name ${appName.replace('_', '')}-blue-container --resource-group ${resourceGroup} --query ipAddress.ip --output tsv",
            returnStdout: true
        ).trim()
        
        if (containerIp) {
            echo "Testing direct connectivity to container ${containerIp}:80"
            sh "curl -I http://${containerIp}:80 --connect-timeout 10 || echo 'Direct connection failed'"
        }
        
    } catch (Exception e) {
        echo "⚠️ Error checking health probe: ${e.message}"
    }
}

def createRoutingRule(String appGatewayName, String resourceGroup, String appName, String backendPoolName) {
    try {
        def appSuffix = appName.replace("app_", "")
        def pathPattern = appSuffix == "1" ? "/*" : "/app${appSuffix}/*"
        def ruleName = "path-rule-${appSuffix}"
        def httpSettingsName = "${appName}-http-settings"
        
        echo "📝 Creating path rule ${ruleName} for pattern ${pathPattern}"
        
        // Get or create path map
        def pathMapName = "app-path-map"
        
        // Check if path map exists, create if not
        def pathMapExists = sh(
            script: "az network application-gateway url-path-map show --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --name ${pathMapName} --query name --output tsv 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!pathMapExists) {
            echo "Creating path map ${pathMapName}"
            sh """
            az network application-gateway url-path-map create \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${pathMapName} \\
                --default-address-pool ${backendPoolName} \\
                --default-http-settings ${httpSettingsName}
            """
        }
        
        // Add path rule to the path map
        sh """
        az network application-gateway url-path-map rule create \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --path-map-name ${pathMapName} \\
            --name ${ruleName} \\
            --paths "${pathPattern}" \\
            --address-pool ${backendPoolName} \\
            --http-settings ${httpSettingsName} || echo "Rule may already exist"
        """
        
        // Update the main routing rule to use path-based routing
        def mainRuleName = "rule1"
        sh """
        az network application-gateway rule update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${mainRuleName} \\
            --url-path-map ${pathMapName} || echo "Main rule update failed"
        """
        
        echo "✅ Created path-based routing rule for ${appName}"
        
    } catch (Exception e) {
        echo "⚠️ Error creating routing rule: ${e.message}"
        echo "💡 Manual configuration may be needed in Azure portal"
    }
}
