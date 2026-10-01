def call(){
    sh "echo hello world"
}
def salute(){
    env.FS="${env.STAGE_NAME}"
    checkout changelog: false, 
    scm: [$class: 'GitSCM', 
    branches: [[name: "*/${BRANCH_GIT}"]], 
    extensions: [], 
    userRemoteConfigs: [[credentialsId: "${env.USER_GIT}", url: "${env.REPO_GIT}"]]]
    echo "Etapa : ${env.FS}"
}
def jobErrors(){
    "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E 'ERROR|expecting|unknown|Undefined|undefined:|no such|overwritten|not exist|Failed to connect and initialize SSH connection|ParserError:|redeclared in this block|no space left| line '"
}
def sonarLogs(){
    "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E ' you can find the results at: | Error during SonarScanner execution | can not be reached'"
}
def textTest(){
    "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log"
}
def logsTest(){
    "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E 'ERROR|expecting|unknown|Undefined|undefined:|no such|overwritten|not exist|ParserError:|no space left| line '"
}
def sonarLogsTest(){
    "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E ' you can find the results at: | Error during SonarScanner execution | can not be reached'"
}
def variable(String pathApp,String branch){
    sh "echo testing this: ${pathApp}"
    sh "echo branch: ${branch}"
}
