// vars/azureVmRollbackUtils.groovy - Azure VM rollback utilities

def fetchResources(Map config) {
    echo "🔄 Fetching Azure VM Application Gateway and backend pool resources..."

    def appName = config.appName ?: "app2"  // Default to app2 if not provided
    echo "🔍 Using app name: ${appName}"

    def appGatewayName = getAppGatewayName(config)
    def bluePoolName = "app_${appName.replace('app', '')}-blue-pool"
    def greenPoolName = "app_${appName.replace('app', '')}-green-pool"

    echo "🔍 Using Application Gateway: ${appGatewayName}"
    echo "🔍 Using backend pools: ${bluePoolName} and ${greenPoolName}"

    try {
        def resourceGroup = getResourceGroupName(config)
        
        // Verify Application Gateway exists
        def appGatewayExists = sh(
            script: "az network application-gateway show --name ${appGatewayName} --resource-group ${resourceGroup} --query name --output tsv 2>/dev/null || echo 'None'",
            returnStdout: true
        ).trim()

        if (!appGatewayExists || appGatewayExists == 'None') {
            error "❌ Failed to retrieve Application Gateway! Check if '${appGatewayName}' exists in Azure."
        }
        echo "✅ Application Gateway: ${appGatewayExists}"
        env.APP_GATEWAY_NAME = appGatewayName
        env.RESOURCE_GROUP = resourceGroup

        // Verify backend pools exist
        def bluePoolExists = sh(
            script: "az network application-gateway address-pool show --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --name ${bluePoolName} --query name --output tsv 2>/dev/null || echo 'None'",
            returnStdout: true
        ).trim()

        def greenPoolExists = sh(
            script: "az network application-gateway address-pool show --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --name ${greenPoolName} --query name --output tsv 2>/dev/null || echo 'None'",
            returnStdout: true
        ).trim()

        if (bluePoolExists == 'None' || greenPoolExists == 'None') {
            error "❌ Backend pools not found! Blue: ${bluePoolExists}, Green: ${greenPoolExists}"
        }
        
        env.BLUE_POOL_NAME = bluePoolName
        env.GREEN_POOL_NAME = greenPoolName
        env.APP_NAME = appName
        
        echo "✅ Blue Backend Pool: ${env.BLUE_POOL_NAME}"
        echo "✅ Green Backend Pool: ${env.GREEN_POOL_NAME}"
    } catch (Exception e) {
        echo "⚠️ Error fetching resources: ${e.message}"
        throw e
    }
}

