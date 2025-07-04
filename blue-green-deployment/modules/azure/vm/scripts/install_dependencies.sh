#!/bin/bash

# Update package manager
sudo apt-get update -y

# Install Python and dependencies
sudo apt-get install -y python3 python3-pip git unzip curl wget

# Install Flask (for the demo app)
pip3 install flask

# Install Azure CLI
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Install Java (Required for other tools if needed)
sudo apt-get install -y openjdk-17-jdk

# Install Terraform
wget -O terraform.zip https://releases.hashicorp.com/terraform/1.5.7/terraform_1.5.7_linux_amd64.zip
unzip terraform.zip
sudo mv terraform /usr/local/bin/
rm terraform.zip

echo "Installation of dependencies completed successfully!"