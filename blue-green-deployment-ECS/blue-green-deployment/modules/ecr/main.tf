/*
resource "aws_ecr_repository" "app_repo" {
  name                 = var.repository_name
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = var.repository_name
  }
}

# Only create this resource if skip_docker_build is false
resource "null_resource" "docker_build_push" {
  count = var.skip_docker_build ? 0 : 1

  triggers = {
    app_py_sha1        = fileexists(var.app_py_path) ? filesha1(var.app_py_path) : "file-not-found"
    dockerfile_sha1    = fileexists(var.dockerfile_path) ? filesha1(var.dockerfile_path) : "file-not-found"
    ecr_repository_url = aws_ecr_repository.app_repo.repository_url
    always_run         = timestamp()
  }

  provisioner "local-exec" {
    interpreter = ["PowerShell", "-Command"]
    command     = <<-EOT
      # Wait for the ECR repository to be available
      Write-Host "Waiting for ECR repository to be available..."
      $maxRetries = 10
      $retryCount = 0
      $success = $false
      
      do {
        try {
          $result = aws ecr describe-repositories --repository-names ${var.repository_name} --region ${var.aws_region}
          if ($?) {
            $success = $true
            break
          }
        } catch {
          Write-Host "Repository not available yet, retrying in 5 seconds..."
          Start-Sleep -Seconds 5
          $retryCount++
        }
      } while ($retryCount -lt $maxRetries)
      
      if (-not $success) {
        Write-Host "Failed to find ECR repository after $maxRetries attempts. Exiting."
        exit 1
      }
      
      # Now run the build and push script
      ${path.module}/build_and_push.ps1 -AwsRegion ${var.aws_region} -DockerfilePath ${var.dockerfile_path} -ImageName ${var.image_name} -RepositoryUrl "${aws_ecr_repository.app_repo.repository_url}"
    EOT
  }

  depends_on = [aws_ecr_repository.app_repo]
}
*/


/*
resource "aws_ecr_repository" "app_repo" {
  name                 = var.repository_name
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = var.repository_name
  }
}

resource "null_resource" "docker_build_push" {
  triggers = {
    app_py_sha1        = filesha1(var.app_py_path)
    dockerfile_sha1    = filesha1(var.dockerfile_path)
    ecr_repository_url = aws_ecr_repository.app_repo.repository_url
    always_run         = timestamp()
  }

  provisioner "local-exec" {
    interpreter = ["PowerShell", "-Command"]
    command     = <<-EOT
      # Wait for the ECR repository to be available
      Write-Host "Waiting for ECR repository to be available..."
      $maxRetries = 10
      $retryCount = 0
      $success = $false
      
      do {
        try {
          $result = aws ecr describe-repositories --repository-names ${var.repository_name} --region ${var.aws_region}
          if ($?) {
            $success = $true
            break
          }
        } catch {
          Write-Host "Repository not available yet, retrying in 5 seconds..."
          Start-Sleep -Seconds 5
          $retryCount++
        }
      } while ($retryCount -lt $maxRetries)
      
      if (-not $success) {
        Write-Host "Failed to find ECR repository after $maxRetries attempts. Exiting."
        exit 1
      }
      
      # Now run the build and push script
      ${path.module}/build_and_push.ps1 -AwsRegion ${var.aws_region} -DockerfilePath ${var.dockerfile_path} -ImageName ${var.image_name} -RepositoryUrl "${aws_ecr_repository.app_repo.repository_url}"
    EOT
  }

  depends_on = [aws_ecr_repository.app_repo]
}
*/




resource "aws_ecr_repository" "app_repo" {
  name                 = var.repository_name
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = var.repository_name
  }
}

resource "null_resource" "docker_build_push" {
  triggers = {
    app_py_sha1        = filesha1(var.app_py_path)
    dockerfile_sha1    = filesha1(var.dockerfile_path)
    ecr_repository_url = aws_ecr_repository.app_repo.repository_url
    always_run         = timestamp() # This will cause it to run on every apply
  }

  provisioner "local-exec" {
    command = <<-EOT
      # Authenticate Docker to ECR
      aws ecr get-login-password --region ${var.aws_region} | docker login --username AWS --password-stdin ${aws_ecr_repository.app_repo.repository_url}
      
      # Navigate to the directory with Dockerfile
      cd ${dirname(var.dockerfile_path)}
      
      # Build the Docker image
      docker build -t ${var.image_name} .
      
      # Tag the image
      docker tag ${var.image_name}:latest ${aws_ecr_repository.app_repo.repository_url}:latest
      
      # Push the image
      docker push ${aws_ecr_repository.app_repo.repository_url}:latest
    EOT
  }

  depends_on = [aws_ecr_repository.app_repo]
}