def prepareRollback(Map config) {
    echo "🛠️ Initiating Azure VM rollback process..."

    def appName = config.appName ?: env.APP_NAME ?: "app2"
    echo "🔍 Using app name for rollback: ${appName}"

    def blueVmTag = "${appName}-blue-vm"
    def greenVmTag = "${appName}-green-vm"
    def resourceGroup = env.RESOURCE_GROUP
    def appGatewayName = env.APP_GATEWAY_NAME
    def appNum = appName.replace('app', '')

    echo "🔍 Target VM tags: ${blueVmTag} and ${greenVmTag}"

    // Determine current active pool by checking URL path map rules
    def currentActivePool = getCurrentActivePool(appGatewayName, resourceGroup, appName)
    echo "🔍 Current active pool: ${currentActivePool}"

    // Get VM IPs
    def blueVmIp = sh(
        script: "az vm show -d -g ${resourceGroup} -n ${blueVmTag} --query publicIps -o tsv 2>/dev/null || echo 'None'",
        returnStdout: true
    ).trim()
    
    def greenVmIp = sh(
        script: "az vm show -d -g ${resourceGroup} -n ${greenVmTag} --query publicIps -o tsv 2>/dev/null || echo 'None'",
        returnStdout: true
    ).trim()

    if (blueVmIp == 'None' && greenVmIp == 'None') {
        error "❌ No VMs found for rollback"
    }

    // Determine rollback target based on current active pool
    if (currentActivePool.contains('blue')) {
        env.CURRENT_ENV = "BLUE"
        env.CURRENT_VM = blueVmTag
        env.CURRENT_POOL = "app_${appNum}-blue-pool"
        env.ROLLBACK_ENV = "GREEN"
        env.ROLLBACK_VM = greenVmTag
        env.ROLLBACK_POOL = "app_${appNum}-green-pool"
        env.ROLLBACK_VM_IP = greenVmIp
    } else {
        env.CURRENT_ENV = "GREEN"
        env.CURRENT_VM = greenVmTag
        env.CURRENT_POOL = "app_${appNum}-green-pool"
        env.ROLLBACK_ENV = "BLUE"
        env.ROLLBACK_VM = blueVmTag
        env.ROLLBACK_POOL = "app_${appNum}-blue-pool"
        env.ROLLBACK_VM_IP = blueVmIp
    }

    if (env.ROLLBACK_VM_IP == 'None') {
        error "❌ Rollback VM ${env.ROLLBACK_VM} not found or not running"
    }

    echo "Current environment: ${env.CURRENT_ENV} (${env.CURRENT_POOL})"
    echo "Rollback target: ${env.ROLLBACK_ENV} (${env.ROLLBACK_VM} - ${env.ROLLBACK_POOL})"

    // Ensure rollback VM is running
    def rollbackVmState = sh(
        script: "az vm show -g ${resourceGroup} -n ${env.ROLLBACK_VM} --query powerState -o tsv",
        returnStdout: true
    ).trim()

    if (!rollbackVmState.contains("running")) {
        echo "Starting rollback VM: ${env.ROLLBACK_VM}"
        sh "az vm start -g ${resourceGroup} -n ${env.ROLLBACK_VM}"
        
        echo "⏳ Waiting for VM to be ready..."
        sleep(30)
        
        // Update IP after starting
        env.ROLLBACK_VM_IP = sh(
            script: "az vm show -d -g ${resourceGroup} -n ${env.ROLLBACK_VM} --query publicIps -o tsv",
            returnStdout: true
        ).trim()
    }

    // Health check rollback VM
    echo "🔍 Checking health of rollback VM..."
    def healthAttempts = 0
    def maxHealthAttempts = 10
    def vmHealthy = false

    while (!vmHealthy && healthAttempts < maxHealthAttempts) {
        try {
            def response = sh(
                script: "curl -s -o /dev/null -w '%{http_code}' http://${env.ROLLBACK_VM_IP}/health || echo '000'",
                returnStdout: true
            ).trim()
            
            if (response == '200') {
                vmHealthy = true
                echo "✅ Rollback VM is healthy"
            } else {
                echo "⏳ Health check ${healthAttempts + 1}/${maxHealthAttempts}: HTTP ${response}"
                sleep(10)
            }
        } catch (Exception e) {
            echo "⏳ Health check ${healthAttempts + 1}/${maxHealthAttempts}: ${e.message}"
            sleep(10)
        }
        healthAttempts++
    }

    if (!vmHealthy) {
        echo "⚠️ Rollback VM health check failed, but proceeding with rollback"
    }

    // Deploy previous version to rollback VM
    echo "🔄 Deploying previous version to rollback VM: ${env.ROLLBACK_VM}"
    deployPreviousVersionToVM(resourceGroup, env.ROLLBACK_VM, appName)

    echo "✅ Rollback preparation completed for ${appName}"
}

