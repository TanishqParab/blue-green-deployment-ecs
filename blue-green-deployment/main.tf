############################################
# Multi-Cloud Blue-Green Deployment
############################################
# Toggle between AWS and Azure by commenting/uncommenting sections

############################################
# Provider Configuration
############################################
/*
# AWS Provider - Comment out when using Azure
provider "aws" {
  region = var.aws.region
}
*/
# Azure Provider - Uncomment when using Azure
provider "azurerm" {
  features {}
  subscription_id                 = "2b4577df-bb3c-4dda-bd5f-5f6bd80f80d2"
  resource_provider_registrations = "none"
}


/*
############################################
# AWS IMPLEMENTATION
############################################
# Comment out this entire section when deploying to Azure

# AWS VPC Module
module "vpc" {
  source = "./modules/aws/vpc"

  vpc_cidr             = var.vpc.cidr_block
  vpc_name             = var.vpc.name
  enable_dns_support   = var.vpc.enable_dns_support
  enable_dns_hostnames = var.vpc.enable_dns_hostnames
  instance_tenancy     = var.vpc.instance_tenancy

  public_subnet_cidrs     = var.vpc.public_subnet_cidrs
  availability_zones      = var.vpc.availability_zones
  subnet_name_prefix      = var.vpc.subnet_name_prefix
  map_public_ip_on_launch = var.vpc.map_public_ip_on_launch

  create_private_subnets     = var.vpc.create_private_subnets
  private_subnet_cidrs       = var.vpc.private_subnet_cidrs
  private_subnet_name_prefix = var.vpc.private_subnet_name_prefix
  create_nat_gateway         = var.vpc.create_nat_gateway

  igw_name               = var.vpc.igw_name
  route_table_name       = var.vpc.route_table_name
  internet_cidr_block    = var.vpc.internet_cidr_block
  route_creation_timeout = var.vpc.route_creation_timeout

  enable_flow_logs       = var.vpc.enable_flow_logs
  flow_logs_traffic_type = var.vpc.flow_logs_traffic_type
  flow_logs_destination  = var.vpc.flow_logs_destination
  flow_logs_iam_role_arn = var.vpc.flow_logs_iam_role_arn

  environment       = var.aws.tags.Environment
  module_name       = var.vpc.module_name
  terraform_managed = var.vpc.terraform_managed
  additional_tags   = merge(var.aws.tags, var.additional_tags)
}

# AWS Security Group Module
module "security_group" {
  source                     = "./modules/aws/security_group"
  vpc_id                     = module.vpc.vpc_id
  security_group_name        = var.security_group.name
  security_group_description = var.security_group.description
  ingress_rules              = var.security_group.ingress_rules

  egress_from_port   = var.security_group.egress_from_port
  egress_to_port     = var.security_group.egress_to_port
  egress_protocol    = var.security_group.egress_protocol
  egress_cidr_blocks = var.security_group.egress_cidr_blocks
  egress_description = var.security_group.egress_description

  environment       = var.aws.tags.Environment
  module_name       = var.security_group.module_name
  terraform_managed = var.security_group.terraform_managed
  additional_tags   = merge(var.aws.tags, var.additional_tags)
}

# AWS ALB Module
module "alb" {
  source            = "./modules/aws/alb"
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
  security_group_id = module.security_group.security_group_id

  alb_name           = var.alb.name
  internal           = var.alb.internal
  load_balancer_type = var.alb.load_balancer_type

  listener_port     = var.alb.listener_port
  listener_protocol = var.alb.listener_protocol

  blue_target_group_name  = var.alb.blue_target_group_name
  green_target_group_name = var.alb.green_target_group_name
  target_group_port       = var.alb.target_group_port
  target_group_protocol   = var.alb.target_group_protocol
  target_type             = var.alb.target_type
  deregistration_delay    = var.alb.deregistration_delay

  application_target_groups = var.alb.application_target_groups
  application_paths         = var.alb.application_paths

  health_check_path     = var.alb.health_check_path
  health_check_interval = var.alb.health_check_interval
  health_check_timeout  = var.alb.health_check_timeout
  healthy_threshold     = var.alb.healthy_threshold
  unhealthy_threshold   = var.alb.unhealthy_threshold
  health_check_matcher  = var.alb.health_check_matcher
  health_check_port     = var.alb.health_check_port
  health_check_protocol = var.alb.health_check_protocol

  blue_weight  = var.alb.blue_weight
  green_weight = var.alb.green_weight

  environment       = var.aws.tags.Environment
  module_name       = var.alb.module_name
  terraform_managed = var.alb.terraform_managed
  additional_tags   = merge(var.aws.tags, var.additional_tags)
}

# AWS Compute Options - Choose ONE: EC2 OR ECS+ECR
/*
# Option 1: AWS EC2 Module (VM-based deployment)
module "ec2" {
  source = "./modules/aws/ec2"

  subnet_id           = module.vpc.public_subnet_ids[0]
  security_group_id   = module.security_group.security_group_id
  key_name            = var.ec2.key_name
  ssh_key_secret_name = var.ec2.ssh_key_secret_name
  instance_type       = var.ec2.instance_type
  ami_id              = var.ec2.ami_id
  environment_tag     = var.ec2.environment_tag
  ssh_user            = var.ec2.ssh_user
  additional_tags     = var.ec2.additional_tags

  application = var.ec2.application
}

# AWS ASG Module (Auto Scaling Group)
module "asg" {
  source            = "./modules/aws/asg"
  subnet_ids        = module.vpc.public_subnet_ids
  security_group_id = module.security_group.security_group_id
  key_name          = var.ec2.key_name

  # Update this line to use the first target group from each map
  alb_target_group_arns = [
    module.alb.blue_target_group_arns["app_1"],
    module.alb.green_target_group_arns["app_1"]
  ]

  desired_capacity = var.asg.desired_capacity
  min_size         = var.asg.min_size
  max_size         = var.asg.max_size
}

*/

