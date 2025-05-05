resource "aws_lb" "main" {
  name               = "blue-green-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.ecs_security_group_id]
  subnets            = var.public_subnet_ids

  tags = {
    Name = "Blue-Green ALB"
  }
}

resource "aws_lb_target_group" "blue" {
  name        = "blue-tg"
  port        = 5000
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # For ECS tasks

  health_check {
    path                = var.health_check_path
    interval            = var.health_check_interval
    timeout             = var.health_check_timeout
    healthy_threshold   = var.healthy_threshold
    unhealthy_threshold = var.unhealthy_threshold
    matcher             = "200"
  }

  tags = {
    Name = "Blue Target Group"
  }
}

resource "aws_lb_target_group" "green" {
  name        = "green-tg"
  port        = 5000
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # For ECS tasks

  health_check {
    path                = var.health_check_path
    interval            = var.health_check_interval
    timeout             = var.health_check_timeout
    healthy_threshold   = var.healthy_threshold
    unhealthy_threshold = var.unhealthy_threshold
    matcher             = "200"
  }

  tags = {
    Name = "Green Target Group"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = var.listener_port
  protocol          = "HTTP"

  default_action {
    type = "forward"
    forward {
      target_group {
        arn    = aws_lb_target_group.blue.arn
        weight = 100 # Initially, all traffic goes to Blue
      }
      target_group {
        arn    = aws_lb_target_group.green.arn
        weight = 0 # No traffic to Green initially
      }
      stickiness {
        enabled  = true
        duration = 300
      }
    }
  }
}
