def call(){
  post {
    success     {notifySlack(currentBuild.result)}
    failure     {notifySlack(currentBuild.result)}
    unstable    {notifySlack(currentBuild.result)}
  }
}
