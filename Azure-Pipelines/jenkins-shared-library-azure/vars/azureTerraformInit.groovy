// vars/azureTerraformInit.groovy - Azure Terraform initialization

def call(config) {
    echo "Initializing Terraform for Azure..."
    dir("${config.tfWorkingDir}") {
        sh "terraform init"
    }
}