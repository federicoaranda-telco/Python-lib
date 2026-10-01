def call(){
  env.FS= "${env.STAGE_NAME}"
  sh "sed -i 's+GITLAB_REGISTRY+${env.REPO_IMAGE}+' 'docker-compose.yml\'"
  writeRemoteCleanupScripts()
  sshPublisher(publishers: [sshPublisherDesc(configName: "${env.HOST_REMOTE}",
                                             transfers: [sshTransfer(cleanRemote: false,
                                                                     excludes: '',
                                                                     execCommand: remoteExecCommand("${env.PATH_APP}"),
                                                                     execTimeout: 0,
                                                                     flatten: false,
                                                                     makeEmptyDirs: false,
                                                                     noDefaultExcludes: false,
                                                                     patternSeparator: '[, ]+',
                                                                     remoteDirectory: "${env.PATH_APP}",
                                                                     remoteDirectorySDF: false,
                                                                     removePrefix: '',
                                                                     sourceFiles: 'docker-compose.yml,.cleanup_pre.sh,.cleanup_post.sh')],
                                             usePromotionTimestamp: true,
                                             useWorkspaceInPromotion: false,
                                             verbose: true)])
}
def go(String pathApp){
  env.FS= "${env.STAGE_NAME}"
  sh "sed -i 's+GITLAB_REGISTRY+${env.REPO_IMAGE}+' 'docker-compose.yml\'"
  writeRemoteCleanupScripts()
  sshPublisher(publishers: [sshPublisherDesc(configName: "${env.HOST_REMOTE}",
                                             transfers: [sshTransfer(
                                                                     sourceFiles: 'config.json',
                                                                     remoteDirectory: '.docker'
                                                          ),
                                                          sshTransfer(cleanRemote: false,
                                                                     excludes: '',
                                                                     execCommand: remoteExecCommand(pathApp),
                                                                     execTimeout: 0,
                                                                     flatten: false,
                                                                     makeEmptyDirs: false,
                                                                     noDefaultExcludes: false,
                                                                     patternSeparator: '[, ]+',
                                                                     remoteDirectory: "${pathApp}",
                                                                     remoteDirectorySDF: false,
                                                                     removePrefix: '',
                                                                     sourceFiles: 'docker-compose.yml,.cleanup_pre.sh,.cleanup_post.sh')],
                                             usePromotionTimestamp: true,
                                             useWorkspaceInPromotion: false,
                                             verbose: true)])
}
def vite(String pathApp){
  env.FS= "${env.STAGE_NAME}"
  sh "sed -i 's+GITLAB_REGISTRY+${env.REPO_IMAGE}+' 'docker-compose.yml\'"
  writeRemoteCleanupScripts()
  sshPublisher(publishers: [sshPublisherDesc(configName: "${env.HOST_REMOTE}",
                                             transfers: [sshTransfer(
                                                                     sourceFiles: 'config.json',
                                                                     remoteDirectory: '.docker'
                                                          ),
                                                        sshTransfer(cleanRemote: false,
                                                                     excludes: '',
                                                                     execCommand: remoteExecCommand(pathApp),
                                                                     execTimeout: 0,
                                                                     flatten: false,
                                                                     makeEmptyDirs: false,
                                                                     noDefaultExcludes: false,
                                                                     patternSeparator: '[, ]+',
                                                                     remoteDirectory: "${pathApp}",
                                                                     remoteDirectorySDF: false,
                                                                     removePrefix: '',
                                                                     sourceFiles: 'docker-compose.yml,.cleanup_pre.sh,.cleanup_post.sh')],
                                             usePromotionTimestamp: true,
                                             useWorkspaceInPromotion: false,
                                             verbose: true)])
}
def laravel(String pathApp){
  env.FS= "${env.STAGE_NAME}"
  sh "sed -i 's+GITLAB_REGISTRY+${env.REPO_IMAGE}+' 'docker-compose.yml\'"
  sshPublisher(publishers: [sshPublisherDesc(configName: "${env.HOST_REMOTE}",
                                             transfers: [sshTransfer(cleanRemote: false, 
                                                                     excludes: '', 
                                                                     execCommand: "cd ${pathApp} && git pull",
                                                                     execTimeout: 0, 
                                                                     flatten: false, 
                                                                     makeEmptyDirs: false, 
                                                                     noDefaultExcludes: false,
                                                                     patternSeparator: '[, ]+',
                                                                     remoteDirectory: "${pathApp}",
                                                                     remoteDirectorySDF: false,
                                                                     removePrefix: '',
                                                                     sourceFiles: 'docker-compose.yml')],
                                             usePromotionTimestamp: true,
                                             useWorkspaceInPromotion: false,
                                             verbose: true)])
}
def node(String pathApp){
  env.FS= "${env.STAGE_NAME}"
  sh "sed -i 's+GITLAB_REGISTRY+${env.REPO_IMAGE}+' 'docker-compose.yml\'"
  writeRemoteCleanupScripts()
  sshPublisher(publishers: [sshPublisherDesc(configName: "${env.HOST_REMOTE}",
                                             transfers: [sshTransfer(
                                                                     sourceFiles: 'config.json',
                                                                     remoteDirectory: '.docker'
                                                          ),
                                                        sshTransfer(cleanRemote: false,
                                                                     excludes: '',
                                                                     execCommand: remoteExecCommand(pathApp),
                                                                     execTimeout: 0,
                                                                     flatten: false,
                                                                     makeEmptyDirs: false,
                                                                     noDefaultExcludes: false,
                                                                     patternSeparator: '[, ]+',
                                                                     remoteDirectory: "${pathApp}",
                                                                     remoteDirectorySDF: false,
                                                                     removePrefix: '',
                                                                     sourceFiles: 'docker-compose.yml,.cleanup_pre.sh,.cleanup_post.sh')],
                                             usePromotionTimestamp: true,
                                             useWorkspaceInPromotion: false,
                                             verbose: true)])
}

