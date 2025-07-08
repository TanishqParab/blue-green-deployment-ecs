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

    // Check current backend pool routing
    def currentPoolConfig = sh(
        script: """az network application-gateway address-pool show \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${greenPoolName} \\
            --query 'backendAddresses' --output json 2>/dev/null || echo '[]'""",
        returnStdout: true
    ).trim()

    if (currentPoolConfig != '[]' && !currentPoolConfig.contains('"ipAddress": null')) {
        echo "⚠️ Green backend pool is currently active. Skipping deployment to Blue."
        return
    }

    echo "✅ Blue backend pool is currently active. Proceeding with deployment..."

    // Get Blue VM IP
    def blueVmIp = sh(
        script: """az vm show -d -g ${resourceGroup} -n ${blueVmTag} --query publicIps -o tsv""",
        returnStdout: true
    ).trim()

    if (!blueVmIp || blueVmIp == 'None') error "❌ No running Blue VM found!"
    echo "✅ Deploying to Blue VM: ${blueVmIp}"

    // Determine app filename and versioning
    def appBase = appName.replace('app', '')
    def timestamp = sh(script: "date +%s", returnStdout: true).trim()
    def appFileVer = "app_${appBase}_v${timestamp}.py"
    def appSymlink = "app_${appBase}.py"
    def appPath = config.appPath ?: "${config.tfWorkingDir ?: env.WORKSPACE + '/blue-green-deployment'}/modules/azure/vm/scripts"
    def appFileSource = "${appPath}/${config.appFile ?: appSymlink}"

    // Use password authentication for SSH connections
    withCredentials([usernamePassword(credentialsId: config.vmPasswordId ?: 'azure-vm-password', usernameVariable: 'VM_USER', passwordVariable: 'VM_PASS')]) {
        sh """
            # Upload new version and switch symlink using sshpass
            sshpass -p "\$VM_PASS" scp -o StrictHostKeyChecking=no ${appFileSource} \$VM_USER@${blueVmIp}:/home/\$VM_USER/${appFileVer}
            sshpass -p "\$VM_PASS" ssh -o StrictHostKeyChecking=no \$VM_USER@${blueVmIp} '
                ln -sf /home/\$VM_USER/${appFileVer} /home/\$VM_USER/${appSymlink}
            '

            # Setup script
            sshpass -p "\$VM_PASS" scp -o StrictHostKeyChecking=no ${appPath}/setup_flask_service.py \$VM_USER@${blueVmIp}:/home/\$VM_USER/
            sshpass -p "\$VM_PASS" ssh -o StrictHostKeyChecking=no \$VM_USER@${blueVmIp} '
                chmod +x /home/\$VM_USER/setup_flask_service.py &&
                sudo python3 /home/\$VM_USER/setup_flask_service.py ${appName} switch
            '
        """
    }

    env.BLUE_VM_IP = blueVmIp

    // Health Check
    echo "🔍 Monitoring health of Blue VM..."
    def healthStatus = ''
    def attempts = 0
    def maxAttempts = 30

    while (healthStatus != 'healthy' && attempts < maxAttempts) {
        sleep(time: 10, unit: 'SECONDS')
        try {
            def response = sh(
                script: "curl -s -o /dev/null -w '%{http_code}' http://${blueVmIp}/health || echo '000'",
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
        error "❌ Blue VM failed to become healthy after ${maxAttempts} attempts!"
    }

    echo "✅ Blue VM is healthy!"
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
        
        // Determine which environment to switch to (default to BLUE if not specified)
        def targetEnv = config.targetEnv?.toUpperCase() ?: "BLUE"
        def targetPoolName = (targetEnv == "BLUE") ? bluePoolName : greenPoolName
        def sourcePoolName = (targetEnv == "BLUE") ? greenPoolName : bluePoolName
        
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
        
        // Update the target backend pool with the VM IP
        sh """
        az network application-gateway address-pool update \\
            --gateway-name ${appGatewayName} \\
            --resource-group ${resourceGroup} \\
            --name ${targetPoolName} \\
            --set backendAddresses='[{"ipAddress":"${targetVmIp}"}]'
        """
        
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
        
        echo "✅✅✅ Traffic successfully routed to ${targetEnv} backend pool (${targetVmIp})!"
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
            script: "terraform output -raw app_gateway_name 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!appGatewayName || appGatewayName == '') {
            appGatewayName = sh(
                script: "grep 'app_gateway_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
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