output "repository_url" {
  description = "URL of the ECR repository"
  value       = aws_ecr_repository.app_repo.repository_url
}

output "repository_name" {
  description = "Name of the ECR repository"
  value       = aws_ecr_repository.app_repo.name
}

output "image_urls" {
  description = "Map of URLs of the built Docker images"
  value       = { for k, v in var.application : k => "${aws_ecr_repository.app_repo.repository_url}:${k}" }
}

output "repository_urls" {
  description = "Map of URLs of the ECR repositories (for backward compatibility)"
  value       = { for k, v in var.application : k => aws_ecr_repository.app_repo.repository_url }
}
