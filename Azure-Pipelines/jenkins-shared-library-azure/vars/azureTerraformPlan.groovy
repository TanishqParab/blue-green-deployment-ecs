// vars/azureTerraformPlan.groovy - Azure Terraform planning

def call(config) {
    def backendPoolsExist = true
    def bluePool = ""
    def greenPool = ""

    try {
        // Get app name from config or default to empty string
        def appName = config.appName ?: ""
        def bluePoolName = appName ? "app_${appName.replace('app', '')}-blue-pool" : "app_1-blue-pool"
        def greenPoolName = appName ? "app_${appName.replace('app', '')}-green-pool" : "app_1-green-pool"
        
        def resourceGroup = getResourceGroupName(config)
        def appGatewayName = getAppGatewayName(config)
        
        bluePool = sh(
            script: "az network application-gateway address-pool show --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --name ${bluePoolName} --query 'name' --output tsv 2>/dev/null || echo 'None'",
            returnStdout: true
        ).trim()
        
        greenPool = sh(
            script: "az network application-gateway address-pool show --gateway-name ${appGatewayName} --resource-group ${resourceGroup} --name ${greenPoolName} --query 'name' --output tsv 2>/dev/null || echo 'None'",
            returnStdout: true
        ).trim()
    } catch (Exception e) {
        echo "⚠️ Could not fetch backend pool info. Assuming first build. Skipping backend pool vars in plan."
        backendPoolsExist = false
    }

    def planCommand = "terraform plan"
    if (config.tfVarsFile) {
        planCommand += " -var-file=${config.tfVarsFile}"
    }

    if (backendPoolsExist && bluePool != 'None' && greenPool != 'None') {
        if (config.implementation == 'azure-aci') {
            planCommand += " -var='pipeline.blue_backend_pool_name=${bluePool}' -var='pipeline.green_backend_pool_name=${greenPool}'"
        } else {
            planCommand += " -var='blue_backend_pool_name=${bluePool}' -var='green_backend_pool_name=${greenPool}'"
        }
    }

    planCommand += " -out=tfplan"

    echo "Running Azure Terraform Plan: ${planCommand}"
    dir("${config.tfWorkingDir}") {
        sh "${planCommand}"
        archiveArtifacts artifacts: 'tfplan', onlyIfSuccessful: true
    }
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
