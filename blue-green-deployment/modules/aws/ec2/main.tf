############################################
# Blue and Green Instances for Multiple Apps
############################################

# Data source to fetch SSH keys from AWS Secrets Manager
data "aws_secretsmanager_secret" "ssh_keys" {
  name = var.ssh_key_secret_name
}

data "aws_secretsmanager_secret_version" "ssh_keys_current" {
  secret_id = data.aws_secretsmanager_secret.ssh_keys.id
}

locals {
  # Parse the secret JSON - expected format: {"private_key": "...", "public_key": "..."}
  ssh_keys = jsondecode(data.aws_secretsmanager_secret_version.ssh_keys_current.secret_string)
}

############################################
# Blue Instances
############################################

resource "aws_instance" "blue" {
  for_each = var.application

  ami             = var.ami_id
  instance_type   = var.instance_type
  key_name        = var.key_name
  subnet_id       = var.subnet_id
  security_groups = [var.security_group_id]

  tags = merge(
    {
      Name        = each.value.blue_instance_name
      Environment = var.environment_tag
      App         = each.key
      Deployment  = "blue"
    },
    var.additional_tags
  )

  provisioner "file" {
    source      = "${path.module}/${var.install_dependencies_script_path}"
    destination = "/home/${var.ssh_user}/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app_${replace(each.key, "app_", "")}.py"
    destination = "/home/${var.ssh_user}/app_${each.key}.py"
  }

  provisioner "file" {
    source      = "${path.root}/${var.jenkins_file_path}"
    destination = "/home/${var.ssh_user}/Jenkinsfile"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/setup_flask_service.py"
    destination = "/home/${var.ssh_user}/setup_flask_service.py"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo yum install -y dos2unix",
      "dos2unix /home/${var.ssh_user}/install_dependencies.sh",
      "dos2unix /home/${var.ssh_user}/setup_flask_service.py",
      "chmod +x /home/${var.ssh_user}/install_dependencies.sh",
      "chmod +x /home/${var.ssh_user}/setup_flask_service.py",
      "sudo /bin/bash /home/${var.ssh_user}/install_dependencies.sh",
      "echo \"Setting up service for app ${each.key}\"",
      "sudo python3 /home/${var.ssh_user}/setup_flask_service.py ${each.key}"
    ]
  }

  connection {
    type        = "ssh"
    user        = var.ssh_user
    private_key = local.ssh_keys.private_key
    host        = self.public_ip
  }
}

/*
# Blue target group attachments
resource "aws_lb_target_group_attachment" "blue_attachment" {
  for_each = var.blue_target_group_arns

  target_group_arn = each.value
  target_id        = aws_instance.blue[each.key].id
  port             = lookup(var.application[each.key], "app_port", 80)
}
*/

############################################
# Green Instances
############################################

resource "aws_instance" "green" {
  for_each = var.application

  ami             = var.ami_id
  instance_type   = var.instance_type
  key_name        = var.key_name
  subnet_id       = var.subnet_id
  security_groups = [var.security_group_id]

  tags = merge(
    {
      Name        = each.value.green_instance_name
      Environment = var.environment_tag
      App         = each.key
      Deployment  = "green"
    },
    var.additional_tags
  )

  provisioner "file" {
    source      = "${path.module}/${var.install_dependencies_script_path}"
    destination = "/home/${var.ssh_user}/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app_${replace(each.key, "app_", "")}.py"
    destination = "/home/${var.ssh_user}/app_${each.key}.py"
  }

  provisioner "file" {
    source      = "${path.root}/${var.jenkins_file_path}"
    destination = "/home/${var.ssh_user}/Jenkinsfile"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/setup_flask_service.py"
    destination = "/home/${var.ssh_user}/setup_flask_service.py"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo yum install -y dos2unix",
      "dos2unix /home/${var.ssh_user}/install_dependencies.sh",
      "dos2unix /home/${var.ssh_user}/setup_flask_service.py",
      "chmod +x /home/${var.ssh_user}/install_dependencies.sh",
      "chmod +x /home/${var.ssh_user}/setup_flask_service.py",
      "sudo /bin/bash /home/${var.ssh_user}/install_dependencies.sh",
      "echo \"Setting up service for app ${each.key}\"",
      "sudo python3 /home/${var.ssh_user}/setup_flask_service.py ${each.key}"
    ]
  }

  connection {
    type        = "ssh"
    user        = var.ssh_user
    private_key = local.ssh_keys.private_key
    host        = self.public_ip
  }
}

/*
# Green target group attachments
resource "aws_lb_target_group_attachment" "green_attachment" {
  for_each = var.green_target_group_arns

  target_group_arn = each.value
  target_id        = aws_instance.green[each.key].id
  port             = lookup(var.application[each.key], "app_port", 80)
}

*/
