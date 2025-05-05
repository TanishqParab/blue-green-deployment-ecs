variable "repository_name" {
  description = "Name of the ECR repository"
  type        = string
}

variable "app_py_path" {
  description = "Path to the app.py file"
  type        = string
}

variable "dockerfile_path" {
  description = "Path to the Dockerfile"
  type        = string
}

variable "aws_region" {
  description = "AWS region"
  type        = string
}

variable "image_name" {
  description = "Name for the Docker image"
  type        = string
  default     = "blue-green-app"
}

variable "skip_docker_build" {
  description = "Whether to skip Docker build and push"
  type        = bool
  default     = false
}
