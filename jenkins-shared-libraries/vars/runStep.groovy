def call(String stepName, Closure body) {
    try {
        body()
    } catch (Exception e) {
        env.FAILED_STEP = stepName
        // El mensaje de la excepcion de un 'sh' fallido es generico ("script returned exit code 1"),
        // no trae el stdout/stderr real del comando. Ese texto real ya quedo impreso en la consola
        // justo antes de la excepcion, asi que se toma la cola del log en vez de e.message.
        env.FAILED_STEP_DETAIL = tailOfLog(20) ?: e.message?.toString()?.take(300)
        throw e
    }
}

def tailOfLog(int lines) {
    def logPath = "${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log"
    def tail = sh(script: "tail -n ${lines} '${logPath}' 2>/dev/null || true", returnStdout: true).trim()
    return tail.isEmpty() ? null : tail.take(1500)
}