/*
# Option 2: AWS ECS + ECR Modules (Container-based deployment)
# Uncomment these when using containers instead of VMs

module "ecr" {
  source = "./modules/aws/ecr"

  repository_name      = var.ecr.repository_name
  image_tag_mutability = var.ecr.image_tag_mutability
  aws_region           = var.aws.region
  scan_on_push         = var.ecr.scan_on_push

  app_py_path        = "${path.module}/modules/aws/ecs/scripts/app.py"
  app_py_path_prefix = "${path.module}/modules/aws/ecs/scripts/app_"
  dockerfile_path    = "${path.module}/modules/aws/ecs/scripts/Dockerfile"
  image_name         = var.ecr.image_name
  skip_docker_build  = var.ecr.skip_docker_build
  image_tag          = var.ecr.image_tag

  application = var.ecr.application

  environment       = var.aws.tags.Environment
  module_name       = var.ecr.module_name
  terraform_managed = var.ecr.terraform_managed
  additional_tags   = merge(var.aws.tags, var.additional_tags)
}

module "ecs" {
  source = "./modules/aws/ecs"

  # Basic ECS settings
  ecs_cluster_name    = var.ecs.cluster_name
  task_family         = var.ecs.task_family
  task_role_arn       = var.ecs.task_role_arn
  cpu                 = var.ecs.cpu
  memory              = var.ecs.memory
  container_name      = var.ecs.container_name
  container_image     = module.ecr.repository_url
  container_port      = var.ecs.container_port
  desired_count       = var.ecs.desired_count
  execution_role_name = var.ecs.execution_role_name
  blue_service_name   = var.ecs.blue_service_name
  green_service_name  = var.ecs.green_service_name
  image_tag           = var.ecs.image_tag

  # Multiple applications configuration
  application = var.ecs.application

  # Network settings
  public_subnet_ids = module.vpc.public_subnet_ids
  security_group_id = module.security_group.security_group_id
  network_mode      = var.ecs.network_mode
  assign_public_ip  = var.ecs.assign_public_ip

  # Target group settings
  blue_target_group_arn   = module.alb.blue_target_group_arns["app_1"]
  green_target_group_arn  = module.alb.green_target_group_arns["app_1"]
  blue_target_group_arns  = module.alb.blue_target_group_arns
  green_target_group_arns = module.alb.green_target_group_arns


  # Task definition settings
  ecs_task_definition      = var.ecs.task_definition
  requires_compatibilities = var.ecs.requires_compatibilities
  launch_type              = var.ecs.launch_type
  green_desired_count      = var.ecs.green_desired_count

  # Container configuration
  enable_container_insights = var.ecs.enable_container_insights
  enable_container_logs     = var.ecs.enable_container_logs
  log_group_name            = var.ecs.log_group_name
  log_retention_days        = var.ecs.log_retention_days
  container_environment     = var.ecs.container_environment
  container_secrets         = var.ecs.container_secrets
  container_protocol        = var.ecs.container_protocol
  blue_log_stream_prefix    = var.ecs.blue_log_stream_prefix
  green_log_stream_prefix   = var.ecs.green_log_stream_prefix
  container_essential       = var.ecs.container_essential
  log_driver                = var.ecs.log_driver

  # IAM configuration
  logs_policy_name          = var.ecs.logs_policy_name
  iam_policy_version        = var.ecs.iam_policy_version
  iam_service_principal     = var.ecs.iam_service_principal
  task_execution_policy_arn = var.ecs.task_execution_policy_arn

  # Naming conventions
  green_container_name_suffix = var.ecs.green_container_name_suffix
  green_task_family_suffix    = var.ecs.green_task_family_suffix
  blue_task_name_suffix       = var.ecs.blue_task_name_suffix
  green_task_name_suffix      = var.ecs.green_task_name_suffix
  log_resource_suffix         = var.ecs.log_resource_suffix

  # EFS configurations
  efs_root_directory_default     = var.ecs.efs_root_directory_default
  efs_transit_encryption_default = var.ecs.efs_transit_encryption_default

  # Deployment configuration
  deployment_maximum_percent         = var.ecs.deployment_maximum_percent
  deployment_minimum_healthy_percent = var.ecs.deployment_minimum_healthy_percent
  health_check_grace_period_seconds  = var.ecs.health_check_grace_period_seconds
  enable_fargate_capacity_providers  = var.ecs.enable_fargate_capacity_providers
  capacity_provider_strategy         = var.ecs.capacity_provider_strategy
  execute_command_logging            = var.ecs.execute_command_logging
  task_volumes                       = var.ecs.task_volumes

  # Tag settings
  environment            = var.aws.tags.Environment
  module_name            = var.ecs.module_name
  terraform_managed      = var.ecs.terraform_managed
  blue_deployment_type   = var.ecs.blue_deployment_type
  green_deployment_type  = var.ecs.green_deployment_type
  blue_service_tag_name  = var.ecs.blue_service_tag_name
  green_service_tag_name = var.ecs.green_service_tag_name
  additional_tags        = merge(var.aws.tags, var.additional_tags)

  # AWS region
  aws_region = var.aws.region

  depends_on = [module.alb, module.ecr]
}

*/


