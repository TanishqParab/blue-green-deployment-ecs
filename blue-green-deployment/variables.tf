variable "aws_region" {
  default = "us-east-1"
}

variable "vpc_cidr" {
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "availability_zones" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
}

variable "listener_port" {
  type    = number
  default = 80
}

variable "health_check_path" {
  description = "The health check path for the target groups."
  type        = string
  default     = "/health"
}

variable "health_check_interval" {
  description = "The interval (in seconds) between health checks."
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "The timeout (in seconds) for each health check."
  type        = number
  default     = 10
}

variable "healthy_threshold" {
  description = "The number of successful health checks required to mark a target as healthy."
  type        = number
  default     = 3
}

variable "unhealthy_threshold" {
  description = "The number of failed health checks required to mark a target as unhealthy."
  type        = number
  default     = 2
}

variable "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  type        = string
  default     = "blue-green-cluster"
}

variable "task_family" {
  description = "Family name for the task definition"
  type        = string
  default     = "blue-green-task"
}

variable "task_role_arn" {
  description = "ARN of the IAM role for the task"
  type        = string
}

variable "cpu" {
  description = "CPU units for the task"
  type        = string
  default     = "256"
}

variable "memory" {
  description = "Memory for the task in MB"
  type        = string
  default     = "512"
}

variable "container_name" {
  description = "Name of the container"
  type        = string
  default     = "blue-green-container"
}

variable "container_image" {
  description = "Docker image for the container"
  type        = string
  default     = "nginx:latest"
}

variable "container_port" {
  description = "Port exposed by the container"
  type        = number
  default     = 80
}

variable "desired_count" {
  description = "Desired number of tasks"
  type        = number
  default     = 1
}

variable "ecs_task_definition" {
  description = "The ECS task definition for Fargate"
  type        = string
  default     = "blue-green-task-def"
}