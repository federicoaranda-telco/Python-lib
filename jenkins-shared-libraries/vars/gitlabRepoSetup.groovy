// Verifica si el repositorio telco-${GITLAB_GROUP}/${PATH_APP} existe en GitLab, y si no, lo crea vacio
// y le agrega un primer commit con la estructura base (Dockerfile/docker-compose/etc.), generada
// corriendo el mismo copyConfEnv.X() que ya usa el pipeline, para no duplicar esa logica ni hardcodear
// contenido de templates aca. El contenido sale siempre del S3 en el momento, nunca queda hardcodeado.
// Silencioso si el repo ya existe (caso normal en casi todos los builds); solo hace echo si lo crea.
//
// scaffoldFn: closure que baja los archivos base al workspace actual, ej. { copyConfEnv.gonew() }.
// Cada pipeline pasa la misma variante que ya usa en su propio stage 'Copy Conf/Env'.
def call(Closure scaffoldFn) {
    withCredentials([string(credentialsId: 'GitLab-Group-API-Token', variable: 'GITLAB_API_TOKEN')]) {
        def groupPath = "telco-${env.GITLAB_GROUP}"
        def projectPath = "${groupPath}/${env.PATH_APP}"
        def encodedProjectPath = java.net.URLEncoder.encode(projectPath, 'UTF-8')

        def getResult = apiCall("https://gitlab.com/api/v4/projects/${encodedProjectPath}", 'GET')
        if (getResult.httpCode == '200') {
            return
        }
        if (getResult.httpCode != '404') {
            error "No se pudo verificar si el repositorio ${projectPath} existe en GitLab (HTTP ${getResult.httpCode} - revisar credencial GitLab-Group-API-Token)"
        }

        def encodedGroupPath = java.net.URLEncoder.encode(groupPath, 'UTF-8')
        def groupResult = apiCall("https://gitlab.com/api/v4/groups/${encodedGroupPath}", 'GET')
        if (groupResult.httpCode != '200') {
            error "No se pudo encontrar el grupo ${groupPath} en GitLab (HTTP ${groupResult.httpCode})"
        }
        def group = parseJson(groupResult.body)

        def encodedName = java.net.URLEncoder.encode(env.PATH_APP, 'UTF-8')
        def createResult = apiCall("https://gitlab.com/api/v4/projects?name=${encodedName}&path=${encodedName}&namespace_id=${group.id}&visibility=private&initialize_with_readme=false", 'POST')
        if (createResult.httpCode != '201') {
            error "No se pudo crear el repositorio ${projectPath} en GitLab (HTTP ${createResult.httpCode})"
        }
        def project = parseJson(createResult.body)

        echo "Repositorio ${projectPath} no existia en GitLab, se creo vacio"

        seedInitialCommit(project.id, scaffoldFn)
    }
}

// Reemplaza readJSON (plugin Pipeline Utility Steps, no instalado) por JsonSlurper puro de Groovy.
// @NonCPS porque JsonSlurper devuelve objetos (LazyMap) que Jenkins no puede serializar entre steps;
// al no ser CPS-transformado, el metodo corre de una sola vez sin necesitar serializar nada.
@NonCPS
def parseJson(String text) {
    return new groovy.json.JsonSlurper().parseText(text)
}

// Archivos que copyConfEnv.gitlab()/vite()/vitewithcerts() bajan del S3 y que NUNCA deben ir a un
// commit de git: config.json (Docker auth config con credenciales), .env (secretos del proyecto),
// y todo lo que venga de la carpeta credenciales/ (certificados/keys). Si no queda nada mas para
// commitear, el repo se deja vacio en vez de arriesgar filtrar un secreto al historial de git.
def isExcluded(String relativePath) {
    return relativePath == 'config.json' ||
           relativePath == '.env' ||
           relativePath.startsWith('credenciales/') ||
           relativePath.contains('/credenciales/')
}

def seedInitialCommit(projectId, Closure scaffoldFn) {
    def seedDir = 'gitlab_repo_seed'
    dir(seedDir) {
        deleteDir()
        scaffoldFn()

        def actions = []
        def excluded = []
        // Reemplaza findFiles (plugin Pipeline Utility Steps, no instalado) por 'find' + readFile,
        // ambos sin dependencia de ningun plugin.
        def fileListing = sh(script: "find . -type f | sed 's|^\\./||'", returnStdout: true).trim()
        def paths = fileListing ? fileListing.split('\n') as List : []
        paths.each { path ->
            if (isExcluded(path)) {
                excluded.add(path)
                return
            }
            actions.add([action: 'create', file_path: path, content: readFile(path)])
        }

        if (!excluded.isEmpty()) {
            echo "gitlabRepoSetup: se excluyeron del commit inicial (contienen secretos): ${excluded.join(', ')}"
        }

        if (actions.isEmpty()) {
            echo "CLEANUP_WARNING: no quedo ningun archivo no-sensible para el commit inicial de ${env.PATH_APP}, el repo queda vacio (Clone Repo va a fallar hasta que alguien suba un primer commit a mano)"
            return
        }

        def payload = groovy.json.JsonOutput.toJson([
            branch: env.BRANCH_GIT,
            commit_message: 'Estructura inicial generada automaticamente desde Jenkins',
            actions: actions
        ])
        writeFile file: 'commit-payload.json', text: payload

        def commitResult = apiCallWithBody("https://gitlab.com/api/v4/projects/${projectId}/repository/commits", 'commit-payload.json')
        if (commitResult.httpCode != '201') {
            echo "CLEANUP_WARNING: no se pudo crear el commit inicial en ${env.PATH_APP} (HTTP ${commitResult.httpCode}), el repo quedo vacio"
        }
    }
}

def apiCall(String url, String method) {
    def raw = sh(
        script: "curl -sw '\\n%{http_code}' -X ${method} --header \"PRIVATE-TOKEN: \${GITLAB_API_TOKEN}\" \"${url}\"",
        returnStdout: true
    ).trim()
    def lines = raw.tokenize('\n')
    def httpCode = lines[-1]
    def body = lines.size() > 1 ? lines[0..-2].join('\n') : ''
    return [body: body, httpCode: httpCode]
}

def apiCallWithBody(String url, String jsonFilePath) {
    def raw = sh(
        script: "curl -sw '\\n%{http_code}' -X POST --header \"PRIVATE-TOKEN: \${GITLAB_API_TOKEN}\" --header \"Content-Type: application/json\" --data @${jsonFilePath} \"${url}\"",
        returnStdout: true
    ).trim()
    def lines = raw.tokenize('\n')
    def httpCode = lines[-1]
    def body = lines.size() > 1 ? lines[0..-2].join('\n') : ''
    return [body: body, httpCode: httpCode]
}
