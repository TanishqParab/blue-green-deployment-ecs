############################################
# Azure VM Resources - Blue/Green Deployment
############################################

# SSH Key for VM access
resource "azurerm_ssh_public_key" "main" {
  name                = var.ssh_key_name
  resource_group_name = var.resource_group_name
  location            = var.location
  public_key          = var.ssh_public_key

  tags = merge(
    {
      Name        = var.ssh_key_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

############################################
# Blue VMs for Multiple Applications
############################################

# Public IPs for Blue VMs
resource "azurerm_public_ip" "blue_vm_ip" {
  for_each = var.application

  name                = "${each.value.blue_vm_name}-ip"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"

  tags = merge(
    {
      Name        = "${each.value.blue_vm_name}-ip"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      App         = each.key
      Deployment  = "blue"
    },
    var.additional_tags
  )
}

# Network Interfaces for Blue VMs
resource "azurerm_network_interface" "blue_vm_nic" {
  for_each = var.application

  name                = "${each.value.blue_vm_name}-nic"
  location            = var.location
  resource_group_name = var.resource_group_name

  ip_configuration {
    name                          = "internal"
    subnet_id                     = var.subnet_id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.blue_vm_ip[each.key].id
  }

  tags = merge(
    {
      Name        = "${each.value.blue_vm_name}-nic"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      App         = each.key
      Deployment  = "blue"
    },
    var.additional_tags
  )
}

# Blue VMs
resource "azurerm_linux_virtual_machine" "blue_vm" {
  for_each = var.application

  name                = each.value.blue_vm_name
  location            = var.location
  resource_group_name = var.resource_group_name
  size                = var.vm_size
  admin_username      = var.admin_username

  disable_password_authentication = true

  network_interface_ids = [
    azurerm_network_interface.blue_vm_nic[each.key].id,
  ]

  admin_ssh_key {
    username   = var.admin_username
    public_key = azurerm_ssh_public_key.main.public_key
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = var.os_disk_type
  }

  source_image_reference {
    publisher = var.vm_image_publisher
    offer     = var.vm_image_offer
    sku       = var.vm_image_sku
    version   = var.vm_image_version
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/home/${var.admin_username}/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app_${replace(each.key, "app_", "")}.py"
    destination = "/home/${var.admin_username}/app_${each.key}.py"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/setup_flask_service.py"
    destination = "/home/${var.admin_username}/setup_flask_service.py"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo apt-get install -y dos2unix",
      "dos2unix /home/${var.admin_username}/install_dependencies.sh",
      "dos2unix /home/${var.admin_username}/setup_flask_service.py",
      "chmod +x /home/${var.admin_username}/install_dependencies.sh",
      "chmod +x /home/${var.admin_username}/setup_flask_service.py",
      "sudo /bin/bash /home/${var.admin_username}/install_dependencies.sh",
      "echo 'Setting up service for app ${each.key}'",
      "sudo python3 /home/${var.admin_username}/setup_flask_service.py ${each.key}"
    ]
  }

  connection {
    type        = "ssh"
    user        = var.admin_username
    private_key = file("${path.root}/azure-vm-key")
    host        = self.public_ip
  }

  tags = merge(
    {
      Name        = each.value.blue_vm_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      App         = each.key
      Deployment  = "blue"
    },
    var.additional_tags
  )
}

############################################
# Green VMs for Multiple Applications
############################################

# Public IPs for Green VMs
resource "azurerm_public_ip" "green_vm_ip" {
  for_each = var.application

  name                = "${each.value.green_vm_name}-ip"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"

  tags = merge(
    {
      Name        = "${each.value.green_vm_name}-ip"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      App         = each.key
      Deployment  = "green"
    },
    var.additional_tags
  )
}

# Network Interfaces for Green VMs
resource "azurerm_network_interface" "green_vm_nic" {
  for_each = var.application

  name                = "${each.value.green_vm_name}-nic"
  location            = var.location
  resource_group_name = var.resource_group_name

  ip_configuration {
    name                          = "internal"
    subnet_id                     = var.subnet_id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.green_vm_ip[each.key].id
  }

  tags = merge(
    {
      Name        = "${each.value.green_vm_name}-nic"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      App         = each.key
      Deployment  = "green"
    },
    var.additional_tags
  )
}

# Green VMs
resource "azurerm_linux_virtual_machine" "green_vm" {
  for_each = var.application

  name                = each.value.green_vm_name
  location            = var.location
  resource_group_name = var.resource_group_name
  size                = var.vm_size
  admin_username      = var.admin_username

  disable_password_authentication = true

  network_interface_ids = [
    azurerm_network_interface.green_vm_nic[each.key].id,
  ]

  admin_ssh_key {
    username   = var.admin_username
    public_key = azurerm_ssh_public_key.main.public_key
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = var.os_disk_type
  }

  source_image_reference {
    publisher = var.vm_image_publisher
    offer     = var.vm_image_offer
    sku       = var.vm_image_sku
    version   = var.vm_image_version
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/home/${var.admin_username}/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app_${replace(each.key, "app_", "")}.py"
    destination = "/home/${var.admin_username}/app_${each.key}.py"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/setup_flask_service.py"
    destination = "/home/${var.admin_username}/setup_flask_service.py"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo apt-get install -y dos2unix",
      "dos2unix /home/${var.admin_username}/install_dependencies.sh",
      "dos2unix /home/${var.admin_username}/setup_flask_service.py",
      "chmod +x /home/${var.admin_username}/install_dependencies.sh",
      "chmod +x /home/${var.admin_username}/setup_flask_service.py",
      "sudo /bin/bash /home/${var.admin_username}/install_dependencies.sh",
      "echo 'Setting up service for app ${each.key}'",
      "sudo python3 /home/${var.admin_username}/setup_flask_service.py ${each.key}"
    ]
  }

  connection {
    type        = "ssh"
    user        = var.admin_username
    private_key = file("${path.root}/azure-vm-key")
    host        = self.public_ip
  }

  tags = merge(
    {
      Name        = each.value.green_vm_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      App         = each.key
      Deployment  = "green"
    },
    var.additional_tags
  )
}