// vars/azureAciInitialDeploymentUtils.groovy - Azure ACI initial deployment utilities

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

@NonCPS
def initialDeploymentParseJson(String jsonText) {
    return new JsonSlurper().parseText(jsonText)
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
        def blueContainerName = "${appName.replace('_', '')}${appSuffix}-blue-container"
        def blueContainer = containers.find { it.name.toLowerCase().contains(blueContainerName.toLowerCase()) }
        
        if (!blueContainer) {
            echo "⚠️ Could not find container ${blueContainerName}. Listing all containers:"
            sh "az container list --resource-group ${resourceGroup} --query '[].name' --output table"
            error "❌ Could not find blue container in resource group ${resourceGroup}"
        }
        
        def actualBlueContainerName = blueContainer.name
        echo "Found blue container: ${actualBlueContainerName}"
        
        // Update container with new image (restart to pull new image)
        sh """
        az container restart \\
            --name ${actualBlueContainerName} \\
            --resource-group ${resourceGroup}
        """
        
        echo "Restarted container with new image"
        
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
    // Use known resource group directly since terraform output is unreliable
    def resourceGroup = "cloud-pratice-Tanishq.Parab-RG"
    echo "📋 Using resource group: ${resourceGroup}"
    return resourceGroup
}

def getRegistryName(config) {
    // Use known registry name directly since terraform output is unreliable
    def registryName = "bluegreenacrregistry"
    echo "📦 Using Container Registry: ${registryName}"
    return registryName
}

def getAppGatewayName(config) {
    // Use known app gateway name directly since terraform output is unreliable
    def appGatewayName = "blue-green-appgw"
    echo "🌐 Using Application Gateway: ${appGatewayName}"
    return appGatewayName
}
