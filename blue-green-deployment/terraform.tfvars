aws_region          = "us-east-1"
vpc_cidr            = "10.0.0.0/16"
public_subnet_cidrs = ["10.0.1.0/24", "10.0.2.0/24"]
availability_zones  = ["us-east-1a", "us-east-1b"]

# ECS Specific Variables
ecs_cluster_name    = "blue-green-cluster"
task_family         = "blue-green-task"
task_role_arn       = "arn:aws:iam::680549841444:role/ecs-task-execution-role"
cpu                 = "256"
memory              = "512"
container_name      = "blue-green-container"
container_port      = 80
desired_count       = 1
ecs_task_definition = "blue-green-task-def"

# Health Check Settings
health_check_path     = "/health"
health_check_interval = 30
health_check_timeout  = 10
healthy_threshold     = 3
unhealthy_threshold   = 2