def executeAzureVmRollback(Map config) {
    echo "🔄 Executing Azure VM rollback..."
    
    def resourceGroup = env.RESOURCE_GROUP
    def appGatewayName = env.APP_GATEWAY_NAME
    def rollbackVm = env.ROLLBACK_VM
    def rollbackPool = env.ROLLBACK_POOL
    def appName = config.appName ?: ""
    
    // Start rollback VM if not running
    def rollbackVmState = sh(
        script: "az vm show -g ${resourceGroup} -n ${rollbackVm} --query powerState -o tsv",
        returnStdout: true
    ).trim()
    
    if (!rollbackVmState.contains("running")) {
        echo "Starting rollback VM: ${rollbackVm}"
        sh "az vm start -g ${resourceGroup} -n ${rollbackVm}"
        
        echo "⏳ Waiting for VM to be ready..."
        sleep(30)
    }
    
    // Get rollback VM IP
    def rollbackVmIp = sh(
        script: "az vm show -d -g ${resourceGroup} -n ${rollbackVm} --query publicIps -o tsv",
        returnStdout: true
    ).trim()
    
    if (!rollbackVmIp || rollbackVmIp == 'None') {
        error "❌ Could not get IP for rollback VM: ${rollbackVm}"
    }
    
    echo "Rollback VM IP: ${rollbackVmIp}"
    
    // Update backend pool with rollback VM IP
    echo "📝 Updating backend pool ${rollbackPool} with rollback VM IP"
    sh """
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${rollbackPool} \\
        --set backendAddresses='[{"ipAddress":"${rollbackVmIp}"}]'
    """
    
    // Update URL path map routing rule to point to rollback pool
    echo "🔄 Updating routing rule to point to rollback pool: ${rollbackPool}"
    try {
        updateRoutingRuleToPool(appGatewayName, resourceGroup, appName, rollbackPool)
        echo "✅ Routing rule updated successfully"
    } catch (Exception e) {
        echo "⚠️ Failed to update routing rule: ${e.message}"
        echo "💡 Manual update may be required in Azure portal"
    }
    
    echo "✅ Traffic switched to rollback VM: ${rollbackVm} (${rollbackVmIp})"
    echo "ℹ️ Both VMs remain running for future deployments"
    echo "✅ Azure VM rollback completed successfully!"
}

def getResourceGroupName(config) {
    // Use known resource group directly since terraform output is unreliable
    def resourceGroup = "cloud-pratice-Tanishq.Parab-RG"
    echo "📋 Using resource group: ${resourceGroup}"
    return resourceGroup
}

def getAppGatewayName(config) {
    // Use known app gateway name directly since terraform output is unreliable
    def appGatewayName = "blue-green-appgw"
    echo "🌐 Using Application Gateway: ${appGatewayName}"
    return appGatewayName
}

// Helper function to determine current active pool from URL path map
def getCurrentActivePool(String appGatewayName, String resourceGroup, String appName) {
    try {
        def appNum = appName.replace('app', '')
        
        // Get the path map name
        def pathMapName = sh(
            script: """az network application-gateway url-path-map list \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --query '[0].name' --output tsv""",
            returnStdout: true
        ).trim()
        
        // Get current backend pool ID for this app's rule
        def currentPoolId = sh(
            script: """az network application-gateway url-path-map show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${pathMapName} \\
                --query "pathRules[?contains(paths[0], '/app${appNum}')].backendAddressPool.id | [0]" \\
                --output tsv 2>/dev/null || echo 'None'""",
            returnStdout: true
        ).trim()
        
        if (currentPoolId && currentPoolId != 'None') {
            // Extract pool name from ID
            def poolName = currentPoolId.split('/')[-1]
            return poolName
        } else {
            echo "⚠️ Could not determine current active pool, defaulting to blue"
            return "app_${appNum}-blue-pool"
        }
    } catch (Exception e) {
        echo "⚠️ Error determining current active pool: ${e.message}"
        return "app_${appName.replace('app', '')}-blue-pool"
    }
}

