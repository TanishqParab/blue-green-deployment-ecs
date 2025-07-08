// vars/azureVmRollbackUtils.groovy - Azure VM rollback utilities

def fetchResources(Map config) {
    echo "🔄 Fetching Azure VM Application Gateway and backend pool resources..."

    def appName = config.appName
    if (!appName) {
        error "❌ APP_NAME not provided. Rollback requires a specific application name like 'app1'."
    }

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

    def appName = config.appName
    if (!appName) {
        error "❌ APP_NAME not provided. Rollback requires a specific application name like 'app1'."
    }

    def blueVmTag = "${appName}-blue-vm"
    def greenVmTag = "${appName}-green-vm"
    def resourceGroup = env.RESOURCE_GROUP
    def appGatewayName = env.APP_GATEWAY_NAME

    echo "🔍 Target VM tags: ${blueVmTag} and ${greenVmTag}"

    // Determine current backend pool routing to find which VM to rollback to
    def currentPoolConfig = sh(
        script: """
            az network application-gateway address-pool show \\
                --gateway-name ${appGatewayName} \\
                --resource-group ${resourceGroup} \\
                --name ${env.BLUE_POOL_NAME} \\
                --query 'backendAddresses[0].ipAddress' --output tsv 2>/dev/null || echo 'None'
        """,
        returnStdout: true
    ).trim()

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

    // Determine rollback target (switch to the VM not currently receiving traffic)
    if (currentPoolConfig == blueVmIp) {
        // Currently on Blue, rollback to Green
        env.CURRENT_ENV = "BLUE"
        env.ROLLBACK_ENV = "GREEN"
        env.ROLLBACK_VM = greenVmTag
        env.ROLLBACK_VM_IP = greenVmIp
    } else {
        // Currently on Green or unknown, rollback to Blue
        env.CURRENT_ENV = "GREEN"
        env.ROLLBACK_ENV = "BLUE"
        env.ROLLBACK_VM = blueVmTag
        env.ROLLBACK_VM_IP = blueVmIp
    }

    if (env.ROLLBACK_VM_IP == 'None') {
        error "❌ Rollback VM ${env.ROLLBACK_VM} not found or not running"
    }

    echo "Current environment: ${env.CURRENT_ENV}"
    echo "Rollback target: ${env.ROLLBACK_ENV} (${env.ROLLBACK_VM} - ${env.ROLLBACK_VM_IP})"

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

    // Execute rollback script on the VM
    echo "🛠️ Executing rollback script on ${env.ROLLBACK_VM} (${env.ROLLBACK_VM_IP})"
    
    // Use password authentication for SSH connections
    withCredentials([usernamePassword(credentialsId: config.vmPasswordId ?: 'azure-vm-password', usernameVariable: 'VM_USER', passwordVariable: 'VM_PASS')]) {
        def tfDir = config.tfWorkingDir ?: env.WORKSPACE + '/blue-green-deployment'
        def localScript = "${tfDir}/modules/azure/vm/scripts/setup_flask_service.py"
        
        if (!fileExists(localScript)) {
            echo "⚠️ Local rollback script not found: ${localScript}. Skipping script execution."
        } else {
            echo "📁 Found rollback script: ${localScript}"
            echo "📤 Uploading and executing rollback script..."
            
            sh """
                sshpass -p "\$VM_PASS" scp -o StrictHostKeyChecking=no ${localScript} \$VM_USER@${env.ROLLBACK_VM_IP}:/home/\$VM_USER/setup_flask_service.py
                sshpass -p "\$VM_PASS" ssh -o StrictHostKeyChecking=no \$VM_USER@${env.ROLLBACK_VM_IP} '
                    chmod +x /home/\$VM_USER/setup_flask_service.py &&
                    sudo python3 /home/\$VM_USER/setup_flask_service.py ${appName} rollback
                '
            """
        }
    }

    echo "✅ Rollback preparation completed for ${appName}"
}

def executeAzureVmRollback(Map config) {
    echo "🔄 Executing Azure VM rollback..."
    
    def resourceGroup = env.RESOURCE_GROUP
    def appGatewayName = env.APP_GATEWAY_NAME
    def rollbackVm = env.ROLLBACK_VM
    def appName = config.appName ?: ""
    
    // Start rollback VM if not running
    def rollbackVmState = sh(
        script: "az vm show -g ${resourceGroup} -n ${rollbackVm} --query powerState -o tsv",
        returnStdout: true
    ).trim()
    
    if (!rollbackVmState.contains("running")) {
        echo "Starting rollback VM: ${rollbackVm}"
        sh "az vm start -g ${resourceGroup} -n ${rollbackVm}"
        
        // Wait for VM to be ready
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
    
    // Update backend pool to point to rollback VM
    def backendPoolName = appName ? "app_${appName.replace('app', '')}-blue-pool" : "app_1-blue-pool"
    
    sh """
    az network application-gateway address-pool update \\
        --gateway-name ${appGatewayName} \\
        --resource-group ${resourceGroup} \\
        --name ${backendPoolName} \\
        --set backendAddresses='[{"ipAddress":"${rollbackVmIp}"}]'
    """
    
    echo "✅ Traffic switched to rollback VM: ${rollbackVm} (${rollbackVmIp})"
    
    // Optionally stop the current VM to save costs
    def currentVm = env.CURRENT_VM
    echo "Stopping current VM to save costs: ${currentVm}"
    sh "az vm deallocate -g ${resourceGroup} -n ${currentVm}"
    
    echo "✅ Azure VM rollback completed successfully!"
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