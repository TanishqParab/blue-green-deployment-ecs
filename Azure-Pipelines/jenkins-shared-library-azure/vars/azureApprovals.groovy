// vars/azureApprovals.groovy - Azure-specific approval functions

def terraformApplyApproval(Map config) {
    dir("${config.tfWorkingDir}") {
        def planCmd = 'terraform plan -no-color'
        if (config.tfVarsFile) {
            planCmd += " -var-file=${config.tfVarsFile}"
        }
        planCmd += " > tfplan.txt"
        sh planCmd

        def tfPlan = readFile('tfplan.txt')
        archiveArtifacts artifacts: 'tfplan.txt', fingerprint: true

        echo "========== Terraform Plan Start =========="
        echo tfPlan
        echo "========== Terraform Plan End ============"

        def planDownloadLink = "${env.BUILD_URL}artifact/tfplan.txt"
        def appInfo = config.appName ? " for app ${config.appName}" : ""

        emailext (
            to: config.emailRecipient,
            subject: "Approval required for Azure Terraform apply${appInfo} - Build ${currentBuild.number}",
            body: """
                Hi,

                An Azure Terraform apply${appInfo} requires your approval.

                👉 Review the Terraform plan here:
                ${planDownloadLink}

                Once reviewed, approve/abort at:
                ${env.BUILD_URL}input

                Regards,  
                Jenkins Automation
            """,
            replyTo: config.emailRecipient
        )

        timeout(time: 1, unit: 'HOURS') {
            input(
                id: 'ApplyApproval',
                message: "Azure Terraform Apply Approval Required${appInfo}",
                ok: "Apply",
                parameters: [],
                description: """⚠️ Full plan too long.

✅ Review full plan here:
- [tfplan.txt Artifact](${planDownloadLink})
- Console Output (above this stage)"""
            )
        }
    }
}

def terraformDestroyApproval(Map config) {
    def destroyLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""

    emailext (
        to: config.emailRecipient,
        subject: "🚨 Approval Required for Azure Terraform Destroy${appInfo} - Build ${currentBuild.number}",
        body: """
        WARNING: You are about to destroy Azure infrastructure${appInfo}.

        👉 Click the link below to approve destruction:

        ${destroyLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "⚠️ Confirm destruction of Azure infrastructure${appInfo}?", ok: 'Destroy Now'
    }
}

def switchTrafficApprovalAzureVM(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    emailext (
        to: config.emailRecipient,
        subject: "Approval required to switch Azure VM traffic${appInfo} - Build ${currentBuild.number}",
        body: """
            Please review the Azure VM deployment and approve to switch traffic to the BLUE backend pool${appInfo}.
            
            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to switch traffic to the new BLUE Azure VM deployment${appInfo}?", ok: 'Switch Traffic'
    }
}

def switchTrafficApprovalAzureACI(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    // Determine test endpoint based on app
    def testPath = ""
    if (config.appName && config.appName != "app_1") {
        def appSuffix = config.appName.replace("app_", "")
        testPath = "/app${appSuffix}/test"
    } else {
        testPath = "/test"
    }
    
    emailext (
        to: config.emailRecipient,
        subject: "Approval required to switch Azure ACI traffic${appInfo} - Build ${currentBuild.number}",
        body: """
            Please review the Azure ACI deployment and approve to switch traffic${appInfo}.

            Current LIVE environment: ${env.LIVE_ENV}
            New environment to make LIVE: ${env.IDLE_ENV}

            You can test the new version at: http://${env.APP_GATEWAY_IP}${testPath}

            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to switch traffic from ${env.LIVE_ENV} to ${env.IDLE_ENV}${appInfo}?", ok: 'Switch Traffic'
    }
}

def rollbackApprovalAzureVM(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    emailext (
        to: config.emailRecipient,
        subject: "🛑 Approval required for Azure VM ROLLBACK${appInfo} - Build #${currentBuild.number}",
        body: "An Azure VM rollback has been triggered${appInfo}. Please review and approve the rollback at: ${buildLink}",
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "🚨 Confirm Azure VM rollback${appInfo} and approve execution", ok: 'Rollback'
    }
}

def rollbackApprovalAzureACI(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    // Determine test endpoint based on app
    def testPath = ""
    if (config.appName && config.appName != "app_1") {
        def appSuffix = config.appName.replace("app_", "")
        testPath = "/app${appSuffix}/test"
    } else {
        testPath = "/test"
    }
    
    emailext (
        to: config.emailRecipient,
        subject: "Approval required for Azure ACI rollback${appInfo} - Build ${currentBuild.number}",
        body: """
            Please review the Azure ACI rollback deployment and approve to switch traffic${appInfo}.
            
            Current LIVE environment: ${env.CURRENT_ENV}
            Environment to rollback to: ${env.ROLLBACK_ENV}
            Previous version image: ${env.ROLLBACK_IMAGE}
            
            You can test the rollback version at: http://${env.APP_GATEWAY_IP}${testPath}
            
            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to rollback from ${env.CURRENT_ENV} to ${env.ROLLBACK_ENV}${appInfo}?", ok: 'Confirm Rollback'
    }
}