############################################
# AZURE IMPLEMENTATION
############################################
# Uncomment ONE of the following sections when deploying to Azure
/*
# Option 1: Azure ACI Implementation (Container-based deployment)
module "azure_aci_implementation" {
  source = "./modules/azure/implementations/aci"

  location        = var.azure.location
  environment     = var.azure.tags.Environment
  additional_tags = merge(var.azure.tags, var.additional_tags)

  app_py_path_prefix = "${path.module}/modules/azure/aci/scripts/app_"
  dockerfile_path    = "${path.module}/modules/azure/aci/scripts/Dockerfile"

  azure_vnet        = var.azure_vnet
  azure_nsg         = var.azure_nsg
  azure_app_gateway = var.azure_app_gateway
  azure_acr         = var.azure_acr
  azure_aci         = var.azure_aci
}

*/

# Option 2: Azure VM Implementation (VM-based deployment)
module "azure_vm_implementation" {
  source = "./modules/azure/implementations/vm"

  location        = var.azure.location
  environment     = var.azure.tags.Environment
  additional_tags = merge(var.azure.tags, var.additional_tags)

  azure_vnet        = var.azure_vnet
  azure_nsg         = var.azure_nsg
  azure_app_gateway = var.azure_app_gateway
  azure_vm          = var.azure_vm
}



