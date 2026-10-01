def call(){
  env.FS="${env.STAGE_NAME}"
  //app = docker.build("${REPO_IMAGE}")
  sh 'docker build --label "cicd.project=${PATH_APP}" -t "${REPO_IMAGE}" .'
}
