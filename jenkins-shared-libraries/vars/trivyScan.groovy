// Capa de analisis de vulnerabilidades de la imagen Docker (Trivy).
// Es una capa informativa: lista los hallazgos pero NUNCA corta la integracion.
// Debe correr entre 'Build Image' y 'Push Image', porque pushImage() hace docker rmi
// y despues de esa etapa la imagen ya no existe localmente en el agente.
// Se desactiva por proyecto con TRIVY_SCAN: 'FALSE' en el mapa del Jenkinsfile.
//
// Jenkins corre en un contenedor con el socket de Docker montado (sibling container):
// el daemon Docker ve un filesystem distinto al del workspace de Jenkins, asi que un
// bind-mount tipo '-v ${env.WORKSPACE}:/workspace' no apunta a la misma carpeta de los
// dos lados. Por eso ningun paso de abajo escribe archivos con --output dentro de un
// volumen: los reportes se capturan por stdout (returnStdout) y se guardan con
// writeFile, que corre en el propio agente Jenkins, no dentro del contenedor de Trivy.

def call(){
  env.FS = "${env.STAGE_NAME}"

  if (env.TRIVY_SCAN?.toUpperCase() == 'FALSE') {
    echo 'Trivy Scan Disabled'
    return
  }

  // Envuelve el trabajo riesgoso, no un echo: si Trivy no arranca, se cae la red
  // o falta el socket de Docker, el build sigue en SUCCESS y pasa a Push Image.
  catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
    sh "mkdir -p ${reportDir()}"
    consoleReport()
    saveReport('json', 'trivy-report.json')
    saveReport('html', 'trivy-report.html')
    countVulnerabilities()
  }

  archiveArtifacts artifacts: "${reportDir()}/*", allowEmptyArchive: true
  publishHtmlReport()
}

// Tabla legible directo al log de consola del build.
def consoleReport(){
  sh "docker run --rm ${dockerArgs()} ${trivyImage()} image --scanners vuln --no-progress --exit-code 0 --format table ${env.REPO_IMAGE}"
}

// Un escaneo completo por formato (json/html). No comparten un solo JSON via
// 'trivy convert' porque convert tambien necesita leer/escribir archivos, y eso
// arrastra el mismo problema de bind-mount. La base de vulnerabilidades queda
// cacheada en el volumen nombrado 'trivy-cache', asi que el costo extra de escanear
// dos veces mas es solo CPU, sin descargas de red repetidas.
def saveReport(String format, String filename){
  def formatFlag = format == 'html' ? "--format template --template '@/contrib/html.tpl'" : "--format ${format}"
  def content = sh(returnStdout: true, script: "docker run --rm ${dockerArgs()} ${trivyImage()} image --scanners vuln --no-progress --exit-code 0 ${formatFlag} ${env.REPO_IMAGE}").trim()
  writeFile file: "${reportDir()}/${filename}", text: content
}

def dockerArgs(){
  return '-v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache/'
}

def countVulnerabilities(){
  env.TRIVY_CRITICAL = countSeverity('CRITICAL')
  env.TRIVY_HIGH     = countSeverity('HIGH')
  env.TRIVY_MEDIUM   = countSeverity('MEDIUM')
  env.TRIVY_LOW      = countSeverity('LOW')
  echo "Trivy: ${env.TRIVY_CRITICAL} CRITICAL | ${env.TRIVY_HIGH} HIGH | ${env.TRIVY_MEDIUM} MEDIUM | ${env.TRIVY_LOW} LOW"
}

// grep sobre el JSON en vez de jq o readJSON, para no sumar dependencias al agente.
// El regex exige los dos puntos pegados a Severity para no contar "SeveritySource".
// grep sin matches devuelve 1, pero el exit code del pipe es el de wc, asi que da 0.
def countSeverity(String severity){
  return sh(returnStdout: true, script: "grep -oE '\"Severity\":[[:space:]]*\"${severity}\"' ${reportDir()}/trivy-report.json | wc -l").trim()
}

// Degrada sin ruido si el plugin HTML Publisher no esta instalado en el Jenkins.
def publishHtmlReport(){
  try {
    publishHTML(target: [
      reportDir: "${reportDir()}",
      reportFiles: 'trivy-report.html',
      reportName: 'Trivy Image Scan',
      keepAll: true,
      alwaysLinkToLastBuild: true,
      allowMissing: true
    ])
  } catch (Exception e) {
    echo 'HTML Publisher plugin not available, skipping Trivy HTML report'
  }
}

def reportDir(){
  return 'trivy'
}

def trivyImage(){
  return env.TRIVY_IMAGE ?: 'aquasec/trivy:latest'
}
