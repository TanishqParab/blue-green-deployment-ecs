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

    echo "📝 Registering VMs to the correct backend pools..."
    sh """
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${bluePoolName} \\
        --set backendAddresses='[{"ipAddress":"${blueVmIp}"}]'
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${greenPoolName} \\
        --set backendAddresses='[{"ipAddress":"${greenVmIp}"}]'
    """

    echo "✅ Azure VMs successfully registered to correct backend pools!"
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
    def bluePoolName = env.BLUE_POOL_NAME
    def greenPoolName = env.GREEN_POOL_NAME

    echo "❌ Clearing old backend pool registrations..."
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

    echo "✅ Registering VMs to the correct backend pools..."
    sh """
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${bluePoolName} \\
        --set backendAddresses='[{"ipAddress":"${blueVmIp}"}]'
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${greenPoolName} \\
        --set backendAddresses='[{"ipAddress":"${greenVmIp}"}]'
    """

    echo "✅ VMs successfully registered to correct backend pools!"
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
    
    // Determine which environment is currently active and deploy to the inactive one
    def blueIsActive = bluePoolConfig != '[]' && bluePoolConfig != 'null' && !bluePoolConfig.contains('"ipAddress":null') && !bluePoolConfig.contains('[]')
    def greenIsActive = greenPoolConfig != '[]' && greenPoolConfig != 'null' && !greenPoolConfig.contains('"ipAddress":null') && !greenPoolConfig.contains('[]')
    
    echo "🔍 Deploy Debug - Blue is active: ${blueIsActive}"
    echo "🔍 Deploy Debug - Green is active: ${greenIsActive}"
    
    def targetEnv, targetVmTag, targetVmIp
    
    if (blueIsActive && !greenIsActive) {
        // Blue is active, deploy to Green
        targetEnv = "GREEN"
        targetVmTag = "${appName}-green-vm"
        echo "🔵 Blue is currently active, deploying to Green environment"
    } else if (greenIsActive && !blueIsActive) {
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

    // Health Check
    echo "🔍 Monitoring health of ${targetEnv} VM..."
    def healthStatus = ''
    def attempts = 0
    def maxAttempts = 15  // Reduced attempts since Azure Run Command deployment is faster

    while (healthStatus != 'healthy' && attempts < maxAttempts) {
        sleep(time: 10, unit: 'SECONDS')
        try {
            def response = sh(
                script: "curl -s -o /dev/null -w '%{http_code}' http://${targetVmIp}/health || echo '000'",
                returnStdout: true
            ).trim()
            
            if (response == '200') {
                healthStatus = 'healthy'
            } else {
                healthStatus = 'unhealthy'
            }
        } catch (Exception e) {
            healthStatus = 'unhealthy'
        }
        attempts++
        echo "Health status check attempt ${attempts}: ${healthStatus}"
    }

    if (healthStatus != 'healthy') {
        echo "⚠️ ${targetEnv} VM health check failed after ${maxAttempts} attempts"
        echo "📝 Manual verification: Check http://${targetVmIp}/${appName}"
    } else {
        echo "✅ ${targetEnv} VM is healthy and ready for traffic!"
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
        
        // Determine which environment is currently active
        def blueIsActive = bluePoolConfig != '[]' && bluePoolConfig != 'null' && !bluePoolConfig.contains('"ipAddress":null') && !bluePoolConfig.contains('[]')
        def greenIsActive = greenPoolConfig != '[]' && greenPoolConfig != 'null' && !greenPoolConfig.contains('"ipAddress":null') && !greenPoolConfig.contains('[]')
        
        echo "🔍 Debug - Blue is active: ${blueIsActive}"
        echo "🔍 Debug - Green is active: ${greenIsActive}"
        
        def currentEnv, targetEnv, targetPoolName, sourcePoolName
        
        if (blueIsActive && !greenIsActive) {
            currentEnv = "BLUE"
            targetEnv = "GREEN"
            targetPoolName = greenPoolName
            sourcePoolName = bluePoolName
        } else if (greenIsActive && !blueIsActive) {
            currentEnv = "GREEN"
            targetEnv = "BLUE"
            targetPoolName = bluePoolName
            sourcePoolName = greenPoolName
        } else {
            // Default: assume Blue is active, switch to Green
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
        def healthCheckPassed = false
        def healthAttempts = 0
        def maxHealthAttempts = 20
        
        while (!healthCheckPassed && healthAttempts < maxHealthAttempts) {
            try {
                def healthResponse = sh(
                    script: "curl -s -o /dev/null -w '%{http_code}' http://${targetVmIp}/${appName} || echo '000'",
                    returnStdout: true
                ).trim()
                
                if (healthResponse == '200') {
                    healthCheckPassed = true
                    echo "✅ ${targetEnv} VM health check passed (${healthResponse})"
                } else {
                    echo "⚠️ ${targetEnv} VM health check failed (${healthResponse}), attempt ${healthAttempts + 1}/${maxHealthAttempts}"
                }
            } catch (Exception e) {
                echo "⚠️ ${targetEnv} VM health check error: ${e.message}"
            }
            
            if (!healthCheckPassed) {
                sleep(15)
                healthAttempts++
            }
        }
        
        if (!healthCheckPassed) {
            echo "❌ ${targetEnv} VM failed health checks after ${maxHealthAttempts} attempts"
            echo "⚠️ Proceeding with traffic switch anyway - manual verification required"
        }
        
        // Update the target backend pool with the VM IP
        sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${targetPoolName} \\
            --set backendAddresses='[{"ipAddress":"${targetVmIp}"}]'
        """
        
        // Wait for Application Gateway to register the new backend
        echo "⏳ Waiting for Application Gateway to register new backend..."
        sleep(30)
        
        // Clear the source backend pool
        sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${sourcePoolName} \\
            --set backendAddresses='[]'
        """
        
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
        
        // Final health check via Application Gateway
        echo "🔍 Final health check via Application Gateway..."
        sleep(10)
        try {
            def gatewayIp = sh(
                script: "az network public-ip show --resource-group ${resourceGroup} --name ${appGatewayName}-ip --query ipAddress --output tsv 2>/dev/null || echo 'unknown'",
                returnStdout: true
            ).trim()
            
            if (gatewayIp != 'unknown') {
                def gatewayResponse = sh(
                    script: "curl -s -o /dev/null -w '%{http_code}' http://${gatewayIp}/${appName} || echo '000'",
                    returnStdout: true
                ).trim()
                
                if (gatewayResponse == '200') {
                    echo "✅ Application Gateway health check passed (${gatewayResponse})"
                    echo "🌐 Application accessible at: http://${gatewayIp}/${appName}"
                } else {
                    echo "⚠️ Application Gateway health check failed (${gatewayResponse})"
                    echo "📝 Manual verification needed: http://${gatewayIp}/${appName}"
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

// SSH-related functions removed - using Azure Run Command only

def deployViaAzureRunCommand(String vmName, String resourceGroup, String appName, String appPath, String appFileSource, String appFileVer, String appSymlink) {
    echo "🚀 Deploying via Azure Run Command to ${vmName}"
    
    try {
        // Read the app file content and encode it as base64 to avoid shell escaping issues
        def appContent = readFile(appFileSource)
        def encodedContent = appContent.bytes.encodeBase64().toString()
        
        // Execute the deployment via Azure Run Command with inline script
        sh """
        az vm run-command invoke \\
            --resource-group ${resourceGroup} \\
            --name ${vmName} \\
            --command-id RunShellScript \\
            --scripts 'echo "Starting deployment for ${appName}..."; echo "${encodedContent}" | base64 -d > /home/azureuser/${appFileVer}; ln -sf /home/azureuser/${appFileVer} /home/azureuser/${appSymlink}; echo "Symlink created successfully"; ls -la /home/azureuser/${appSymlink}*; echo "Downloading setup script..."; curl -s -o /home/azureuser/setup_flask_service_switch.py https://raw.githubusercontent.com/TanishqParab/blue-green-deployment-ecs/main/Multi-App/blue-green-deployment/modules/azure/vm/scripts/setup_flask_service_switch.py; chmod +x /home/azureuser/setup_flask_service_switch.py; sudo python3 /home/azureuser/setup_flask_service_switch.py ${appName} switch; echo "Deployment completed successfully for ${appName}"'
        """
        
        echo "✅ Deployment via Azure Run Command completed successfully"
        
    } catch (Exception e) {
        echo "❌ Deployment via Azure Run Command failed: ${e.message}"
        echo "⚠️ Manual deployment may be required"
    }
}
