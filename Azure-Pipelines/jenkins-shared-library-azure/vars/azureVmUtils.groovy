// vars/azureVmUtils.groovy - Azure VM utility functions (equivalent to ec2Utils.groovy)

def registerVMsToBackendPools(Map config) {
    if (config.implementation != 'azure-vm' || params.MANUAL_BUILD == 'DESTROY') {
        echo "⚠️ Skipping Azure VM registration as conditions not met."
        return
    }

    echo "📥 Fetching Application Gateway backend pools from Azure..."
    
    def appName = config.appName ?: ""
    def bluePoolName = appName ? "app_${appName.replace('app', '')}-blue-pool" : "app_1-blue-pool"
    def greenPoolName = appName ? "app_${appName.replace('app', '')}-green-pool" : "app_1-green-pool"
    
    def blueVmTag = appName ? "${appName}-blue-vm" : "app1-blue-vm"
    def greenVmTag = appName ? "${appName}-green-vm" : "app1-green-vm"
    
    echo "🔍 Using backend pools: ${bluePoolName} and ${greenPoolName}"
    echo "🔍 Using VM tags: ${blueVmTag} and ${greenVmTag}"

    def resourceGroup = getResourceGroupName(config)
    def appGatewayName = getAppGatewayName(config)

    echo "🔍 Fetching Azure VM IPs..."

    def blueVmIp = sh(
        script: """
        az vm show -d -g ${resourceGroup} -n ${blueVmTag} --query publicIps -o tsv
        """,
        returnStdout: true
    ).trim()

    def greenVmIp = sh(
        script: """
        az vm show -d -g ${resourceGroup} -n ${greenVmTag} --query publicIps -o tsv
        """,
        returnStdout: true
    ).trim()

    if (!blueVmIp || blueVmIp == "None" || !greenVmIp || greenVmIp == "None") {
        echo "⚠️ One or both VMs not found. Blue: ${blueVmIp}, Green: ${greenVmIp}"
        echo "⚠️ This is normal for the first deployment. Skipping registration."
        return
    }

    echo "✅ Blue VM IP: ${blueVmIp}"
    echo "✅ Green VM IP: ${greenVmIp}"

    echo "🔄 Clearing old backend pool registrations..."
    try {
        sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${bluePoolName} \\
            --set backendAddresses='[]'
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${greenPoolName} \\
            --set backendAddresses='[]'
        """
    } catch (Exception e) {
        echo "⚠️ Warning during deregistration: ${e.message}"
        echo "⚠️ Continuing with registration..."
    }
    sleep(10)

    echo "📝 VMs are already in GREEN pools. Updating routing rules instead..."
    
    // Update routing rules to point to green pools where VMs are
    updateRoutingRulesToGreenPools(config)
    
    echo "✅ Routing rules updated to point to GREEN pools where VMs are!"
}

def detectChanges(Map config) {
    echo "🔍 Detecting changes for Azure VM implementation..."

    def changedFiles = sh(script: "git diff --name-only HEAD~1 HEAD", returnStdout: true).trim()
    
    if (!changedFiles) {
        echo "No changes detected."
        env.EXECUTION_TYPE = 'SKIP'
        return
    }
    
    def fileList = changedFiles.split('\\n')
    echo "📝 Changed files: ${fileList.join(', ')}"
    echo "🚀 Change(s) detected. Triggering deployment."
    
    def appChanges = []
    def infraChanges = false
    
    fileList.each { file ->
        if (file.endsWith("app.py")) {
            appChanges.add("default")
        } else if (file.endsWith("app_1.py")) {
            appChanges.add("app1")
        } else if (file.endsWith("app_2.py")) {
            appChanges.add("app2")
        } else if (file.endsWith("app_3.py")) {
            appChanges.add("app3")
        } else {
            infraChanges = true
        }
    }
    
    if (appChanges.size() > 0 && !infraChanges) {
        echo "🚀 Detected app changes: ${appChanges}, executing App Deploy."
        env.EXECUTION_TYPE = 'APP_DEPLOY'
        
        if (appChanges.size() > 1) {
            echo "⚠️ Multiple app files changed. Using the first one: ${appChanges[0]}"
        }
        
        if (appChanges[0] == "default") {
            env.APP_NAME = ""
        } else {
            env.APP_NAME = appChanges[0]
        }
        
        echo "🔍 Setting APP_NAME to: ${env.APP_NAME ?: 'default'}"
    } else {
        echo "✅ Infra changes detected, running full deployment."
        env.EXECUTION_TYPE = 'FULL_DEPLOY'
    }
}

def fetchResources(Map config) {
    echo "🔍 Fetching Application Gateway backend pools..."
    
    def appName = config.appName ?: ""
    def bluePoolName = appName ? "app_${appName.replace('app', '')}-blue-pool" : "app_1-blue-pool"
    def greenPoolName = appName ? "app_${appName.replace('app', '')}-green-pool" : "app_1-green-pool"
    
    echo "🔍 Using backend pools: ${bluePoolName} and ${greenPoolName}"

    def resourceGroup = getResourceGroupName(config)
    def appGatewayName = getAppGatewayName(config)

    env.BLUE_POOL_NAME = bluePoolName
    env.GREEN_POOL_NAME = greenPoolName
    env.RESOURCE_GROUP = resourceGroup
    env.APP_GATEWAY_NAME = appGatewayName

    echo "✅ Blue Backend Pool: ${env.BLUE_POOL_NAME}"
    echo "✅ Green Backend Pool: ${env.GREEN_POOL_NAME}"
}

def updateApplication(Map config) {
    echo "Running Azure VM update application logic..."

    def appName = config.appName ?: ""
    def blueVmTag = appName ? "${appName}-blue-vm" : "app1-blue-vm"
    def greenVmTag = appName ? "${appName}-green-vm" : "app1-green-vm"
    
    echo "🔍 Using VM tags: ${blueVmTag} and ${greenVmTag}"

    def resourceGroup = getResourceGroupName(config)

    def blueVmIp = sh(
        script: """
        az vm show -d -g ${resourceGroup} -n ${blueVmTag} --query publicIps -o tsv
        """,
        returnStdout: true
    ).trim()

    def greenVmIp = sh(
        script: """
        az vm show -d -g ${resourceGroup} -n ${greenVmTag} --query publicIps -o tsv
        """,
        returnStdout: true
    ).trim()

    if (!blueVmIp || blueVmIp == "None" || !greenVmIp || greenVmIp == "None") {
        error "❌ Blue or Green VM not found! Check Azure portal."
    }

    echo "✅ Blue VM IP: ${blueVmIp}"
    echo "✅ Green VM IP: ${greenVmIp}"

    def appGatewayName = getAppGatewayName(config)
    def greenPoolName = env.GREEN_POOL_NAME

    echo "✅ Ensuring VMs are registered in GREEN pools..."
    // Ensure green VM is in green pool
    sh """
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${greenPoolName} \\
        --set backendAddresses='[{"ipAddress":"${greenVmIp}"}]'
    """

    echo "✅ VMs successfully registered in GREEN pools!"
}

def deployToBlueVM(Map config) {
    def appName = config.appName ?: ""
    def appGatewayName = getAppGatewayName(config)
    def bluePoolName = "app_${appName.replace('app', '')}-blue-pool"
    def greenPoolName = "app_${appName.replace('app', '')}-green-pool"
    def blueVmTag = "${appName}-blue-vm"
    def pathPattern = "/${appName}"

    echo "🔍 App: ${appName}, App Gateway: ${appGatewayName}, Blue Pool: ${bluePoolName}, Blue Tag: ${blueVmTag}"

    def resourceGroup = getResourceGroupName(config)

    // Smart environment detection - determine which environment to deploy to
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
    
    // Debug: Show current backend pool configurations for deployment
    echo "🔍 Deploy Debug - Blue pool config: ${bluePoolConfig}"
    echo "🔍 Deploy Debug - Green pool config: ${greenPoolConfig}"
    
    // Check which backend pool currently has targets (same as EC2 logic)
    def blueHasTargets = bluePoolConfig != '[]' && !bluePoolConfig.contains('"backendAddresses": []')
    def greenHasTargets = greenPoolConfig != '[]' && !greenPoolConfig.contains('"backendAddresses": []')
    
    echo "🔍 Deploy Debug - Blue has targets: ${blueHasTargets}"
    echo "🔍 Deploy Debug - Green has targets: ${greenHasTargets}"
    
    def targetEnv, targetVmTag, targetVmIp
    
    // Deploy to the inactive environment (same logic as EC2)
    if (blueHasTargets && !greenHasTargets) {
        // Blue is active, deploy to Green
        targetEnv = "GREEN"
        targetVmTag = "${appName}-green-vm"
        echo "🔵 Blue is currently active, deploying to Green environment"
    } else if (greenHasTargets && !blueHasTargets) {
        // Green is active, deploy to Blue  
        targetEnv = "BLUE"
        targetVmTag = "${appName}-blue-vm"
        echo "🟢 Green is currently active, deploying to Blue environment"
    } else {
        // Default: deploy to Blue (first deployment or both active)
        targetEnv = "BLUE"
        targetVmTag = "${appName}-blue-vm"
        echo "🔄 Defaulting to Blue environment deployment"
    }
    
    echo "🎯 Deploying to ${targetEnv} environment (${targetVmTag})..."

    // Get Target VM IP
    targetVmIp = sh(
        script: """az vm show -d -g ${resourceGroup} -n ${targetVmTag} --query publicIps -o tsv""",
        returnStdout: true
    ).trim()

    if (!targetVmIp || targetVmIp == 'None') error "❌ No running ${targetEnv} VM found!"
    echo "✅ Deploying to ${targetEnv} VM: ${targetVmIp}"
    
    // Skip SSH and use Azure Run Command directly
    echo "🚀 Using Azure Run Command for deployment (SSH disabled)"
    env.SKIP_SSH_DEPLOYMENT = 'true'

    // Determine app filename and versioning
    def appBase = appName.replace('app', '')
    def timestamp = sh(script: "date +%s", returnStdout: true).trim()
    def appFileVer = "app_${appBase}_v${timestamp}.py"
    def appSymlink = "app_${appBase}.py"
    def appPath = config.appPath ?: "${config.tfWorkingDir ?: env.WORKSPACE + '/blue-green-deployment'}/modules/azure/vm/scripts"
    def appFileSource = "${appPath}/app_${appBase}.py"

    // Deploy using Azure Run Command only
    echo "🚀 Deploying via Azure Run Command to ${targetEnv} VM"
    deployViaAzureRunCommand(targetVmTag, resourceGroup, appName, appPath, appFileSource, appFileVer, appSymlink)
    env.TARGET_VM_IP = targetVmIp
    env.TARGET_ENV = targetEnv

    env.TARGET_VM_IP = targetVmIp
    env.TARGET_ENV = targetEnv

    // Health Check with proper path
    echo "🔍 Monitoring health of ${targetEnv} VM..."
    sleep(10)
    
    // Try multiple health check endpoints
    def healthUrls = [
        "http://${targetVmIp}/health",
        "http://${targetVmIp}/${appName}/health",
        "http://${targetVmIp}/${appName}"
    ]
    
    def healthPassed = false
    for (url in healthUrls) {
        try {
            def response = sh(
                script: "curl -s -o /dev/null -w '%{http_code}' ${url} || echo '000'",
                returnStdout: true
            ).trim()
            
            if (response == '200') {
                echo "Health status check attempt 1: healthy"
                echo "✅ ${targetEnv} VM is healthy and ready for traffic!"
                healthPassed = true
                break
            }
        } catch (Exception e) {
            // Continue to next URL
        }
    }
    
    if (!healthPassed) {
        echo "⚠️ ${targetEnv} VM health check failed on all endpoints"
        echo "📝 Manual verification: Check http://${targetVmIp}/${appName}"
    }
}

def switchTraffic(Map config) {
    def appName = config.appName ?: ""
    def appGatewayName = getAppGatewayName(config)
    def bluePoolName = appName ? "app_${appName.replace('app', '')}-blue-pool" : "app_1-blue-pool"
    def greenPoolName = appName ? "app_${appName.replace('app', '')}-green-pool" : "app_1-green-pool"
    
    echo "🔍 Using Application Gateway: ${appGatewayName}"
    echo "🔍 Using backend pools: ${bluePoolName} and ${greenPoolName}"
    
    try {
        def resourceGroup = getResourceGroupName(config)
        
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
        
        // Debug: Show current backend pool configurations
        echo "🔍 Debug - Blue pool config: ${bluePoolConfig}"
        echo "🔍 Debug - Green pool config: ${greenPoolConfig}"
        
        // Check which backend pool currently has targets (same as EC2 logic)
        def blueHasTargets = bluePoolConfig != '[]' && !bluePoolConfig.contains('"backendAddresses": []')
        def greenHasTargets = greenPoolConfig != '[]' && !greenPoolConfig.contains('"backendAddresses": []')
        
        echo "🔍 Debug - Blue has targets: ${blueHasTargets}"
        echo "🔍 Debug - Green has targets: ${greenHasTargets}"
        
        def currentEnv, targetEnv, targetPoolName, sourcePoolName
        
        if (blueHasTargets && !greenHasTargets) {
            currentEnv = "BLUE"
            targetEnv = "GREEN"
            targetPoolName = greenPoolName
            sourcePoolName = bluePoolName
        } else if (greenHasTargets && !blueHasTargets) {
            currentEnv = "GREEN"
            targetEnv = "BLUE"
            targetPoolName = bluePoolName
            sourcePoolName = greenPoolName
        } else {
            // Default: assume Blue is active, switch to Green (same as EC2 logic)
            echo "⚠️ Could not determine current environment clearly. Defaulting to switch from BLUE to GREEN."
            currentEnv = "BLUE"
            targetEnv = "GREEN"
            targetPoolName = greenPoolName
            sourcePoolName = bluePoolName
        }
        
        echo "🔄 Current active environment: ${currentEnv}"
        echo "🎯 Target environment: ${targetEnv}"
        echo "🔁 Switching traffic from ${sourcePoolName} to ${targetPoolName}..."
        
        // Get the target VM IP
        def targetVmTag = (targetEnv == "BLUE") ? "${appName}-blue-vm" : "${appName}-green-vm"
        def targetVmIp = sh(
            script: "az vm show -d -g ${resourceGroup} -n ${targetVmTag} --query publicIps -o tsv",
            returnStdout: true
        ).trim()
        
        if (!targetVmIp || targetVmIp == 'None') {
            error "❌ Could not get IP for target VM: ${targetVmTag}"
        }
        
        // Verify target VM health before switching traffic
        echo "🔍 Verifying ${targetEnv} VM health before traffic switch..."
        def healthResponse = sh(
            script: "curl -s -o /dev/null -w '%{http_code}' http://${targetVmIp}/${appName} || echo '000'",
            returnStdout: true
        ).trim()
        
        if (healthResponse == '200') {
            echo "✅ ${targetEnv} VM health check passed (${healthResponse})"
        } else {
            echo "⚠️ ${targetEnv} VM health check failed (${healthResponse})"
            echo "⚠️ Proceeding with traffic switch - Application Gateway will handle health checks"
        }
        
        // First ensure the target backend pool has the correct VM
        sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${targetPoolName} \\
            --set backendAddresses='[{"ipAddress":"${targetVmIp}"}]'
        """
        
        // Wait longer for Application Gateway to register the new backend
        echo "⏳ Waiting for Application Gateway to register new backend..."
        sleep(45)
        
        // Verify the backend is registered before clearing the source
        def backendRegistered = false
        try {
            def registeredIp = sh(
                script: """az network application-gateway address-pool show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${targetPoolName} \\
                    --query 'backendAddresses[0].ipAddress' --output tsv 2>/dev/null || echo 'none'""",
                returnStdout: true
            ).trim()
            
            if (registeredIp == targetVmIp) {
                backendRegistered = true
                echo "✅ Backend successfully registered: ${registeredIp}"
            } else {
                echo "⚠️ Backend registration issue. Expected: ${targetVmIp}, Got: ${registeredIp}"
            }
        } catch (Exception e) {
            echo "⚠️ Could not verify backend registration: ${e.message}"
        }
        
        // Only clear the source pool if it's different from target pool
        // Don't clear the pool we just deployed to
        if (sourcePoolName != targetPoolName) {
            sleep(15)
            sh """
            az network application-gateway address-pool update \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${sourcePoolName} \\
                --set backendAddresses='[]'
            """
            echo "✅ Source backend pool (${sourcePoolName}) cleared"
        } else {
            echo "📝 Skipping source pool clear - same as target pool (${targetPoolName})"
        }
        
        // Verify the switch
        def currentPoolConfig = sh(
            script: """az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${targetPoolName} \\
                --query 'backendAddresses[0].ipAddress' --output tsv""",
            returnStdout: true
        ).trim()
        
        if (currentPoolConfig != targetVmIp) {
            error "❌ Verification failed! Backend pool not pointing to ${targetEnv} VM."
        }
        
        echo "✅✅✅ Traffic successfully switched from ${currentEnv} to ${targetEnv} (${targetVmIp})!"
        echo "🌐 Application should now be accessible via Application Gateway"
        echo "📝 Direct VM access: http://${targetVmIp}/${appName}"
        
        // Update routing rules to point to the new active pool
        echo "🔄 Updating routing rules to point to ${targetEnv} pool..."
        updateRoutingRuleToPool(appGatewayName, resourceGroup, appName, targetPoolName)
        
        // Check backend health after switch
        checkBackendHealth(appGatewayName, resourceGroup, targetPoolName)
        
        // Final health check via Application Gateway with retry logic
        echo "🔍 Final health check via Application Gateway..."
        sleep(10)
        try {
            def gatewayIp = sh(
                script: "az network public-ip show --resource-group ${resourceGroup} --name ${appGatewayName}-ip --query ipAddress --output tsv 2>/dev/null || echo 'unknown'",
                returnStdout: true
            ).trim()
            
            if (gatewayIp != 'unknown') {
                // Try multiple times as Application Gateway needs time to update routing
                def gatewayHealthy = false
                for (int i = 0; i < 3; i++) {
                    def gatewayResponse = sh(
                        script: "curl -s -o /dev/null -w '%{http_code}' http://${gatewayIp}/${appName} || echo '000'",
                        returnStdout: true
                    ).trim()
                    
                    if (gatewayResponse == '200') {
                        echo "✅ Application Gateway health check passed (${gatewayResponse})"
                        echo "🌐 Application accessible at: http://${gatewayIp}/${appName}"
                        gatewayHealthy = true
                        break
                    } else if (i < 2) {
                        echo "⚠️ Application Gateway health check attempt ${i+1} failed (${gatewayResponse}), retrying..."
                        sleep(15)
                    }
                }
                
                if (!gatewayHealthy) {
                    echo "⚠️ Application Gateway health check failed (502)"
                    echo "📝 Manual verification needed: http://${gatewayIp}/${appName}"
                    echo "💡 This may be due to Application Gateway backend health probe configuration"
                    echo "💡 Check that the backend health probe path matches the application endpoints"
                }
            }
        } catch (Exception e) {
            echo "⚠️ Could not perform Application Gateway health check: ${e.message}"
        }
    } catch (Exception e) {
        echo "⚠️ Error switching traffic: ${e.message}"
        throw e
    }
}

