def call() {
    sh "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E 'ERROR|expecting|unknown|Undefined|undefined:|no such|overwritten|not exist|ParserError:| error  in |Syntax Error|no space left| invalid character |Failed to connect session for config |mc: <ERROR> Unable to validate source | line ' >> .elogs"
    def logs = readFile('.elogs').trim()
    sh 'rm .elogs'
    return "${logs}"
}
def sonar() {
    sh "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E ' you can find the results at: | Error during SonarScanner execution | can not be reached' >> .sonarlinkextract"
    def sonarlink = readFile('.sonarlinkextract').trim()
    sh 'rm .sonarlinkextract'
    return "${sonarlink}"
}
def sonarif() {
    if (env.FS == 'Test' || env.FS == 'Scan Image' || env.FS == 'Push Image' || env.FS == 'Deploy') {
        sh "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E ' you can find the results at: | Error during SonarScanner execution | can not be reached' >> .sonarlinkextract"
        def sonarlink = readFile('.sonarlinkextract').trim()
        sh 'rm .sonarlinkextract'
        return "${sonarlink}"
    } else {
        return "No Sonar Analisis done on this integration"
    }
}
