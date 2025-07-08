// vars/azureAciInitialDeploymentImpl.groovy - Azure ACI initial deployment implementation

def initialize(Map config) {
    def buildId = currentBuild.number
    echo "Current Build ID: ${buildId}"
}

def deployInitialApplication(Map config) {
    if (config.implementation == 'azure-aci' && params.INITIAL_DEPLOYMENT == 'YES') {
        echo "🚀 Executing initial deployment for Azure ACI..."
        
        def appName = config.appName ?: "app_1"
        
        def deployConfig = config.clone()
        deployConfig.appName = appName
        
        azureAciInitialDeploymentUtils.deployToBlueContainer(deployConfig)
    } else {
        echo "⏭️ Skipping initial deployment (not Azure ACI or INITIAL_DEPLOYMENT not set to YES)"
    }
}

def getStages(Map config) {
    def stages = []
    
    stages << stage('Initialize') {
        steps {
            script {
                initialize(config)
            }
        }
    }
    
    stages << stage('Deploy Initial Application') {
        when {
            expression { config.implementation == 'azure-aci' && params.INITIAL_DEPLOYMENT == 'YES' }
        }
        steps {
            script {
                deployInitialApplication(config)
            }
        }
    }
    
    return stages
}