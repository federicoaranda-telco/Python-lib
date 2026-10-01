def call(){
  env.FS="${env.STAGE_NAME}"
  checkout changelog: false, 
  scm: [$class: 'GitSCM', 
  branches: [[name: "*/${BRANCH_GIT}"]], 
  extensions: [], 
  userRemoteConfigs: [[credentialsId: "${env.USER_GIT}", url: "${env.REPO_GIT}"]]]
}
