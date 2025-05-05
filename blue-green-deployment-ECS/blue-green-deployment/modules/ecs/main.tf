resource "aws_ecs_cluster" "blue_green_cluster" {
  name = var.ecs_cluster_name

  tags = {
    Name = var.ecs_cluster_name
  }
}

resource "aws_iam_role" "ecs_task_execution_role" {
  name = "ecs-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_policy" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_ecs_task_definition" "blue_task" {
  family                   = var.task_family
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = var.task_role_arn
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.cpu
  memory                   = var.memory

  container_definitions = jsonencode([{
    name      = var.container_name
    image     = var.container_image
    cpu       = tonumber(var.cpu)
    memory    = tonumber(var.memory)
    essential = true
    portMappings = [{
      containerPort = var.container_port
      hostPort      = var.container_port
      protocol      = "tcp"
    }]
  }])

  tags = {
    Name = "${var.task_family}-blue"
  }
}

resource "aws_ecs_service" "blue_service" {
  name            = "blue-service"
  cluster         = aws_ecs_cluster.blue_green_cluster.id
  task_definition = aws_ecs_task_definition.blue_task.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [var.ecs_security_group_id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = var.blue_target_group_arn
    container_name   = var.container_name
    container_port   = var.container_port
  }

  tags = {
    Name = "Blue Service"
  }
}

resource "aws_ecs_task_definition" "green_task" {
  family                   = "${var.task_family}-green"
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = var.task_role_arn
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.cpu
  memory                   = var.memory

  container_definitions = jsonencode([{
    name      = "${var.container_name}-green"
    image     = var.container_image
    cpu       = tonumber(var.cpu)
    memory    = tonumber(var.memory)
    essential = true
    portMappings = [{
      containerPort = var.container_port
      hostPort      = var.container_port
      protocol      = "tcp"
    }]
  }])

  tags = {
    Name = "${var.task_family}-green"
  }
}

resource "aws_ecs_service" "green_service" {
  name            = "green-service"
  cluster         = aws_ecs_cluster.blue_green_cluster.id
  task_definition = aws_ecs_task_definition.green_task.arn
  desired_count   = 0 # Initially set to 0 for green deployment
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [var.ecs_security_group_id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = var.green_target_group_arn
    container_name   = "${var.container_name}-green"
    container_port   = var.container_port
  }

  tags = {
    Name = "Green Service"
  }
}
