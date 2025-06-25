/*
############################################
# ENTIRE FILE COMMENTED OUT
# Use main-multi-cloud.tf instead
############################################

############################################
# Provider Configuration
############################################

# AWS Provider - Comment out when using Azure
provider "aws" {
  region = var.aws.region
}

############################################
# AWS IMPLEMENTATION - COMMENTED OUT
############################################
/*
############################################
# VPC Module
############################################

module "vpc" {
  source = "./modules/vpc"

  # Basic VPC settings
  vpc_cidr             = var.vpc.cidr_block
  vpc_name             = var.vpc.name
  enable_dns_support   = var.vpc.enable_dns_support
  enable_dns_hostnames = var.vpc.enable_dns_hostnames
  instance_tenancy     = var.vpc.instance_tenancy

  # Subnet configuration
  public_subnet_cidrs     = var.vpc.public_subnet_cidrs
  availability_zones      = var.vpc.availability_zones
  subnet_name_prefix      = var.vpc.subnet_name_prefix
  map_public_ip_on_launch = var.vpc.map_public_ip_on_launch

  # Private subnet configuration
  create_private_subnets     = var.vpc.create_private_subnets
  private_subnet_cidrs       = var.vpc.private_subnet_cidrs
  private_subnet_name_prefix = var.vpc.private_subnet_name_prefix
  create_nat_gateway         = var.vpc.create_nat_gateway

  # Routing configuration
  igw_name               = var.vpc.igw_name
  route_table_name       = var.vpc.route_table_name
  internet_cidr_block    = var.vpc.internet_cidr_block
  route_creation_timeout = var.vpc.route_creation_timeout

  # Flow logs configuration
  enable_flow_logs       = var.vpc.enable_flow_logs
  flow_logs_traffic_type = var.vpc.flow_logs_traffic_type
  flow_logs_destination  = var.vpc.flow_logs_destination
  flow_logs_iam_role_arn = var.vpc.flow_logs_iam_role_arn

  # Naming conventions
  nat_eip_name_suffix             = var.vpc.nat_eip_name_suffix
  nat_gateway_name_suffix         = var.vpc.nat_gateway_name_suffix
  private_route_table_name_suffix = var.vpc.private_route_table_name_suffix
  flow_logs_name_suffix           = var.vpc.flow_logs_name_suffix

  # Type tags
  public_subnet_type       = var.vpc.public_subnet_type
  private_subnet_type      = var.vpc.private_subnet_type
  public_route_table_type  = var.vpc.public_route_table_type
  private_route_table_type = var.vpc.private_route_table_type

  # Calculation methods
  az_count_calculation_method = var.vpc.az_count_calculation_method

  # Tag settings
  environment       = var.aws.tags.Environment
  module_name       = var.vpc.module_name
  terraform_managed = var.vpc.terraform_managed
  additional_tags   = merge(var.aws.tags, var.additional_tags)
}

############################################
# Security Group Module
############################################

module "security_group" {
  source                     = "./modules/security_group"
  vpc_id                     = module.vpc.vpc_id
  security_group_name        = var.security_group.name
  security_group_description = var.security_group.description
  ingress_rules              = var.security_group.ingress_rules

  # Egress settings
  egress_from_port   = var.security_group.egress_from_port
  egress_to_port     = var.security_group.egress_to_port
  egress_protocol    = var.security_group.egress_protocol
  egress_cidr_blocks = var.security_group.egress_cidr_blocks
  egress_description = var.security_group.egress_description

  # Tag settings
  environment       = var.aws.tags.Environment
  module_name       = var.security_group.module_name
  terraform_managed = var.security_group.terraform_managed
  additional_tags   = merge(var.aws.tags, var.additional_tags)
}

############################################
# ALB Module
############################################

module "alb" {
  source            = "./modules/alb"
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
  security_group_id = module.security_group.security_group_id

  # Basic ALB settings
  alb_name           = var.alb.name
  internal           = var.alb.internal
  load_balancer_type = var.alb.load_balancer_type

  # Listener settings
  listener_port     = var.alb.listener_port
  listener_protocol = var.alb.listener_protocol

  # Target group settings
  blue_target_group_name  = var.alb.blue_target_group_name
  green_target_group_name = var.alb.green_target_group_name
  target_group_port       = var.alb.target_group_port
  target_group_protocol   = var.alb.target_group_protocol
  target_type             = var.alb.target_type
  deregistration_delay    = var.alb.deregistration_delay

  # New settings for multi-app support
  application_target_groups = var.alb.application_target_groups
  application_paths         = var.alb.application_paths

  # Health check settings
  health_check_path     = var.alb.health_check_path
  health_check_interval = var.alb.health_check_interval
  health_check_timeout  = var.alb.health_check_timeout
  healthy_threshold     = var.alb.healthy_threshold
  unhealthy_threshold   = var.alb.unhealthy_threshold
  health_check_matcher  = var.alb.health_check_matcher
  health_check_port     = var.alb.health_check_port
  health_check_protocol = var.alb.health_check_protocol

  # Stickiness settings
  stickiness_enabled               = var.alb.stickiness_enabled
  stickiness_duration              = var.alb.stickiness_duration
  target_group_stickiness_enabled  = var.alb.target_group_stickiness_enabled
  target_group_stickiness_type     = var.alb.target_group_stickiness_type
  target_group_stickiness_duration = var.alb.target_group_stickiness_duration

  # Traffic distribution
  blue_weight  = var.alb.blue_weight
  green_weight = var.alb.green_weight

  # HTTPS settings
  create_https_listener = var.alb.create_https_listener
  https_port            = var.alb.https_port
  https_protocol        = var.alb.https_protocol
  ssl_policy            = var.alb.ssl_policy
  certificate_arn       = var.alb.certificate_arn

  # Access logs settings
  enable_access_logs  = var.alb.enable_access_logs
  access_logs_bucket  = var.alb.access_logs_bucket
  access_logs_prefix  = var.alb.access_logs_prefix
  access_logs_enabled = var.alb.access_logs_enabled

  # Additional settings
  idle_timeout               = var.alb.idle_timeout
  enable_deletion_protection = var.alb.enable_deletion_protection
  drop_invalid_header_fields = var.alb.drop_invalid_header_fields
  forward_action_type        = var.alb.forward_action_type

  # Tag settings
  environment            = var.aws.tags.Environment
  module_name            = var.alb.module_name
  terraform_managed      = var.alb.terraform_managed
  blue_deployment_group  = var.alb.blue_deployment_group
  green_deployment_group = var.alb.green_deployment_group
  additional_tags        = merge(var.aws.tags, var.additional_tags)
}
*/


