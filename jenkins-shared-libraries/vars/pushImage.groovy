def call(){
  env.FS= "${env.STAGE_NAME}"
  docker.withRegistry('https://registry.gitlab.com', 'GitLab-Registry') {
    //app.push()
    sh 'docker push "${REPO_IMAGE}"'
    gitlabRegistryCleanup()
    sh "docker rmi ${REPO_IMAGE}"
  }
  cleanDockerImages()
}