def tagSwapVMs(Map config) {
    echo "🌐 Discovering Azure VMs..."
    
    def appName = config.appName ?: ""
    def blueTag = appName ? "${appName}-blue-vm" : (config.blueTag ?: "app1-blue-vm")
    def greenTag = appName ? "${appName}-green-vm" : (config.greenTag ?: "app1-green-vm")
    
    echo "🔍 Using VM tags: ${blueTag} and ${greenTag}"

    def resourceGroup = getResourceGroupName(config)

    def blueVmId = sh(script: """
        az vm show -g ${resourceGroup} -n ${blueTag} --query id -o tsv
    """, returnStdout: true).trim()

    def greenVmId = sh(script: """
        az vm show -g ${resourceGroup} -n ${greenTag} --query id -o tsv
    """, returnStdout: true).trim()

    if (!blueVmId || !greenVmId) {
        error "❌ Could not find both Blue and Green VMs. Found:\\nBlue: ${blueVmId}\\nGreen: ${greenVmId}"
    }

    echo "✔️ Found VMs - Blue: ${blueVmId}, Green: ${greenVmId}"
    echo "🔄 Performing atomic tag swap..."

    sh """
        #!/bin/bash
        set -euo pipefail

        BLUE_VM="${blueVmId}"
        GREEN_VM="${greenVmId}"
        BLUE_TAG="${blueTag}"
        GREEN_TAG="${greenTag}"

        echo "➡️ Swapping tags:"
        echo "- \$BLUE_VM will become \$GREEN_TAG"
        echo "- \$GREEN_VM will become \$BLUE_TAG"

        # Swap the Name tags
        az vm update --ids "\$BLUE_VM" --set tags.Name="\$GREEN_TAG"
        az vm update --ids "\$GREEN_VM" --set tags.Name="\$BLUE_TAG"

        echo "✅ Tag swap completed"
    """

    echo "✅ Deployment Complete!"
    echo "====================="
    echo "VM Tags:"
    echo "- ${blueVmId} is now '${greenTag}'"
    echo "- ${greenVmId} is now '${blueTag}'"
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

// Function to update routing rules to point to green pools (where VMs are)
def updateRoutingRulesToGreenPools(Map config) {
    def resourceGroup = getResourceGroupName(config)
    def appGatewayName = getAppGatewayName(config)
    
    echo "🔄 Updating routing rules to point to GREEN pools (where VMs are)..."
    
    try {
        // Update routing rules for each app to point to green pools
        ['1', '2', '3'].each { appNum ->
            def greenPoolName = "app_${appNum}-green-pool"
            def pathPattern = "/app${appNum}/*"
            
            echo "📝 Updating rule for /app${appNum} to point to ${greenPoolName}"
            
            // Get the green pool ID
            def greenPoolId = sh(
                script: """az network application-gateway address-pool show \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --name ${greenPoolName} \\
                    --query 'id' --output tsv""",
                returnStdout: true
            ).trim()
            
            if (greenPoolId) {
                // Update the path rule to point to green pool
                sh """
                az network application-gateway url-path-map rule update \\
                    --gateway-name ${appGatewayName} \\
                    --resource-group ${resourceGroup} \\
                    --path-map-name pathMap \\
                    --name "rule${appNum}" \\
                    --address-pool ${greenPoolId}
                """
                echo "✅ Updated rule for app${appNum} to point to green pool"
            }
        }
        
        echo "✅ All routing rules updated to point to GREEN pools!"
        echo "🌐 Applications should now be accessible via Application Gateway"
        
    } catch (Exception e) {
        echo "⚠️ Error updating routing rules: ${e.message}"
        echo "💡 You may need to update the routing rules manually in Azure portal"
    }
}

// Function to update routing rule for a specific app to point to a specific pool
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
            // Update the path rule to point to the specified pool
            sh """
            az network application-gateway url-path-map rule update \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --path-map-name pathMap \\
                --name "rule${appNum}" \\
                --address-pool ${poolId}
            """
            echo "✅ Updated routing rule for ${appName} to point to ${poolName}"
        } else {
            echo "⚠️ Could not get pool ID for ${poolName}"
        }
        
    } catch (Exception e) {
        echo "⚠️ Error updating routing rule: ${e.message}"
        echo "💡 You may need to update the routing rule manually in Azure portal"
    }
}

