def call(){
    echo 'false'
}

def notifySlackQube(String buildStatus){
    if (env.SLACK_NOTIFY == "TRUE") {
        buildStatus = buildStatus ?: 'SUCCESS'
        def colorCode = '#4a6985'
        def subject = " Proyecto : ${env.JOB_NAME}\n Estado Build : ${buildStatus}  |  Nro Build : ${env.BUILD_NUMBER}\n Autor Commit : ${gitCommands.commitAuthor()} - ${gitCommands.commitAuthorMail()}"
        def summary = " ${subject}\n Hash Commit : ${gitCommands.commitHash()}\n Mensaje Commit : ${gitCommands.commitMessage()?.split(/[\r\n]+/)?.getAt(0)}\n Modificaciones : ${gitCommands.commitChanged()}"
        switch(buildStatus) {
            case "STARTED":
                colorCode = '#04f8f9'
                slackSend (color: colorCode, message: subject, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
            break
            case "SUCCESS":
                def summarySuccess = "${summary}\n ${sonar()}\n ${trivy()}\n ${cleanupNotices()}\n"
                colorCode = '#09ed46'
                slackSend (color: colorCode, message: summarySuccess, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
            break
            case "UNSTABLE":
                def summaryError = "${subject}\n Etapa : ${env.FS}\n ${failureDetail()}\n ${sonar()}\n ${trivy()}\n ${cleanupNotices()}"
                colorCode = '#f1c232'
                slackSend (color: colorCode, message: summaryError, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
            break
            case "FAILURE":
                def summaryError = "${subject}\n Etapa : ${env.FS}\n ${failureDetail()}\n ${sonar()}\n ${trivy()}\n ${cleanupNotices()}\n"
                colorCode = '#cc0000'
                slackSend (color: colorCode, message: summaryError, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
            break
        }
    }
}
/*
def errors() {
    sh "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E ' ERROR|expecting|unknown|Undefined|undefined:|no such|overwritten|not exist|ParserError:|no space left|declared but not used| line ' >> .errors"
    def logs = readFile('.errors').trim()
    sh 'rm .errors'
    return "${logs}"
}
*/

def errors() {
    def logPath = "${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log"
    def regexErrores = 'ERROR|expecting|unknown|Undefined|undefined:|no such|overwritten|not exist|ParserError:|no space left|declared but not used| line '

    // '|| true' evita que Jenkins aborte si grep no encuentra coincidencias (exit code 1)
    def logs = sh(
        script: "grep -E '${regexErrores}' '${logPath}' || true",
        returnStdout: true
    ).trim()

    // Si no se encontró ningún patrón de error o surgió una falla no detectada por la regex
    if (logs.isEmpty()) {
        return "No se detectaron errores conocidos en el log. Si la compilación falló, revise la causa directamente en la consola de Jenkins o consulte al administrador de CI/CD."
    }

    return logs
}

def failureDetail() {
    if (env.FAILED_STEP) {
        return " Step que fallo : ${env.FAILED_STEP}\n Detalle : ${env.FAILED_STEP_DETAIL ?: 'sin detalle capturado'}"
    }
    // Respaldo: el fallo no vino de un step envuelto con runStep() (ej. error de sintaxis del pipeline), se usa el grep viejo sobre el log completo
    return " Detalle : ${errors()}"
}

def cleanupNotices() {
    def logPath = "${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log"

    // '|| true' evita que la funcion reviente cuando no hay coincidencias (caso normal en un build sano)
    def notices = sh(
        script: "grep -E 'CLEANUP_WARNING:|CLEANUP_ERROR:' '${logPath}' || true",
        returnStdout: true
    ).trim()

    return notices
}

def trivy() {
    if (!env.TRIVY_CRITICAL) {
        return "No Image Scan done on this run"
    }
    return "Vulnerabilidades Imagen : ${env.TRIVY_CRITICAL} CRITICAL | ${env.TRIVY_HIGH} HIGH | ${env.TRIVY_MEDIUM} MEDIUM | ${env.TRIVY_LOW} LOW"
}
/*
def sonar() {
    if (env.FS == 'Test' || env.FS == 'Scan Image' || env.FS == 'Push Image' || env.FS == 'Deploy') {
        sh "cat ${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log | grep -E ' you can find the results at: | Error during SonarScanner execution | can not be reached' >> .sonarlinkextract"
        def sonarlink = readFile('.sonarlinkextract').trim()
        sh 'rm .sonarlinkextract'
        return "${sonarlink}"
    } else {
        return "No Sonar Analisis done on this run"
    }
}
*/
def sonar() {
    if (env.FS == 'Test' || env.FS == 'Push Image' || env.FS == 'Deploy') {
        def logPath = "${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log"
        
        // Ejecutamos grep asegurando exit code 0 con '|| true' y capturamos la salida
        def sonarlink = sh(
            script: "grep -E 'you can find the results at:|Error during SonarScanner execution|can not be reached' '${logPath}' || true",
            returnStdout: true
        ).trim()

        return sonarlink.isEmpty() ? "SonarQube not reached or no results found" : sonarlink
    } else {
        return "No Sonar Analisis done on this run"
    }
}