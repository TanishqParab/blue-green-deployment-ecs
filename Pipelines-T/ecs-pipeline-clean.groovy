// ecs-pipeline.groovy - Unified ECS pipeline for apply, switch, and rollback operations

@Library('jenkins-shared-library-temp1') _

pipeline {
    agent any
    
    parameters {
        choice(
            name: 'OPERATION',
            choices: ['APPLY', 'SWITCH', 'ROLLBACK'],
            description: 'Select the operation to perform: APPLY (deploy infrastructure), SWITCH (update and switch traffic), or ROLLBACK'
        )
        choice(
            name: 'MANUAL_BUILD', 
            choices: ['YES', 'DESTROY', 'NO'], 
            description: 'YES: Run Terraform, DESTROY: Destroy Infra, NO: Auto Deploy App Changes'
        )
        choice(
            name: 'INITIAL_DEPLOYMENT',
            choices: ['NO', 'YES'],
            description: 'YES: Deploy initial application to Blue service after infrastructure is created'
        )
        choice(
            name: 'CONFIRM_ROLLBACK',
            choices: ['NO', 'YES'],
            description: 'Confirm you want to rollback to previous version?'
        )
        choice(
            name: 'APP_NAME',
            choices: ['app_1', 'app_2', 'app_3', 'all'],
            description: 'Select which application to deploy/rollback (app_1 is the default app, "all" deploys all apps)'
        )
    }
    
    triggers {
        githubPush()
    }
    
    environment {
        IMPLEMENTATION = 'ecs'
    }
    
    stages {
        stage('Determine Operation') {
            steps {
                script {
                    def operation = params.OPERATION ?: 'APPLY'
                    def isGitHubPush = false
        
                    def causes = currentBuild.getBuildCauses()
                    causes.each { cause ->
                        if (cause._class?.contains('SCMTrigger') || 
                            cause._class?.contains('GitHubPush') || 
                            cause.shortDescription?.contains('push') || 
                            cause.shortDescription?.contains('SCM')) {
                            isGitHubPush = true
                        }
                    }
        
                    if (isGitHubPush) {
                        echo "🚀 GitHub push detected - automatically using SWITCH operation"
                        operation = 'SWITCH'
        
                        def config = deploymentVars()
                        checkout([$class: 'GitSCM',
                            branches: [[name: "*/${config.repoBranch}"]],
                            extensions: [],
                            userRemoteConfigs: [[
                                url: config.repoUrl,
                                credentialsId: config.repoCredentialsId
                            ]]
                        ])
        
                        def changedFiles = sh(script: "git diff --name-only HEAD~1 HEAD", returnStdout: true).trim()
                        echo "🔍 Changed files: ${changedFiles}"
        
                        if (changedFiles.contains("app_1.py")) {
                            env.APP_NAME = "app_1"
                            echo "🟢 Detected changes in app_1.py"
                        } else if (changedFiles.contains("app_2.py")) {
                            env.APP_NAME = "app_2"
                            echo "🟡 Detected changes in app_2.py"
                        } else if (changedFiles.contains("app_3.py")) {
                            env.APP_NAME = "app_3"
                            echo "🔵 Detected changes in app_3.py"
                        } else {
                            env.APP_NAME = "all"
                            echo "⚪ No specific app file changed. Defaulting to all"
                        }
                    } else {
                        echo "🔧 Manual/Parameter build detected - using selected operation: ${operation}"
                        env.APP_NAME = params.APP_NAME
                    }
        
                    env.SELECTED_OPERATION = operation
                    env.IMPLEMENTATION = 'ecs'
                    env.SELECTED_IMPLEMENTATION = 'ecs'
                    echo "DEBUG: Forced implementation to ECS"
                }
            }
        }
        
        stage('Execute Apply') {
            when {
                expression { env.SELECTED_OPERATION == 'APPLY' }
            }
            steps {
                script {
                    def config = deploymentVars()
                    config.implementation = 'ecs'
                    config.tfWorkingDir = config.tfWorkingDirECS
                    
                    echo "DEBUG: Config implementation: ${config.implementation}"
                    
                    basePipelineImpl.initialize(config)
                    basePipelineImpl.checkout(config)
                    
                    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
                        echo "DEBUG: Before terraform - Implementation: ${config.implementation}"
                        config.implementation = 'ecs'
                        echo "DEBUG: Fixing Terraform variable format"
                        terraformInit(config)
                        
                        dir(config.tfWorkingDir) {
                            sh "terraform plan -out=tfplan"
                        }
                        
                        approvals.terraformApplyApproval(config)
                        
                        dir(config.tfWorkingDir) {
                            sh "terraform apply -auto-approve tfplan"
                            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
                        }
                    }
                    
                    if (params.MANUAL_BUILD == 'DESTROY') {
                        approvals.terraformDestroyApproval(config)
                        ecsUtils.cleanResources(config)
                        terraformDestroy(config)
                    }
                }
            }
        }

        stage('Execute Initial Deployment') {
            when {
                allOf {
                    expression { env.SELECTED_OPERATION == 'APPLY' }
                    expression { params.INITIAL_DEPLOYMENT == 'YES' }
                    expression { params.MANUAL_BUILD != 'DESTROY' }
                }
            }
            steps {
                script {
                    def config = deploymentVars()
                    config.implementation = 'ecs'
                    config.tfWorkingDir = config.tfWorkingDirECS
                    
                    echo "🚀 Executing initial deployment for ECS..."
                    
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Deploying initial application: ${appName}"
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            ecsInitialDeploymentImpl.deployInitialApplication(appConfig)
                        }
                    } else {
                        config.appName = params.APP_NAME
                        ecsInitialDeploymentImpl.deployInitialApplication(config)
                    }
                }
            }
        }
        
        stage('Execute Switch') {
            when {
                expression { env.SELECTED_OPERATION == 'SWITCH' }
            }
            steps {
                script {
                    def config = deploymentVars()
                    config.implementation = 'ecs'
                    config.tfWorkingDir = config.tfWorkingDirECS
                    
                    def changedFiles = []
                    try {
                        def gitDiff = sh(
                            script: "git diff --name-only HEAD~1 HEAD",
                            returnStdout: true
                        ).trim()
                        
                        if (gitDiff) {
                            changedFiles = gitDiff.split('\n')
                            echo "📝 Changed files: ${changedFiles.join(', ')}"
                        }
                    } catch (Exception e) {
                        echo "⚠️ Could not determine changed files: ${e.message}"
                    }
                    
                    def hasAppSpecificChanges = { appName ->
                        if (changedFiles.isEmpty()) return true
                        
                        def appSuffix = appName.replace("app_", "")
                        for (def file : changedFiles) {
                            if (file.contains("app_${appSuffix}.py") || 
                                file.contains("app${appSuffix}.py") || 
                                file.contains("app${appSuffix}/") ||
                                (appSuffix == "1" && file.contains("app.py"))) {
                                return true
                            }
                        }
                        return false
                    }
                    
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Switching application: ${appName}"
                            
                            if (!hasAppSpecificChanges(appName)) {
                                echo "📄 No changes detected for ${appName}. Skipping deployment."
                                return
                            }
                            
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            
                            switchPipelineImpl.initialize(appConfig)
                            switchPipelineImpl.checkout(appConfig)
                            switchPipelineImpl.detectChanges(appConfig)
                            
                            if (env.DEPLOY_NEW_VERSION == 'true') {
                                echo "🚀 Deploying changes for ${appName}"
                                switchPipelineImpl.fetchResources(appConfig)
                                switchPipelineImpl.ensureTargetGroupAssociation(appConfig)
                                switchPipelineImpl.updateApplication(appConfig)
                                switchPipelineImpl.testEnvironment(appConfig)
                                switchPipelineImpl.manualApprovalBeforeSwitchTrafficECS(appConfig)
                                switchPipelineImpl.switchTraffic(appConfig)
                                switchPipelineImpl.postSwitchActions(appConfig)
                            }
                        }
                    } else {
                        config.appName = params.APP_NAME
                        
                        switchPipelineImpl.initialize(config)
                        switchPipelineImpl.checkout(config)
                        switchPipelineImpl.detectChanges(config)
                        
                        if (env.DEPLOY_NEW_VERSION == 'true') {
                            switchPipelineImpl.fetchResources(config)
                            switchPipelineImpl.ensureTargetGroupAssociation(config)
                            switchPipelineImpl.updateApplication(config)
                            switchPipelineImpl.testEnvironment(config)
                            switchPipelineImpl.manualApprovalBeforeSwitchTrafficECS(config)
                            switchPipelineImpl.switchTraffic(config)
                            switchPipelineImpl.postSwitchActions(config)
                        }
                    }
                }
            }
        }
        
        stage('Execute Rollback') {
            when {
                expression { env.SELECTED_OPERATION == 'ROLLBACK' }
            }
            steps {
                script {
                    def config = deploymentVars()
                    config.implementation = 'ecs'
                    config.tfWorkingDir = config.tfWorkingDirECS
                    
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Rolling back application: ${appName}"
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            
                            rollbackPipelineImpl.initialize(appConfig)
                            rollbackPipelineImpl.fetchResources(appConfig)
                            rollbackPipelineImpl.prepareRollback(appConfig)
                            rollbackPipelineImpl.testRollbackEnvironment(appConfig)
                            rollbackPipelineImpl.manualApprovalBeforeRollbackECS(appConfig)
                            rollbackPipelineImpl.executeRollback(appConfig)
                            rollbackPipelineImpl.postRollbackActions(appConfig)
                        }
                    } else {
                        config.appName = params.APP_NAME
                        
                        rollbackPipelineImpl.initialize(config)
                        rollbackPipelineImpl.fetchResources(config)
                        rollbackPipelineImpl.prepareRollback(config)
                        rollbackPipelineImpl.testRollbackEnvironment(config)
                        rollbackPipelineImpl.manualApprovalBeforeRollbackECS(config)
                        rollbackPipelineImpl.executeRollback(config)
                        rollbackPipelineImpl.postRollbackActions(config)
                    }
                }
            }
        }
    }
}