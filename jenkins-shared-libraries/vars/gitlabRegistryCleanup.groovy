def call() {
    try {
        withCredentials([usernamePassword(credentialsId: 'GitLab-Registry', usernameVariable: 'GITLAB_USER', passwordVariable: 'GITLAB_API_TOKEN')]) {
            def maxTags = ((env.GITLAB_REGISTRY_MAX_TAGS ?: '10') as Integer)
            def projectPath = "telco-${env.GITLAB_GROUP}/${env.PATH_APP}"
            def encodedProjectPath = java.net.URLEncoder.encode(projectPath, 'UTF-8')
            


            def reposResult = apiCall("https://gitlab.com/api/v4/projects/${encodedProjectPath}/registry/repositories", 'GET')
            if (reposResult.httpCode == '404') {
                echo "CLEANUP_WARNING: no se encontro el proyecto ${projectPath} en GitLab (HTTP 404), se omite la limpieza del registry"
                return
            }
            if (reposResult.httpCode != '200') {
                echo "CLEANUP_WARNING: no se pudo listar los repositorios del registry para ${projectPath} (HTTP ${reposResult.httpCode} - revisar credencial GitLab-API-Token)"
                return
            }

            def repositories = parseJson(reposResult.body)
            def repository = repositories.find { it.path?.endsWith("/${env.DIR_ENV}") }
            if (!repository) {
                echo "CLEANUP_WARNING: no existe todavia un repositorio de registry para ${env.DIR_ENV} en ${projectPath}, no hay nada que limpiar (probablemente primer push)"
                return
            }

            def tags = []
            def page = 1
            while (true) {
                def tagsResult = apiCall("https://gitlab.com/api/v4/projects/${encodedProjectPath}/registry/repositories/${repository.id}/tags?per_page=100&page=${page}", 'GET')
                if (tagsResult.httpCode != '200') {
                    echo "CLEANUP_WARNING: no se pudo obtener la pagina ${page} de tags del registry para ${projectPath} (HTTP ${tagsResult.httpCode})"
                    return
                }
                def pageTags = parseJson(tagsResult.body)
                if (pageTags.isEmpty()) {
                    break
                }
                tags.addAll(pageTags)
                page++
            }

            def tagsToDelete = tagsToTrim(tags, maxTags)
            if (tagsToDelete.isEmpty()) {
                echo "GitLab Registry (${projectPath}/${env.DIR_ENV}): ${tags.size()} tags, no supera GITLAB_REGISTRY_MAX_TAGS=${maxTags}, no se elimina nada"
                return
            }

            def deletedTags = []
            tagsToDelete.each { tag ->
                def deleteResult = apiCall("https://gitlab.com/api/v4/projects/${encodedProjectPath}/registry/repositories/${repository.id}/tags/${tag}", 'DELETE')
                if (deleteResult.httpCode == '200' || deleteResult.httpCode == '204') {
                    deletedTags.add(tag)
                } else {
                    echo "CLEANUP_WARNING: no se pudo eliminar el tag ${tag} en GitLab Registry de ${projectPath} (HTTP ${deleteResult.httpCode} - revisar credencial GitLab-API-Token)"
                }
            }

            if (!deletedTags.isEmpty()) {
                echo "GitLab Registry (${projectPath}/${env.DIR_ENV}): tags eliminados (${deletedTags.size()}/${tagsToDelete.size()}): ${deletedTags.join(', ')}"
            }
        }
    } catch (Exception e) {
        echo "CLEANUP_WARNING: fallo inesperado limpiando GitLab Container Registry (${e.message})"
    }
}

// Reemplaza readJSON (plugin Pipeline Utility Steps, no instalado) por JsonSlurper puro de Groovy.
// @NonCPS porque JsonSlurper devuelve objetos (LazyMap) que Jenkins no puede serializar entre steps;
// al no ser CPS-transformado, el metodo corre de una sola vez sin necesitar serializar nada.
@NonCPS
def parseJson(String text) {
    return new groovy.json.JsonSlurper().parseText(text)
}

@NonCPS
def tagsToTrim(List tags, int maxTags) {
    def sorted = tags.findAll { it.name ==~ /0\.\d+/ }
                      .sort { a, b -> (a.name.replaceFirst('^0\\.', '') as Integer) <=> (b.name.replaceFirst('^0\\.', '') as Integer) }
    def toDeleteCount = Math.max(0, sorted.size() - maxTags)
    return sorted.take(toDeleteCount).collect { it.name }
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
