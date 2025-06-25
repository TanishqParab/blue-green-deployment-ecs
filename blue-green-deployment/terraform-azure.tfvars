# Blue-Green Deployment Configuration for Azure

azure = {
  location = "East US"
  tags = {
    Environment = "dev"
    Project     = "blue-green-deployment"
    ManagedBy   = "Terraform"
  }
}

# Azure VNet Configuration
azure_vnet = {
  # Basic VNet settings
  resource_group_name = "blue-green-rg"
  vnet_name           = "blue-green-vnet"
  vnet_cidr           = "10.0.0.0/16"

  # Subnet configuration
  public_subnet_cidrs = ["10.0.1.0/24", "10.0.2.0/24"]
  subnet_name_prefix  = "public-subnet"

  # Private subnet configuration (optional)
  create_private_subnets     = false # Set to true to enable private subnets
  private_subnet_cidrs       = []
  private_subnet_name_prefix = "private-subnet"
  create_nat_gateway         = false # Set to true to enable NAT gateway

  # Naming conventions
  nat_gateway_name_suffix = "nat-gateway"
  nat_gateway_ip_suffix   = "nat-gateway-ip"

  # Tag settings
  module_name       = "azure-vnet"
  terraform_managed = "true"
}

# Azure NSG Configuration
azure_nsg = {
  nsg_name = "blue-green-nsg"

  # Ingress rules
  ingress_rules = [
    {
      name                   = "SSH"
      priority               = 1001
      protocol               = "Tcp"
      destination_port_range = "22"
      source_address_prefix  = "*"
    },
    {
      name                   = "Flask"
      priority               = 1002
      protocol               = "Tcp"
      destination_port_range = "5000"
      source_address_prefix  = "*"
    },
    {
      name                   = "HTTP"
      priority               = 1003
      protocol               = "Tcp"
      destination_port_range = "80"
      source_address_prefix  = "*"
    },
    {
      name                   = "HTTPS"
      priority               = 1004
      protocol               = "Tcp"
      destination_port_range = "443"
      source_address_prefix  = "*"
    }
  ]

  # Tag settings
  module_name       = "azure-nsg"
  terraform_managed = "true"
}

############################################
# Azure Application Gateway Configuration
############################################

azure_app_gateway = {
  # Basic Application Gateway settings
  app_gateway_name     = "blue-green-appgw"
  app_gateway_sku_name = "Standard_v2"
  app_gateway_sku_tier = "Standard_v2"
  app_gateway_capacity = 2

  # Listener settings
  frontend_port     = 80
  backend_port      = 80 # Changed from 5000 to 80
  backend_protocol  = "Http"
  listener_protocol = "Http"

  # Application-specific backend pools
  application_target_groups = {
    app_1 = {
      blue_backend_pool_name  = "app1-blue-pool"
      green_backend_pool_name = "app1-green-pool"
      backend_port            = 80
    },
    app_2 = {
      blue_backend_pool_name  = "app2-blue-pool"
      green_backend_pool_name = "app2-green-pool"
      backend_port            = 80
    },
    app_3 = {
      blue_backend_pool_name  = "app3-blue-pool"
      green_backend_pool_name = "app3-green-pool"
      backend_port            = 80
    }
  }

  # Path-based routing
  application_paths = {
    app_1 = {
      priority     = 100
      path_pattern = "/app1*"
    },
    app_2 = {
      priority     = 200
      path_pattern = "/app2*"
    },
    app_3 = {
      priority     = 300
      path_pattern = "/app3*"
    }
  }

  # Health check settings
  health_check_path     = "/health"
  health_check_interval = 30
  health_check_timeout  = 10
  unhealthy_threshold   = 3

  # Tag settings
  module_name       = "azure-app-gateway"
  terraform_managed = "true"
}

############################################
# Azure VM Configuration
############################################

azure_vm = {
  # Basic VM settings
  vm_size        = "Standard_B2s"
  admin_username = "azureuser"
  ssh_key_name   = "azure-ssh-key"
  ssh_public_key = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDIRVohwaiaRbtma+sK3KishUGfgAQEMVBZRe7U0HvoOZJeJRwnNNdKbemRsPKumrPw1Ghx4vGKElDmSrO0TIqID0sUDyyv94lOCa+T1pOYfiTfI1YtGbMEVGI+c4TqgTK61DtfoC0bM1x3jQP09Je+fG+wHOKO+thLrOGUPOyUY4wqqEdxXxNZwQexkJOLHA1xOomCAgnkQTUwF9inTNznDxxu7jvwKbNkdQ3MY4jT8hQnp3omS56bTDPprYc/AY+a4h7M1B9lw18Mtwr5A/UcXAaF/6jd6ikhClxG12NoIagBNEcNwAvAHu4OozLZaRi7Z/cd6dVMPeT/OfqTsm0VuDW0pMzYHk3TXXKXOqeGfzk315qXbgbMwjtqRJbRrfhpIAm5Ak1M0zjT0N5K4Rtd8XCyCZXEhWEUjgVNCIXRFGRI/vFlhKlzNS6tWRR6tB846m1DAXvqOCrCSUu4qyBDFiE/kGpdaQgqmf/HFvlOvEyR7s0gu972u64z23k6BFHiNuSWvc6lzAya7CuGkzuJ/A/beZi2l5blXoPzXPay+UM+TxlN/mmQ8URhuFOC8FL7TL1R2nwmlLEJKFMk2IUbdjM7u8SbsKgFf9LWwv10IKrJb//0HVHw2RVShix9vxg8CD5FKChtsIgwphJVvSWsNNcjjeVX1yi0EBX0cLjC1Q== ec2-user@ip-172-31-86-39.ec2.internal" # Replace with actual key

  # Multiple applications configuration
  vm_application = {
    app_1 = {
      blue_vm_name  = "app1-blue-vm"
      green_vm_name = "app1-green-vm"
      app_port      = 80
    },
    app_2 = {
      blue_vm_name  = "app2-blue-vm"
      green_vm_name = "app2-green-vm"
      app_port      = 80
    },
    app_3 = {
      blue_vm_name  = "app3-blue-vm"
      green_vm_name = "app3-green-vm"
      app_port      = 80
    }
  }

  # Tag settings
  module_name       = "azure-vm"
  terraform_managed = "true"
}

# Additional tags
additional_tags = {
  Owner      = "DevOps Team"
  CostCenter = "Engineering"
}
