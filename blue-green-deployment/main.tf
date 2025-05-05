provider "aws" {
  region = var.aws_region
}

module "vpc" {
  source              = "./modules/vpc"
  vpc_cidr            = var.vpc_cidr
  public_subnet_cidrs = var.public_subnet_cidrs
  availability_zones  = var.availability_zones
}

module "security_group" {
  source = "./modules/security_group"
  vpc_id = module.vpc.vpc_id
}



module "alb" {
  source                = "./modules/alb"
  vpc_id                = module.vpc.vpc_id
  public_subnet_ids     = module.vpc.public_subnet_ids
  ecs_security_group_id = module.security_group.ecs_security_group_id
  listener_port         = var.listener_port
  health_check_path     = var.health_check_path
  health_check_interval = var.health_check_interval
  health_check_timeout  = var.health_check_timeout
  healthy_threshold     = var.healthy_threshold
  unhealthy_threshold   = var.unhealthy_threshold
}

module "ecr" {
  source            = "./modules/ecr"
  repository_name   = "blue-green-app"
  app_py_path       = "${path.module}/modules/ecs/scripts/app.py"
  dockerfile_path   = "${path.module}/modules/ecs/scripts/docker"
  aws_region        = var.aws_region
  skip_docker_build = false
}

module "ecs" {
  source                 = "./modules/ecs"
  ecs_cluster_name       = var.ecs_cluster_name
  task_family            = var.task_family
  task_role_arn          = var.task_role_arn
  cpu                    = var.cpu
  memory                 = var.memory
  container_name         = var.container_name
  container_image        = module.ecr.image_url
  container_port         = var.container_port
  desired_count          = var.desired_count
  public_subnet_ids      = module.vpc.public_subnet_ids
  ecs_security_group_id  = module.security_group.ecs_security_group_id
  blue_target_group_arn  = module.alb.blue_target_group_arn
  green_target_group_arn = module.alb.green_target_group_arn
  ecs_task_definition    = var.ecs_task_definition

  depends_on = [module.alb, module.ecr]
}


