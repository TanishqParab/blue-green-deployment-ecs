#!/bin/bash

# Update package manager
sudo apt-get update -y

# Install essential dependencies
sudo apt-get install -y python3 python3-pip curl wget

# Install Flask (for the demo app)
pip3 install flask

# Install Azure CLI (lightweight installation)
echo "Installing Azure CLI..."
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

echo "Installation of dependencies completed successfully!"