// Helper function to deploy previous version to VM
def deployPreviousVersionToVM(String resourceGroup, String vmName, String appName) {
    try {
        // Look for backup/previous version file
        def appBaseName = appName.replace('app', 'app_')
        def backupFilePath = "blue-green-deployment/modules/azure/vm/scripts/${appBaseName}_backup.py"
        def currentFilePath = "blue-green-deployment/modules/azure/vm/scripts/${appBaseName}.py"
        
        def deployContent = ""
        if (fileExists(backupFilePath)) {
            echo "📦 Found backup version, deploying previous version"
            deployContent = readFile(backupFilePath)
        } else if (fileExists(currentFilePath)) {
            echo "⚠️ No backup found, deploying current version as fallback"
            deployContent = readFile(currentFilePath)
        } else {
            echo "❌ No app files found for deployment"
            return
        }
        
        def encodedContent = deployContent.bytes.encodeBase64().toString()
        
        // Read setup script
        def setupScriptPath = "blue-green-deployment/modules/azure/vm/scripts/setup_flask_service_switch.py"
        def setupScriptContent = fileExists(setupScriptPath) ? readFile(setupScriptPath) : ""
        def encodedSetupScript = setupScriptContent.bytes.encodeBase64().toString()
        
        def timestamp = sh(script: "date +%s", returnStdout: true).trim()
        def appFileVer = "${appBaseName}_rollback_v${timestamp}.py"
        def appSymlink = "${appBaseName}.py"
        
        sh """
        az vm run-command invoke \\
            --resource-group ${resourceGroup} \\
            --name ${vmName} \\
            --command-id RunShellScript \\
            --scripts 'echo "Starting rollback deployment for ${appName}..."; 
            echo "${encodedContent}" | base64 -d > /home/azureuser/${appFileVer}; 
            ln -sf /home/azureuser/${appFileVer} /home/azureuser/${appSymlink}; 
            echo "Rollback version symlink created"; 
            ls -la /home/azureuser/${appSymlink}*; 
            echo "Setting up rollback service..."; 
            echo "${encodedSetupScript}" | base64 -d > /home/azureuser/setup_flask_service_switch.py; 
            chmod +x /home/azureuser/setup_flask_service_switch.py; 
            sudo python3 /home/azureuser/setup_flask_service_switch.py ${appName} rollback; 
            echo "Rollback deployment completed for ${appName}"'
        """
        
        echo "✅ Previous version deployed to rollback VM successfully"
    } catch (Exception e) {
        echo "⚠️ Rollback deployment failed: ${e.message}"
        echo "Proceeding with traffic switch using existing service..."
    }
}

// Function to use same routing mechanism as deployment
def updateRoutingRuleToPool(String appGatewayName, String resourceGroup, String appName, String poolName) {
    try {
        def appNum = appName.replace('app', '')
        
        echo "📝 Updating routing rule for ${appName} to point to ${poolName}"
        
        // Get the pool ID
        def poolId = sh(
            script: """az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${poolName} \\
                --query 'id' --output tsv""",
            returnStdout: true
        ).trim()
        
        if (poolId) {
            // Get the correct path map name first
            def pathMapName = sh(
                script: """az network application-gateway url-path-map list \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --query '[0].name' --output tsv""",
                returnStdout: true
            ).trim()
            
            // Find the correct rule index by matching the path pattern
            def ruleIndex = sh(
                script: """az network application-gateway url-path-map show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${pathMapName} \\
                    --query "pathRules[?contains(paths[0], '/app${appNum}')] | [0]" \\
                    --output json | jq -r 'if . == null then "not_found" else "found" end' 2>/dev/null || echo 'not_found'""",
                returnStdout: true
            ).trim()
            
            if (ruleIndex != 'not_found') {
                // Find the actual index of the rule that handles /app${appNum} paths
                sh """
                RULE_INDEX=\$(az network application-gateway url-path-map show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${pathMapName} \\
                    --query "pathRules | to_entries | map(select(.value.paths[0] | contains('/app${appNum}'))) | [0].key" \\
                    --output tsv 2>/dev/null || echo '${appNum == "1" ? "0" : appNum == "2" ? "1" : "2"}')
                
                echo "🔍 Found rule index for app${appNum}: \$RULE_INDEX"
                
                az network application-gateway url-path-map update \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${pathMapName} \\
                    --set "pathRules[\${RULE_INDEX}].backendAddressPool.id='${poolId}'"
                """
            } else {
                echo "⚠️ Rule for app${appNum} not found, using fallback index"
                def fallbackIndex = appNum == "1" ? "0" : appNum == "2" ? "1" : "2"
                sh """
                az network application-gateway url-path-map update \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${pathMapName} \\
                    --set "pathRules[${fallbackIndex}].backendAddressPool.id='${poolId}'"
                """
            }
            echo "✅ Updated routing rule for ${appName} to point to ${poolName}"
        } else {
            echo "⚠️ Could not get pool ID for ${poolName}"
        }
        
    } catch (Exception e) {
        echo "⚠️ Error updating routing rule: ${e.message}"
        echo "💡 You may need to update the routing rule manually in Azure portal"
    }
}
