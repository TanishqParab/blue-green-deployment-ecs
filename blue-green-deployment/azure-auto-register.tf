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
      
      # Use correct backend pool names with underscores
      az network application-gateway address-pool update \
        --gateway-name ${var.azure_app_gateway.app_gateway_name} \
        --resource-group ${module.azure_aci_implementation.resource_group_name} \
        --name app_1-blue-pool \
        --set backendAddresses='[{"ipAddress":"'$APP1_IP'"}]'
      
      az network application-gateway address-pool update \
        --gateway-name ${var.azure_app_gateway.app_gateway_name} \
        --resource-group ${module.azure_aci_implementation.resource_group_name} \
        --name app_2-blue-pool \
        --set backendAddresses='[{"ipAddress":"'$APP2_IP'"}]'
      
      az network application-gateway address-pool update \
        --gateway-name ${var.azure_app_gateway.app_gateway_name} \
        --resource-group ${module.azure_aci_implementation.resource_group_name} \
        --name app_3-blue-pool \
        --set backendAddresses='[{"ipAddress":"'$APP3_IP'"}]'
      
      # Get static welcome container IP from module output
      STATIC_IP=$(az container show --name static-welcome-container --resource-group ${module.azure_aci_implementation.resource_group_name} --query ipAddress.ip --output tsv 2>/dev/null || echo "")
      if [ ! -z "$STATIC_IP" ]; then
        echo "Static welcome IP: $STATIC_IP"
        
        az network application-gateway address-pool update \
          --gateway-name ${var.azure_app_gateway.app_gateway_name} \
          --resource-group ${module.azure_aci_implementation.resource_group_name} \
          --name default-static-pool \
          --set backendAddresses='[{"ipAddress":"'$STATIC_IP'"}]'
      fi
      
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
      
      # Get container IPs for cleanup
      APP1_IP=$(az container show --name app1-blue-container --resource-group ${self.triggers.resource_group} --query ipAddress.ip --output tsv 2>/dev/null || echo "")
      APP2_IP=$(az container show --name app2-blue-container --resource-group ${self.triggers.resource_group} --query ipAddress.ip --output tsv 2>/dev/null || echo "")
      APP3_IP=$(az container show --name app3-blue-container --resource-group ${self.triggers.resource_group} --query ipAddress.ip --output tsv 2>/dev/null || echo "")
      
      # Remove registrations if IPs exist
      if [ ! -z "$APP1_IP" ]; then
        az network application-gateway address-pool update \
          --gateway-name ${self.triggers.app_gateway_name} \
          --resource-group ${self.triggers.resource_group} \
          --name app_1-blue-pool \
          --set backendAddresses='[]' || true
        
        az network application-gateway address-pool update \
          --gateway-name ${self.triggers.app_gateway_name} \
          --resource-group ${self.triggers.resource_group} \
          --name default-static-pool \
          --set backendAddresses='[]' || true
      fi
      
      if [ ! -z "$APP2_IP" ]; then
        az network application-gateway address-pool update \
          --gateway-name ${self.triggers.app_gateway_name} \
          --resource-group ${self.triggers.resource_group} \
          --name app_2-blue-pool \
          --set backendAddresses='[]' || true
      fi
      
      if [ ! -z "$APP3_IP" ]; then
        az network application-gateway address-pool update \
          --gateway-name ${self.triggers.app_gateway_name} \
          --resource-group ${self.triggers.resource_group} \
          --name app_3-blue-pool \
          --set backendAddresses='[]' || true
      fi
      
      echo "✅ Cleanup complete!"
    EOT
  }

  depends_on = [
    module.azure_aci_implementation
  ]
}
