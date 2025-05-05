variable "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  type        = string
}

variable "task_family" {
  description = "Family name for the task definition"
  type        = string
}

variable "task_role_arn" {
  description = "ARN of the IAM role for the task"
  type        = string
}

variable "cpu" {
  description = "CPU units for the task"
  type        = string
}

variable "memory" {
  description = "Memory for the task in MB"
  type        = string
}

variable "container_name" {
  description = "Name of the container"
  type        = string
}

variable "container_image" {
  description = "Docker image for the container"
  type        = string
}

variable "container_port" {
  description = "Port exposed by the container"
  type        = number
}

variable "desired_count" {
  description = "Desired number of tasks"
  type        = number
}

variable "public_subnet_ids" {
  description = "List of public subnet IDs"
  type        = list(string)
}

variable "ecs_security_group_id" {
  description = "Security group ID to associate with ECS tasks"
  type        = string
}

variable "blue_target_group_arn" {
  description = "ARN of the Blue target group"
  type        = string
}

variable "green_target_group_arn" {
  description = "ARN of the Green target group"
  type        = string
}

variable "ecs_task_definition" {
  description = "The ECS task definition for Fargate"
  type        = string
}