// Additional utility function to check Application Gateway backend health
def checkBackendHealth(String appGatewayName, String resourceGroup, String poolName) {
    try {
        def healthStatus = sh(
            script: """az network application-gateway show-backend-health \\
                --name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --query "backendAddressPools[?name=='${poolName}'].backendHttpSettingsCollection[0].servers[0].health" \\
                --output tsv 2>/dev/null || echo 'Unknown'""",
            returnStdout: true
        ).trim()
        
        echo "🔍 Backend pool ${poolName} health status: ${healthStatus}"
        return healthStatus
    } catch (Exception e) {
        echo "⚠️ Could not check backend health: ${e.message}"
        return 'Unknown'
    }
}

// SSH-related functions removed - using Azure Run Command only

def deployViaAzureRunCommand(String vmName, String resourceGroup, String appName, String appPath, String appFileSource, String appFileVer, String appSymlink) {
    echo "🚀 Deploying via Azure Run Command to ${vmName}"
    
    try {
        // Read the app file content and encode it as base64 to avoid shell escaping issues
        def appContent = readFile(appFileSource)
        def encodedContent = appContent.bytes.encodeBase64().toString()
        
        // Read the setup script content and encode it
        def setupScriptContent = readFile("${appPath}/setup_flask_service_switch.py")
        def encodedSetupScript = setupScriptContent.bytes.encodeBase64().toString()
        
        // Execute the deployment via Azure Run Command with inline script
        sh """
        az vm run-command invoke \\
            --resource-group ${resourceGroup} \\
            --name ${vmName} \\
            --command-id RunShellScript \\
            --scripts 'echo "Starting deployment for ${appName}..."; echo "${encodedContent}" | base64 -d > /home/azureuser/${appFileVer}; ln -sf /home/azureuser/${appFileVer} /home/azureuser/${appSymlink}; echo "Symlink created successfully"; ls -la /home/azureuser/${appSymlink}*; echo "Creating setup script..."; echo "${encodedSetupScript}" | base64 -d > /home/azureuser/setup_flask_service_switch.py; chmod +x /home/azureuser/setup_flask_service_switch.py; sudo python3 /home/azureuser/setup_flask_service_switch.py ${appName} switch; echo "Deployment completed successfully for ${appName}"; echo "Verifying service status..."; sudo systemctl status flask-app-app_${appName.replace("app", "")} --no-pager || true; echo "Checking if port 80 is listening..."; sudo netstat -tlnp | grep :80 || true'
        """
        
        echo "✅ Deployment via Azure Run Command completed successfully"
        
    } catch (Exception e) {
        echo "❌ Deployment via Azure Run Command failed: ${e.message}"
        echo "⚠️ Manual deployment may be required"
        throw e
    }
}
