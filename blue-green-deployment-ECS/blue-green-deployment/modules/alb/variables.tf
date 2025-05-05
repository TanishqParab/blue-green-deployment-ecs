variable "vpc_id" {
  description = "VPC ID where the ALB will be created"
  type        = string
}

variable "public_subnet_ids" {
  description = "List of subnet IDs for the ALB"
  type        = list(string)
}

variable "ecs_security_group_id" {
  description = "Security group ID to associate with ECS tasks"
  type        = string
}

variable "listener_port" {
  description = "Port for the ALB listener"
  type        = number
  default     = 80
}

variable "health_check_path" {
  description = "The health check path for the target groups"
  type        = string
  default     = "/health"
}

variable "health_check_interval" {
  description = "The interval (in seconds) between health checks"
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "The timeout (in seconds) for each health check"
  type        = number
  default     = 10
}

variable "healthy_threshold" {
  description = "The number of successful health checks required to mark a target as healthy"
  type        = number
  default     = 3
}

variable "unhealthy_threshold" {
  description = "The number of failed health checks required to mark a target as unhealthy"
  type        = number
  default     = 2
}
