def call(){
    env.FS= "${env.STAGE_NAME}"
    sshPublisher(publishers: [sshPublisherDesc(configName: "${env.PROXY_REMOTE}", 
            transfers: [sshTransfer(cleanRemote: false, 
            excludes: '', 
            execCommand: "mv ~/http_route_api_notificaciones_telco.yml ~/mgmt-proxy/traefik/dynamic/http/http_routers/ && mv ~/http_service_api_notificaciones_telco.yml ~/mgmt-proxy/traefik/dynamic/http/http_services/ && docker exec mgmt-proxy-proxy_traefik-1 sh -c 'chown -R root: /etc/traefik/dynamic/'",
            execTimeout: 0, 
            flatten: false, 
            makeEmptyDirs: false, 
            noDefaultExcludes: false, 
            patternSeparator: '[, ]+', 
            remoteDirectory: "",
            remoteDirectorySDF: false, 
            removePrefix: '', 
            sourceFiles: 'http_route_api_notificaciones_telco.yml,http_service_api_notificaciones_telco.yml')],
            usePromotionTimestamp: true, 
            useWorkspaceInPromotion: false, 
    verbose: true)])
}