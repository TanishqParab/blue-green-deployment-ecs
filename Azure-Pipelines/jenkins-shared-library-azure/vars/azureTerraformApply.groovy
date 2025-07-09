// vars/azureTerraformApply.groovy - Azure Terraform apply

def call(config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        echo "Running Azure Terraform apply"

        dir("${config.tfWorkingDir}") {
            sh "terraform apply -auto-approve tfplan"
            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
        }

        if (config.implementation == 'azure-vm') {
            echo "Waiting for Azure VMs to start and initialize..."
            sleep(90)  // Allow time for cloud-init scripts to complete

            echo "Checking VM states..."
            def resourceGroup = getResourceGroupName(config)
            sh """
            az vm list -g ${resourceGroup} --query '[].{Name:name, PowerState:powerState}' --output table
            """

            def validApps = ["app1", "app2", "app3"]
            def appName = config.appName?.trim()?.toLowerCase()

            if (!appName) {
                echo "No appName provided, deploying all apps: ${validApps.join(', ')}"
                validApps.each { app ->
                    deployApp(app, config)
                }
            } else if (!validApps.contains(appName)) {
                error "Invalid appName '${appName}'. Must be one of: ${validApps.join(', ')}"
            } else {
                echo "Deploying single app: ${appName}"
                deployApp(appName, config)
            }
        } else if (config.implementation == 'azure-aci') {
            echo "Waiting for Azure Container Instances to stabilize..."
            sleep(60)

            def resourceGroup = getResourceGroupName(config)

            sh """
            az container list -g ${resourceGroup} --query '[].{Name:name, State:instanceView.state}' --output table
            """

            def appGatewayIp = sh(
                script: "az network public-ip show --resource-group ${resourceGroup} --name ${getAppGatewayName(config)}-ip --query ipAddress --output tsv",
                returnStdout: true
            ).trim()

            echo "Application is accessible at: http://${appGatewayIp}"

            sh """
            sleep 30
            curl -f http://${appGatewayIp}/health || echo "Health check failed but continuing"
            """
        }
    }
}

def deployApp(String appName, Map config) {
    echo "Starting deployment for app: ${appName}"

    def blueVmTag = "${appName}-blue-vm"
    def greenVmTag = "${appName}-green-vm"

    def blueVmIp = getVmPublicIp(blueVmTag, config)
    def greenVmIp = getVmPublicIp(greenVmTag, config)

    if (!blueVmIp || blueVmIp == "None") {
        error "Blue VM IP not found or invalid for ${appName}!"
    }
    if (!greenVmIp || greenVmIp == "None") {
        error "Green VM IP not found or invalid for ${appName}!"
    }

    echo "Blue VM IP for ${appName}: ${blueVmIp}"
    echo "Green VM IP for ${appName}: ${greenVmIp}"

    // Health check both VMs
    [blueVmIp, greenVmIp].each { ip ->
        echo "Checking health for VM ${ip} (app: ${appName})"
        try {
            sh "curl -m 10 -f http://${ip}/health"
            echo "VM ${ip} for ${appName} is healthy."
        } catch (Exception e) {
            echo "⚠️ Warning: Health check failed for ${ip} (app: ${appName}): ${e.message}"
            echo "The VM may still be initializing. Try accessing it manually in a few minutes."
        }
    }

    echo "Deployment completed for app: ${appName}"
}

def getVmPublicIp(String vmName, Map config) {
    def resourceGroup = getResourceGroupName(config)
    return sh(
        script: """
        az vm show -d -g ${resourceGroup} -n ${vmName} --query publicIps -o tsv
        """,
        returnStdout: true
    ).trim()
}

def getResourceGroupName(config) {
    try {
        // First try reading from tfvars file (most reliable)
        def resourceGroup = sh(
            script: "grep 'resource_group_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
            returnStdout: true
        ).trim()
        
        // If tfvars didn't work, try terraform output
        if (!resourceGroup || resourceGroup == '') {
            echo "Reading from terraform output..."
            try {
                resourceGroup = sh(
                    script: "terraform output -raw resource_group_name 2>/dev/null | tr -d '\n' | sed 's/[^a-zA-Z0-9._-]//g'",
                    returnStdout: true
                ).trim()
            } catch (Exception e) {
                echo "Terraform output failed: ${e.message}"
            }
        }
        
        // Final fallback to known resource group
        if (!resourceGroup || resourceGroup == '' || resourceGroup.length() < 5) {
            resourceGroup = "cloud-pratice-Tanishq.Parab-RG"
        }
        
        echo "Using resource group: ${resourceGroup}"
        return resourceGroup
    } catch (Exception e) {
        echo "Error getting resource group: ${e.message}"
        return "cloud-pratice-Tanishq.Parab-RG"
    }
}

def getAppGatewayName(config) {
    try {
        // First try reading from tfvars file (most reliable)
        def appGatewayName = sh(
            script: "grep 'app_gateway_name' terraform-azure.tfvars | head -1 | cut -d'\"' -f2",
            returnStdout: true
        ).trim()
        
        // If tfvars didn't work, try terraform output
        if (!appGatewayName || appGatewayName == '') {
            echo "Reading from terraform output..."
            try {
                appGatewayName = sh(
                    script: "terraform output -raw app_gateway_name 2>/dev/null | tr -d '\n' | sed 's/[^a-zA-Z0-9._-]//g'",
                    returnStdout: true
                ).trim()
            } catch (Exception e) {
                echo "Terraform output failed: ${e.message}"
            }
        }
        
        // Final fallback to known app gateway name
        if (!appGatewayName || appGatewayName == '' || appGatewayName.length() < 5) {
            appGatewayName = "blue-green-appgw"
        }
        
        echo "Using app gateway: ${appGatewayName}"
        return appGatewayName
    } catch (Exception e) {
        echo "Error getting app gateway name: ${e.message}"
        return "blue-green-appgw"
    }
}
