def call(String buildStatus){
    buildStatus = buildStatus ?: 'SUCCESS'
    def colorCode = '#4a6985'
    def subject = " Proyecto : ${env.JOB_NAME}\n Estado Build : ${buildStatus}  |  Nro Build : ${env.BUILD_NUMBER}\n Autor Commit : ${gitCommands.commitAuthor()} - ${gitCommands.commitAuthorMail()}"
    def summary = " ${subject}\n Hash Commit : ${gitCommands.commitHash()}\n Mensaje Commit : ${gitCommands.commitMessage()}\n Modificaciones : ${gitCommands.commitChanged()}"
    switch(buildStatus) {
        case "STARTED":
            colorCode = '#04f8f9'
            slackSend (color: colorCode, message: subject, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
        break
        case "SUCCESS":
            def summarySuccess = "${summary}\n ${commonFunction.cleanupNotices()}"
            colorCode = '#09ed46'
            slackSend (color: colorCode, message: summarySuccess, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
        break
        case "UNSTABLE":
            def summaryError = "${subject}\n Etapa : ${env.FS}\n ${commonFunction.failureDetail()}\n ${commonFunction.cleanupNotices()}"
            colorCode = '#f1c232'
            slackSend (color: colorCode, message: summaryError, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
        break
        case "FAILURE":
            def summaryError = "${subject}\n Etapa : ${env.FS}\n ${commonFunction.failureDetail()}\n ${commonFunction.cleanupNotices()}"
            colorCode = '#cc0000'
            slackSend (color: colorCode, message: summaryError, channel: "${SLACK_CHANNEL}", teamDomain: "${SLACK_TEAM}", tokenCredentialId: "${SLACK_TOKEN}")
        break
    }
}
def environmentChannel(String channel){
    if (channel == 'prod') {
        env.SLACK_CHANNEL = '#cicd-status-production'
        env.SLACK_TOKEN = 'slack_cicd_status_production'
    } else {
        env.SLACK_CHANNEL = '#cicd-status-development'
        env.SLACK_TOKEN = 'slack_cicd_status_development'
    }
}