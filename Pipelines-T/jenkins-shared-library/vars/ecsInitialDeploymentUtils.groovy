// vars/ecsInitialDeploymentUtils.groovy

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

// Helper function to parse JSON
@NonCPS
def initialDeploymentParseJson(String jsonText) {
    return new JsonSlurper().parseText(jsonText)
}

// Helper function to update task definition
@NonCPS
def initialDeploymentUpdateTaskDef(String jsonText, String imageUri) {
    def taskDef = new JsonSlurper().parseText(jsonText)
    taskDef.remove('taskDefinitionArn')
    taskDef.remove('revision')
    taskDef.remove('status')
    taskDef.remove('requiresAttributes')
    taskDef.remove('compatibilities')
    taskDef.remove('registeredAt')
    taskDef.remove('registeredBy')
    taskDef.remove('deregisteredAt')
    taskDef.containerDefinitions[0].image = imageUri
    return JsonOutput.prettyPrint(JsonOutput.toJson(taskDef))
}

def deployToBlueService(Map config) {
    // Get app name from config or default to app_1
    def appName = config.appName ?: "app_1"
    def appSuffix = appName.replace("app_", "")
    
    echo "🚀 Deploying initial application ${appName} to Blue Service..."

    try {
        // Get ECR repository URI
        def ecrUri = sh(
            script: "aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${config.awsRegion} --query 'repositories[0].repositoryUri' --output text",
            returnStdout: true
        ).trim()
        
        // Smart path handling for Docker directory
        def tfDir = config.tfWorkingDirECS ?: env.WORKSPACE
        def dockerDir = "${tfDir}/modules/aws/ecs/scripts"
        
        // Handle case where tfDir already includes blue-green-deployment
        if (tfDir.endsWith('/blue-green-deployment')) {
            dockerDir = "${tfDir}/modules/aws/ecs/scripts"
        }
        
        // Build and push Docker image for the specified app with app_*-latest tag
        sh """
            # Authenticate Docker to ECR
            aws ecr get-login-password --region ${config.awsRegion} | docker login --username AWS --password-stdin ${ecrUri}
            
            # Navigate to the directory with Dockerfile
            cd ${dockerDir}
            
            # Make sure we're using the right app file
            cp app_${appSuffix}.py app.py
            
            # Build the Docker image
            docker build -t ${config.ecrRepoName} --build-arg APP_NAME=${appSuffix} .
            
            # Tag the image with app-specific latest tag
            docker tag ${config.ecrRepoName}:latest ${ecrUri}:${appName}-latest
            
            # Push the app-specific latest tag
            docker push ${ecrUri}:${appName}-latest
        """
        
        // Get services JSON and find blue service
        def servicesJson = sh(
            script: "aws ecs list-services --cluster blue-green-cluster --region ${config.awsRegion} --output json",
            returnStdout: true
        ).trim()
        
        def serviceArns = initialDeploymentParseJson(servicesJson).serviceArns
        
        // Look for app-specific blue service with exact naming pattern: app1-blue-service
        def blueServiceName = "app${appSuffix}-blue-service"
        def blueServiceArn = serviceArns.find { it.toLowerCase().contains(blueServiceName.toLowerCase()) }
        
        if (!blueServiceArn) {
            echo "⚠️ Could not find service ${blueServiceName}. Listing all services:"
            sh "aws ecs list-services --cluster blue-green-cluster --query 'serviceArns[*]' --output table"
            error "❌ Could not find blue service in cluster blue-green-cluster"
        }
        
        def blueService = blueServiceArn.tokenize('/').last()
        echo "Found blue service: ${blueService}"
        
        // Get task definition ARN
        def taskDefArn = sh(
            script: """
            aws ecs describe-services --cluster blue-green-cluster --services "${blueService}" --region ${config.awsRegion} --query 'services[0].taskDefinition' --output text
            """,
            returnStdout: true
        ).trim()
        
        echo "Found task definition ARN: ${taskDefArn}"
        
        // Get task definition JSON
        def taskDefJsonText = sh(
            script: """
            aws ecs describe-task-definition --task-definition "${taskDefArn}" --region ${config.awsRegion} --query 'taskDefinition' --output json
            """,
            returnStdout: true
        ).trim()
        
        // Update task definition with new image
        def newTaskDefJson = initialDeploymentUpdateTaskDef(taskDefJsonText, "${ecrUri}:${appName}-latest")
        writeFile file: "initial-task-def-${appSuffix}.json", text: newTaskDefJson
        
        def newTaskDefArn = sh(
            script: "aws ecs register-task-definition --cli-input-json file://initial-task-def-${appSuffix}.json --region ${config.awsRegion} --query 'taskDefinition.taskDefinitionArn' --output text",
            returnStdout: true
        ).trim()
        
        echo "Registered new task definition: ${newTaskDefArn}"
        
        // Update service with new task definition
        sh """
        aws ecs update-service \\
            --cluster blue-green-cluster \\
            --service "${blueService}" \\
            --task-definition "${newTaskDefArn}" \\
            --desired-count 1 \\
            --force-new-deployment \\
            --region ${config.awsRegion}
        """
        
        // Use the exact target group naming pattern you specified
        def blueTgName = "blue-tg-app${appSuffix}"
        
        echo "Looking for target group: ${blueTgName}"
        def blueTgArn = sh(
            script: "aws elbv2 describe-target-groups --names ${blueTgName} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        if (!blueTgArn || blueTgArn == "None") {
            echo "⚠️ Could not find target group ${blueTgName}. Listing all target groups:"
            sh "aws elbv2 describe-target-groups --query 'TargetGroups[*].TargetGroupName' --output table"
            error "❌ Could not find blue target group ${blueTgName}"
        }
        
        echo "Found target group: ${blueTgName}"
        
        // Get ALB and listener ARNs directly into variables
        def albArn = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
            returnStdout: true
        ).trim()
        
        def listenerArn = sh(
            script: "aws elbv2 describe-listeners --load-balancer-arn ${albArn} --query 'Listeners[?Port==`80`].ListenerArn' --output text",
            returnStdout: true
        ).trim()
        
        // For all apps, use path-based rules and keep the welcome message as default action
        // Check if a rule for this path pattern already exists
        def pathPattern = "/app${appSuffix}*"
        def existingRule = sh(
            script: "aws elbv2 describe-rules --listener-arn ${listenerArn} --query \"Rules[?contains(to_string(Conditions[?Field=='path-pattern'].Values[]), '${pathPattern}')].RuleArn\" --output text",
            returnStdout: true
        ).trim()
        
        if (existingRule && existingRule != "None") {
            // Rule exists, modify it to point to the blue target group
            sh """
            aws elbv2 modify-rule \\
                --rule-arn ${existingRule} \\
                --actions '[{"Type":"forward","TargetGroupArn":"${blueTgArn}"}]'
            """
            echo "Modified existing rule for path pattern ${pathPattern} to point to ${blueTgArn}"
        } else {
            // Rule doesn't exist, create a new one
            def usedPriorities = sh(
                script: "aws elbv2 describe-rules --listener-arn ${listenerArn} --query 'Rules[?Priority!=`default`].Priority' --output json",
                returnStdout: true
            ).trim()
            
            def prioritiesJson = initialDeploymentParseJson(usedPriorities)
            def priority = 50  // Start with a lower priority for app routing
            
            // Find the first available priority
            while (prioritiesJson.contains(priority.toString())) {
                priority++
            }
            
            // Create path-based rule for this app
            sh """
            aws elbv2 create-rule \\
                --listener-arn ${listenerArn} \\
                --priority ${priority} \\
                --conditions '[{"Field":"path-pattern","Values":["${pathPattern}"]}]' \\
                --actions '[{"Type":"forward","TargetGroupArn":"${blueTgArn}"}]'
            """
            echo "Created path-based rule with priority ${priority} for app${appSuffix}"
        }
        
        // Wait for service to stabilize
        sh "aws ecs wait services-stable --cluster blue-green-cluster --services \"${blueService}\" --region ${config.awsRegion}"
        
        // Get ALB DNS name to display in the output
        def albDns = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].DNSName' --output text",
            returnStdout: true
        ).trim()
        
        // Display access information
        if (appName == "app_1") {
            echo "✅ Initial deployment of ${appName} completed successfully!"
            echo "🌐 Application is accessible at: http://${albDns}/"
        } else {
            echo "✅ Initial deployment of ${appName} completed successfully!"
            echo "🌐 Application is accessible at: http://${albDns}/app${appSuffix}/"
        }
        
    } catch (Exception e) {
        echo "❌ Initial deployment failed: ${e.message}"
        throw e
    }
}
