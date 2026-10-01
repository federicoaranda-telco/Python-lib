def call(Map params = [:]) {

    // Set additional environment variables
    params.each { key, value ->
        env[key] = value
    }

    // Now calculate PATH_APP after other vars are set
    env.PATH_APP = "${env.LANG_APP}_${env.PROJECT_APP}"
    env.REPO_IMAGE = "registry.gitlab.com/telco-${GITLAB_GROUP}/${PATH_APP}/${DIR_ENV}:0.${BUILD_NUMBER}"
    env.S3_ENV = "mc cp minio/env/${PROJECT_ENVIRONMENT}/${env.PATH_APP}"
    env.GITLAB_REGISTRY_MAX_TAGS = env.GITLAB_REGISTRY_MAX_TAGS ?: '10'
    env.REMOTE_MIN_DOCKER_IMAGES_TO_KEEP = env.REMOTE_MIN_DOCKER_IMAGES_TO_KEEP ?: '3'
    env.REMOTE_DISK_USAGE_THRESHOLD_PERCENT = env.REMOTE_DISK_USAGE_THRESHOLD_PERCENT ?: '75'
    env.AGENT_CLEANUP_LOG_MAX_FILES = env.AGENT_CLEANUP_LOG_MAX_FILES ?: '20'

    pipeline {
        agent any
        stages {
            stage('Ensure GitLab Repo') {
                steps {
                    script {
                        runStep('gitlabRepoSetup') {
                            gitlabRepoSetup {
                                if (env.CERTS == 'YES') { copyConfEnv.gonewcerts() } else { copyConfEnv.gonew() }
                            }
                        }
                    }
                }
            }
            stage('Clone Repo') {
                steps {
                    script {
                        runStep('cloneRepo') { cloneRepo() }
                        if (env.SLACK_NOTIFY == 'TRUE') {
                            notifySlack.environmentChannel("${PROJECT_ENVIRONMENT}")
                            commonFunction.notifySlackQube('STARTED')
                        } else {
                            echo 'Slack Notification Disabled'
                        }
                    }
                }
            }
            stage('Copy Conf/Env') {
                steps {
                    script {
                        //Depending on lang for the project: (copyConfEnv.go()->go, copyConfEnv.vite()->vite, copyConfEnv.laravel()->laravel)
                        if (env.CERTS == 'YES') {
                            runStep('copyConfEnv.gonewcerts') { copyConfEnv.gonewcerts() }
                        } else {
                            runStep('copyConfEnv.gonew') { copyConfEnv.gonew() }
                        }
                    }
                }
            }
            stage('Build Image') {
                steps {
                    script {
                        runStep('buildImage') { buildImage() }
                    }
                }
            }
            stage('Test') {
                steps {
                    script {
                        //runStep('sonarQubeAnalisis') { sonarQubeAnalisis() }
                    }
                }
            }
            stage('Scan Image') {
                steps {
                    script {
                        runStep('trivyScan') { trivyScan() }
                    }
                }
            }
            stage('Push Image') {
                steps {
                    script {
                        runStep('pushImage') { pushImage() }
                    }
                }
            }
            stage('Deploy') {
                steps {
                    script {
                        runStep('deploy.go') { deploy.go("${PATH_APP}") }
                    }
                }
            }
        }
        post {
            always {
                script {
                    commonFunction.notifySlackQube(currentBuild.result)
                }
            }
        }
    }
}
