# Automatic Load Balancer Registration for Testing
# Only runs when skip_docker_build = false (testing mode)
# Runs after all modules are complete to avoid dependency issues

# Automatic Blue Container Registration
resource "null_resource" "auto_register_containers" {
  count = var.azure_acr.skip_docker_build ? 0 : 1

  triggers = {
    app_gateway_name = var.azure_app_gateway.app_gateway_name
    resource_group   = module.azure_aci_implementation.resource_group_name
    always_run       = timestamp()
  }

  provisioner "local-exec" {
    command = <<-EOT
      echo "🚀 Starting automatic container registration..."
      
      # Wait for containers to be fully ready
      sleep 90
      
      # Get all container IPs
      echo "📍 Getting container IPs..."
      APP1_IP=$(az container show --name app1-blue-container --resource-group ${module.azure_aci_implementation.resource_group_name} --query ipAddress.ip --output tsv)
      APP2_IP=$(az container show --name app2-blue-container --resource-group ${module.azure_aci_implementation.resource_group_name} --query ipAddress.ip --output tsv)
      APP3_IP=$(az container show --name app3-blue-container --resource-group ${module.azure_aci_implementation.resource_group_name} --query ipAddress.ip --output tsv)
      
      echo "Container IPs: App1=$APP1_IP, App2=$APP2_IP, App3=$APP3_IP"
      
      # Register containers to backend pools
      echo "🔗 Registering containers to backend pools..."
      
      # Register App1
      az network application-gateway address-pool update \
        --gateway-name ${var.azure_app_gateway.app_gateway_name} \
        --resource-group ${module.azure_aci_implementation.resource_group_name} \
        --name app1-blue-pool \
        --set backendAddresses='[{"ipAddress":"'$APP1_IP'"}]'
      
      # Register App2
      az network application-gateway address-pool update \
        --gateway-name ${var.azure_app_gateway.app_gateway_name} \
        --resource-group ${module.azure_aci_implementation.resource_group_name} \
        --name app2-blue-pool \
        --set backendAddresses='[{"ipAddress":"'$APP2_IP'"}]'
      
      # Register App3
      az network application-gateway address-pool update \
        --gateway-name ${var.azure_app_gateway.app_gateway_name} \
        --resource-group ${module.azure_aci_implementation.resource_group_name} \
        --name app3-blue-pool \
        --set backendAddresses='[{"ipAddress":"'$APP3_IP'"}]'
      
      echo "✅ Registration complete!"
      
      # Get Load Balancer IP
      LB_IP=$(az network public-ip show --resource-group ${module.azure_aci_implementation.resource_group_name} --name ${var.azure_app_gateway.app_gateway_name}-ip --query ipAddress --output tsv)
      echo "🌐 Load Balancer IP: $LB_IP"
      echo "🧪 Test URLs:"
      echo "  http://$LB_IP/app1/"
      echo "  http://$LB_IP/app2/"
      echo "  http://$LB_IP/app3/"
      echo "  http://$LB_IP/"
    EOT
  }

  # Cleanup on destroy
  provisioner "local-exec" {
    when    = destroy
    command = <<-EOT
      echo "🧹 Cleaning up backend pool registrations..."
      
      # Clear backend pools
      az network application-gateway address-pool update \
        --gateway-name ${self.triggers.app_gateway_name} \
        --resource-group ${self.triggers.resource_group} \
        --name app1-blue-pool \
        --set backendAddresses='[]' || true
      
      az network application-gateway address-pool update \
        --gateway-name ${self.triggers.app_gateway_name} \
        --resource-group ${self.triggers.resource_group} \
        --name app2-blue-pool \
        --set backendAddresses='[]' || true
      
      az network application-gateway address-pool update \
        --gateway-name ${self.triggers.app_gateway_name} \
        --resource-group ${self.triggers.resource_group} \
        --name app3-blue-pool \
        --set backendAddresses='[]' || true
      
      echo "✅ Cleanup complete!"
    EOT
  }

  depends_on = [
    module.azure_aci_implementation
  ]
}
