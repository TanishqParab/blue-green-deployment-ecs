# Azure Key Vault Setup for SSH Keys

This guide explains how to set up Azure Key Vault to securely store SSH keys for your blue-green deployment infrastructure.

## Prerequisites

- Azure CLI installed and configured
- Azure subscription with appropriate permissions
- SSH key pair generated

## Step 1: Create Azure Key Vault

### 1.1 Use Existing Resource Group
```bash
# Your existing resource group: cloud-pratice-Tanishq.Parab-RG
# No need to create - already exists
```

### 1.2 Create Key Vault

#### Option A: Using Azure CLI
```bash
az keyvault create \
  --name blue-green-keyvault \
  --resource-group cloud-pratice-Tanishq.Parab-RG \
  --location "East US" \
  --sku Standard
```

#### Option B: Using Azure Portal (UI)
1. **Go to Azure Portal**: https://portal.azure.com
2. **Search for "Key Vault"** in the top search bar
3. **Click "Create"** or "+ Add"
4. **Fill in the details**:
   - **Subscription**: Select your subscription
   - **Resource Group**: `cloud-pratice-Tanishq.Parab-RG`
   - **Key Vault Name**: `blue-green-keyvault`
   - **Region**: `East US`
   - **Pricing Tier**: `Standard`
5. **Click "Review + Create"**
6. **Click "Create"**
7. **Wait for deployment** to complete
8. **Go to the Key Vault** resource once created

## Step 2: Use Existing SSH Keys

```bash
# You already have these keys on your EC2 instance:
# azure-vm-key (private key)
# azure-vm-key.pub (public key)
```

## Step 3: Prepare SSH Keys JSON

Create a JSON object containing both keys:

```bash
# Read your existing keys and create JSON
PRIVATE_KEY=$(cat azure-vm-key | sed ':a;N;$!ba;s/\n/\\n/g')
PUBLIC_KEY=$(cat azure-vm-key.pub)

# Create JSON format
cat > ssh-keys.json << EOF
{
  "private_key": "$PRIVATE_KEY",
  "public_key": "$PUBLIC_KEY"
}
EOF
```

## Step 4: Store SSH Keys in Key Vault

#### Option A: Using Azure CLI
```bash
# Store the SSH keys JSON as a secret
az keyvault secret set \
  --vault-name blue-green-keyvault \
  --name blue-green-ssh-keys \
  --file ssh-keys.json

# Verify the secret was created
az keyvault secret show \
  --vault-name blue-green-keyvault \
  --name blue-green-ssh-keys \
  --query "value" -o tsv
```

#### Option B: Using Azure Portal (UI)
1. **Go to your Key Vault** in Azure Portal
2. **Click "Secrets"** in the left menu
3. **Click "+ Generate/Import"**
4. **Fill in the details**:
   - **Upload Options**: `Manual`
   - **Name**: `blue-green-ssh-keys`
   - **Value**: Copy and paste the JSON content from `ssh-keys.json`
   - **Content Type**: `application/json`
   - **Set activation date**: Leave empty
   - **Set expiration date**: Leave empty
   - **Enabled**: `Yes`
5. **Click "Create"**
6. **Verify** the secret appears in the secrets list

## Step 5: Set Key Vault Access Policies

### 5.1 Get your Azure AD Object ID
```bash
# Get your user object ID
USER_OBJECT_ID=$(az ad signed-in-user show --query objectId -o tsv)
echo "Your Object ID: $USER_OBJECT_ID"
```

### 5.2 Set Access Policy for Terraform

#### Option A: Using Azure CLI
```bash
# Grant Terraform access to Key Vault secrets
az keyvault set-policy \
  --name blue-green-keyvault \
  --object-id $USER_OBJECT_ID \
  --secret-permissions get list set delete
```

#### Option B: Using Azure Portal (UI)
1. **Go to your Key Vault** in Azure Portal
2. **Click "Access Policies"** in the left menu
3. **Click "+ Add Access Policy"**
4. **Configure permissions**:
   - **Secret Permissions**: Select `Get`, `List`, `Set`, `Delete`
   - **Key Permissions**: Leave empty
   - **Certificate Permissions**: Leave empty
5. **Select Principal**: Click "None selected"
   - Search for your email/username
   - Select your account
   - Click "Select"
6. **Click "Add"**
7. **Click "Save"** at the top to apply changes

### 5.3 For Service Principal (if using automated deployment)
```bash
# If using service principal for Terraform
# Replace <SERVICE_PRINCIPAL_OBJECT_ID> with actual ID
az keyvault set-policy \
  --name blue-green-keyvault \
  --object-id <SERVICE_PRINCIPAL_OBJECT_ID> \
  --secret-permissions get list
```

## Step 6: Configure Terraform Variables

Update your `terraform-azure.tfvars` file:

```hcl
azure_vm = {
  # ... other settings ...
  ssh_key_name        = "azure-ssh-key"
  key_vault_name      = "blue-green-keyvault"
  ssh_key_secret_name = "blue-green-ssh-keys"
  # ... rest of configuration ...
}
```

## Step 7: Verify Setup

### 7.1 Test Key Vault Access
```bash
# Test reading the secret
az keyvault secret show \
  --vault-name blue-green-keyvault \
  --name blue-green-ssh-keys \
  --query "value" -o tsv | jq .
```

### 7.2 Validate JSON Format
The secret should contain valid JSON with both keys:
```json
{
  "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----",
  "public_key": "ssh-rsa AAAAB3NzaC1yc2EAAAA... user@host"
}
```

## Step 8: Clean Up Temporary Files

```bash
# Remove temporary files
rm -f ssh-keys.json
```

## Terraform Usage

Once configured, Terraform will:

1. **Read Key Vault**: Access the specified Key Vault
2. **Fetch Secret**: Retrieve the SSH keys JSON secret
3. **Parse Keys**: Extract public and private keys from JSON
4. **Deploy VMs**: Use keys for VM authentication and provisioning

## Security Best Practices

### ✅ **Recommended:**
- Use separate Key Vaults for different environments
- Implement least-privilege access policies
- Enable Key Vault logging and monitoring
- Rotate SSH keys regularly
- Use managed identities when possible

### ❌ **Avoid:**
- Storing keys in plain text files
- Hardcoding keys in Terraform files
- Overly permissive access policies
- Sharing Key Vault across unrelated projects

## Troubleshooting

### Common Issues:

1. **Access Denied Error**
   ```bash
   # Check access policies
   az keyvault show --name blue-green-keyvault --query "properties.accessPolicies"
   ```

2. **Secret Not Found**
   ```bash
   # List all secrets
   az keyvault secret list --vault-name blue-green-keyvault
   ```

3. **Invalid JSON Format**
   ```bash
   # Validate JSON
   az keyvault secret show --vault-name blue-green-keyvault --name blue-green-ssh-keys --query "value" -o tsv | jq .
   ```

4. **Key Vault Not Found**
   ```bash
   # Check Key Vault exists
   az keyvault list --query "[?name=='blue-green-keyvault']"
   ```

## Next Steps

After completing this setup:

1. **Update terraform-azure.tfvars** with Key Vault details
2. **Run Terraform Plan** to verify configuration
3. **Deploy Infrastructure** with secure SSH key management

```bash
terraform plan -var-file="terraform-azure.tfvars"
terraform apply -var-file="terraform-azure.tfvars"
```

Your SSH keys are now securely managed through Azure Key Vault! 🔐