/*
############################################
# ECR Module
############################################

module "ecr" {
  source = "./modules/ecr"

  # Basic ECR settings
  repository_name      = var.ecr.repository_name
  image_tag_mutability = var.ecr.image_tag_mutability
  aws_region           = var.aws.region
  scan_on_push         = var.ecr.scan_on_push

  # Docker build settings
  app_py_path        = "${path.module}/modules/ecs/scripts/app.py"
  app_py_path_prefix = "${path.module}/modules/ecs/scripts/app_"
  dockerfile_path    = "${path.module}/modules/ecs/scripts/Dockerfile"
  image_name         = var.ecr.image_name
  skip_docker_build  = var.ecr.skip_docker_build
  image_tag          = var.ecr.image_tag
  docker_username    = var.ecr.docker_username
  docker_build_args  = var.ecr.docker_build_args

  # Multiple applications configuration
  application = var.ecr.application

  # Retry settings
  max_retries         = var.ecr.max_retries
  retry_sleep_seconds = var.ecr.retry_sleep_seconds

  # Error handling
  file_not_found_message = var.ecr.file_not_found_message

  # Tag settings
  environment       = var.aws.tags.Environment
  module_name       = var.ecr.module_name
  terraform_managed = var.ecr.terraform_managed
  additional_tags   = merge(var.aws.tags, var.additional_tags)
}


############################################
# ECS Module
############################################

module "ecs" {
  source = "./modules/ecs"

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



/*
############################################
# Azure Provider
############################################

provider "azurerm" {
  features {}
}

############################################
# AZURE IMPLEMENTATION
############################################

# Azure VNet Module
module "azure_vnet" {
  source = "./azure-modules/vnet"

  resource_group_name = var.azure.resource_group_name
  location            = var.azure.location
  vnet_name           = var.azure.vnet_name
  vnet_cidr           = var.azure.vnet_cidr

  public_subnet_cidrs  = var.azure.public_subnet_cidrs
  subnet_name_prefix   = var.azure.subnet_name_prefix

  create_private_subnets     = var.azure.create_private_subnets
  private_subnet_cidrs       = var.azure.private_subnet_cidrs
  private_subnet_name_prefix = var.azure.private_subnet_name_prefix
  create_nat_gateway         = var.azure.create_nat_gateway

  environment       = var.azure.tags.Environment
  module_name       = "azure-vnet"
  terraform_managed = "true"
  additional_tags   = merge(var.azure.tags, var.additional_tags)
}

# Azure NSG Module
module "azure_nsg" {
  source = "./azure-modules/nsg"

  nsg_name            = var.azure.nsg_name
  location            = var.azure.location
  resource_group_name = module.azure_vnet.resource_group_name
  subnet_ids          = module.azure_vnet.public_subnet_ids

  ingress_rules = var.azure.nsg_rules

  environment       = var.azure.tags.Environment
  module_name       = "azure-nsg"
  terraform_managed = "true"
  additional_tags   = merge(var.azure.tags, var.additional_tags)
}

# Azure Application Gateway Module
module "azure_app_gateway" {
  source = "./azure-modules/app-gateway"

  app_gateway_name    = var.azure.app_gateway_name
  location            = var.azure.location
  resource_group_name = module.azure_vnet.resource_group_name
  gateway_subnet_id   = module.azure_vnet.public_subnet_ids[0]

  sku_name  = var.azure.app_gateway_sku_name
  sku_tier  = var.azure.app_gateway_sku_tier
  capacity  = var.azure.app_gateway_capacity

  frontend_port     = var.azure.frontend_port
  backend_port      = var.azure.backend_port
  backend_protocol  = var.azure.backend_protocol
  listener_protocol = var.azure.listener_protocol

  application_target_groups = var.azure.application_target_groups
  application_paths         = var.azure.application_paths

  health_check_path     = var.azure.health_check_path
  health_check_interval = var.azure.health_check_interval
  health_check_timeout  = var.azure.health_check_timeout
  unhealthy_threshold   = var.azure.unhealthy_threshold

  environment       = var.azure.tags.Environment
  module_name       = "azure-app-gateway"
  terraform_managed = "true"
  additional_tags   = merge(var.azure.tags, var.additional_tags)
}

# Azure VM Module (VM-based deployment)
module "azure_vm" {
  source = "./azure-modules/vm"

  resource_group_name = module.azure_vnet.resource_group_name
  location            = var.azure.location
  subnet_id           = module.azure_vnet.public_subnet_ids[0]

  vm_size        = var.azure.vm_size
  admin_username = var.azure.admin_username
  ssh_key_name   = var.azure.ssh_key_name
  ssh_public_key = var.azure.ssh_public_key

  application = var.azure.vm_application

  environment       = var.azure.tags.Environment
  module_name       = "azure-vm"
  terraform_managed = "true"
  additional_tags   = merge(var.azure.tags, var.additional_tags)
}

*/
