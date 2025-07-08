// ec2-pipeline.groovy - Unified EC2 pipeline for apply, switch, and rollback operations

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
            name: 'CONFIRM_ROLLBACK',
            choices: ['NO', 'YES'],
            description: 'Confirm you want to rollback to previous version?'
        )
        string(
            name: 'APP_NAME',
            defaultValue: '',
            description: 'Application name for multi-app deployment (e.g., app1, app2, app3). Leave empty for default app.'
        )
    }
    
    triggers {
        githubPush()
    }
    
    environment {
        IMPLEMENTATION = 'ec2'
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
                        echo "Build triggered by GitHub push - automatically using SWITCH operation"
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
                        echo "Changed files: ${changedFiles}"
                        
                        if (changedFiles.contains("app_1.py")) {
                            env.APP_NAME = "app1"
                        } else if (changedFiles.contains("app_2.py")) {
                            env.APP_NAME = "app2"
                        } else if (changedFiles.contains("app_3.py")) {
                            env.APP_NAME = "app3"
                        } else if (changedFiles.contains("app.py")) {
                            env.APP_NAME = ""
                        }
                    } else {
                        env.APP_NAME = params.APP_NAME
                    }
                    
                    env.SELECTED_OPERATION = operation
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
                    config.implementation = 'ec2'
                    config.tfWorkingDir = config.tfWorkingDirEC2
                    config.appName = env.APP_NAME
                    
                    basePipelineImpl.initialize(config)
                    basePipelineImpl.checkout(config)
                    
                    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
                        terraformInit(config)
                        dir(config.tfWorkingDir) {
                            sh "terraform plan -out=tfplan"
                        }
                        approvals.terraformApplyApproval(config)
                        terraformApply(config)
                    }
                    
                    if (params.MANUAL_BUILD != 'DESTROY') {
                        if (!env.APP_NAME || env.APP_NAME.trim() == '') {
                            def appNames = ["app1", "app2", "app3"]
                            appNames.each { appName ->
                                def appConfig = config.clone()
                                appConfig.appName = appName
                                try {
                                    ec2Utils.registerInstancesToTargetGroups(appConfig)
                                } catch (Exception e) {
                                    echo "⚠️ Warning: Could not register instances for ${appName}: ${e.message}"
                                }
                            }
                        } else {
                            try {
                                ec2Utils.registerInstancesToTargetGroups(config)
                            } catch (Exception e) {
                                echo "⚠️ Warning: Could not register instances for ${env.APP_NAME}: ${e.message}"
                            }
                        }
                    }
                    
                    if (params.MANUAL_BUILD == 'DESTROY') {
                        approvals.terraformDestroyApproval(config)
                        terraformDestroy(config)
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
                    config.implementation = 'ec2'
                    config.tfWorkingDir = config.tfWorkingDirEC2
                    config.appName = env.APP_NAME
                    
                    switchPipelineImpl.initialize(config)
                    switchPipelineImpl.detectChanges(config)
                    
                    if (env.EXECUTION_TYPE == 'APP_DEPLOY') {
                        switchPipelineImpl.checkout(config)
                        switchPipelineImpl.fetchResources(config)
                        switchPipelineImpl.manualApprovalBeforeSwitchTrafficEC2(config)
                        switchPipelineImpl.updateApplication(config)
                        switchPipelineImpl.deployToBlueEC2Instance(config)
                        switchPipelineImpl.switchTraffic(config)
                        switchPipelineImpl.postSwitchActions(config)
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
                    config.implementation = 'ec2'
                    config.tfWorkingDir = config.tfWorkingDirEC2
                    config.appName = env.APP_NAME
                    
                    rollbackPipelineImpl.initialize(config)
                    rollbackPipelineImpl.checkout(config)
                    rollbackPipelineImpl.fetchResources(config)
                    rollbackPipelineImpl.manualApprovalBeforeRollbackEC2(config)
                    rollbackPipelineImpl.prepareRollback(config)
                    rollbackPipelineImpl.executeRollback(config)
                    rollbackPipelineImpl.postRollbackActions(config)
                }
            }
        }
    }
}