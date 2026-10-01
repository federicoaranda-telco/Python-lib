def call(){
  env.FS="${env.STAGE_NAME}"
  sh '${S3_ENV}/ ./ --recursive'
}
def gitlab(){
  sh 'mc cp minio/env/gitlab/config.json ./'
}
def go(){
  env.FS="${env.STAGE_NAME}"
  gitlab()
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/docker-compose.yml ./'
  dir("internal/config") {
    sh '${S3_ENV}/env.go ./'
  }
}
def gonew(){
  env.FS="${env.STAGE_NAME}"
  gitlab()
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/docker-compose.yml ./'
  dir("internal/config") {
    sh '${S3_ENV}/env.go ./'
  }
  dir("internal/logs") {
    sh '${S3_ENV}/logs.go ./'
  }
}
def gonewcerts(){
  env.FS="${env.STAGE_NAME}"
  gitlab()
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/docker-compose.yml ./'
  dir("internal/config") {
    sh '${S3_ENV}/env.go ./'
  }
  dir("internal/logs") {
    sh '${S3_ENV}/logs.go ./'
  }
  dir("internal/credenciales") {
    sh '${S3_ENV}/credenciales/ --recursive ./'
  }
}
def vite(){
  env.FS="${env.STAGE_NAME}"
  gitlab()
  sh '${S3_ENV}/docker-compose.yml ./'
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/.env ./'
  sh '${S3_ENV}/nginx.conf ./'
}
def vitewithcerts(){
  env.FS="${env.STAGE_NAME}"
  gitlab()
  sh '${S3_ENV}/docker-compose.yml ./'
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/.env ./'
  sh '${S3_ENV}/nginx.conf ./'
  dir("credenciales") {
    sh '${S3_ENV}/credenciales/ --recursive ./'
  }
}
def laravel(){
  sh '${S3_ENV}/composer.json ./'
  sh '${S3_ENV}/.env ./'
}
def node(){
  env.FS="${env.STAGE_NAME}"
  gitlab()
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/docker-compose.yml ./'
  sh '${S3_ENV}/.env ./'
}
def py(){
  env.FS="${env.STAGE_NAME}"
  gitlab()
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/docker-compose.yml ./'
}
def flutter(){
  gitlab()
  sh '${S3_ENV}/dockerfile ./'
  sh '${S3_ENV}/docker-compose.yml ./'
}