def remoteExecCommand(String pathApp) {
    return "cd ${pathApp} && bash .cleanup_pre.sh && ${env.CMD_DOCKER} && (bash .cleanup_post.sh || true)"
}

def writeRemoteCleanupScripts() {
    def imgBase = "registry.gitlab.com/telco-${env.GITLAB_GROUP}/${env.PATH_APP}/${env.DIR_ENV}"
    def threshold = env.REMOTE_DISK_USAGE_THRESHOLD_PERCENT
    def minImages = env.REMOTE_MIN_DOCKER_IMAGES_TO_KEEP

    writeFile file: '.cleanup_pre.sh', text: """#!/bin/bash
DOCKER_ROOT=\$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo '/var/lib/docker')
IMG_BASE="${imgBase}"
usage() { df --output=pcent "\$DOCKER_ROOT" | tail -1 | tr -dc '0-9'; }
if [ "\$(usage)" -ge "${threshold}" ]; then
  echo "CLEANUP_WARNING: disco al \$(usage)% (umbral ${threshold}%) en \$IMG_BASE, liberando imagenes viejas del propio proyecto..."
  docker images --format '{{.Repository}}:{{.Tag}}' | grep -F -- "\$IMG_BASE:0." | while read -r line; do
    build_num=\$(echo "\$line" | sed 's/.*:0\\.//')
    echo "\$build_num \$line"
  done | sort -k1,1 -nr | awk -v keep="${minImages}" 'NR>keep {print \$2}' | xargs -r -n1 docker rmi
fi
if [ "\$(usage)" -ge "${threshold}" ]; then
  echo "CLEANUP_ERROR: espacio en disco insuficiente en \$DOCKER_ROOT (\$(usage)%) incluso tras liberar imagenes de \$IMG_BASE. Abortando deploy."
  exit 1
fi
"""

    writeFile file: '.cleanup_post.sh', text: """#!/bin/bash
IMG_BASE="${imgBase}"

# Contenedores caidos/detenidos de este proyecto (por imagen), para liberar su referencia
# antes de intentar borrar imagenes sin tag mas abajo.
docker ps -a --filter "status=exited" --format '{{.ID}} {{.Image}}' | grep -F -- " \$IMG_BASE:0." | awk '{print \$1}' | while read -r cid; do
  if ! docker rm "\$cid" 2>/tmp/cleanup_post_err.\$\$; then
    echo "CLEANUP_WARNING: no se pudo eliminar el contenedor caido \$cid de \$IMG_BASE en el remoto (\$(tr '\\n' ' ' < /tmp/cleanup_post_err.\$\$))"
    rm -f /tmp/cleanup_post_err.\$\$
  fi
done

docker images --format '{{.Repository}}:{{.Tag}}' | grep -F -- "\$IMG_BASE:0." | while read -r line; do
  build_num=\$(echo "\$line" | sed 's/.*:0\\.//')
  echo "\$build_num \$line"
done | sort -k1,1 -nr | awk -v keep="${minImages}" 'NR>keep {print \$2}' | while read -r img; do
  if ! docker rmi "\$img" 2>/tmp/cleanup_post_err.\$\$; then
    echo "CLEANUP_WARNING: no se pudo eliminar la imagen \$img en el remoto (\$(tr '\\n' ' ' < /tmp/cleanup_post_err.\$\$))"
    rm -f /tmp/cleanup_post_err.\$\$
  fi
done

# Imagenes sin tag (<none>:<none>) del propio proyecto que ya no sostiene ningun
# contenedor (ni corriendo ni detenido, gracias al rm de arriba).
if ! docker image prune -f --filter "dangling=true" --filter "label=cicd.project=${env.PATH_APP}" 2>/tmp/cleanup_post_err.\$\$; then
  echo "CLEANUP_WARNING: no se pudieron limpiar las imagenes sin tag de \$IMG_BASE en el remoto (\$(tr '\\n' ' ' < /tmp/cleanup_post_err.\$\$))"
  rm -f /tmp/cleanup_post_err.\$\$
fi
"""
}
