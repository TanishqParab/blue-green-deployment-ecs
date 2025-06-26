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

# Blue VM Setup using null_resource
resource "null_resource" "blue_vm_setup" {
  for_each = var.application

  depends_on = [azurerm_linux_virtual_machine.blue_vm]

  connection {
    type        = "ssh"
    user        = var.admin_username
    private_key = var.ssh_private_key
    host        = azurerm_public_ip.blue_vm_ip[each.key].ip_address
    timeout     = "10m"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/tmp/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app_${replace(each.key, "app_", "")}.py"
    destination = "/tmp/app_${each.key}.py"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/setup_flask_service.py"
    destination = "/tmp/setup_flask_service.py"
  }

  provisioner "remote-exec" {
    inline = [
      "sleep 60",
      "sudo mv /tmp/* /home/${var.admin_username}/",
      "sudo chown ${var.admin_username}:${var.admin_username} /home/${var.admin_username}/*",
      "sudo apt-get update",
      "sudo apt-get install -y dos2unix",
      "dos2unix /home/${var.admin_username}/*.sh /home/${var.admin_username}/*.py",
      "chmod +x /home/${var.admin_username}/install_dependencies.sh",
      "chmod +x /home/${var.admin_username}/setup_flask_service.py",
      "sudo /bin/bash /home/${var.admin_username}/install_dependencies.sh",
      "sudo python3 /home/${var.admin_username}/setup_flask_service.py ${each.key}"
    ]
  }
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

# Green VM Setup using null_resource
resource "null_resource" "green_vm_setup" {
  for_each = var.application

  depends_on = [azurerm_linux_virtual_machine.green_vm]

  connection {
    type        = "ssh"
    user        = var.admin_username
    private_key = var.ssh_private_key
    host        = azurerm_public_ip.green_vm_ip[each.key].ip_address
    timeout     = "10m"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/tmp/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app_${replace(each.key, "app_", "")}.py"
    destination = "/tmp/app_${each.key}.py"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/setup_flask_service.py"
    destination = "/tmp/setup_flask_service.py"
  }

  provisioner "remote-exec" {
    inline = [
      "sleep 60",
      "sudo mv /tmp/* /home/${var.admin_username}/",
      "sudo chown ${var.admin_username}:${var.admin_username} /home/${var.admin_username}/*",
      "sudo apt-get update",
      "sudo apt-get install -y dos2unix",
      "dos2unix /home/${var.admin_username}/*.sh /home/${var.admin_username}/*.py",
      "chmod +x /home/${var.admin_username}/install_dependencies.sh",
      "chmod +x /home/${var.admin_username}/setup_flask_service.py",
      "sudo /bin/bash /home/${var.admin_username}/install_dependencies.sh",
      "sudo python3 /home/${var.admin_username}/setup_flask_service.py ${each.key}"
    ]
  }
}
