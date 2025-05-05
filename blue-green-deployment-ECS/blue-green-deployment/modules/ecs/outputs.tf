output "ecs_cluster_id" {
  description = "ID of the ECS cluster"
  value       = aws_ecs_cluster.blue_green_cluster.id
}

output "blue_task_definition_arn" {
  description = "ARN of the Blue task definition"
  value       = aws_ecs_task_definition.blue_task.arn
}

output "green_task_definition_arn" {
  description = "ARN of the Green task definition"
  value       = aws_ecs_task_definition.green_task.arn
}

output "blue_service_name" {
  description = "Name of the Blue service"
  value       = aws_ecs_service.blue_service.name
}

output "green_service_name" {
  description = "Name of the Green service"
  value       = aws_ecs_service.green_service.name
}
