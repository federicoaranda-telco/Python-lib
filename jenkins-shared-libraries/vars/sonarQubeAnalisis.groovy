def call(){
  try {
  //env.FS="${env.STAGE_NAME}"
  writeFile file: 'sonar-project.properties', text: """sonar.projectKey=${env.BRANCH_GIT}_${env.PATH_APP}
sonar.projectName=${env.BRANCH_GIT}_${env.PATH_APP}
sonar.ProjectVersion=0.${env.BUILD_NUMBER}
sonar.sourceEncoding=UTF-8
sonar.sources=${env.WORKSPACE}
sonar.login=admin
sonar.password=4fxv22BNDxLDjrG7iid9YLmg3lM8Mt
"""
  def scannerHome = tool name: 'sonarqubescanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
    withSonarQubeEnv('Telco SonarQube CI') {
      sh "${scannerHome}/bin/sonar-scanner"
    }
} catch (Exception e) {
  catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
    echo "SonarQube Analisis failed, but continuing..."
  }
}
}
