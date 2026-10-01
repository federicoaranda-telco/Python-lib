def call() {
    try {
        def maxLogFiles = (env.AGENT_CLEANUP_LOG_MAX_FILES ?: '20') as Integer
        sh """
set -e
LOG_DIR="\$HOME/.cicd_cleanup_logs/${env.PATH_APP}"
mkdir -p "\$LOG_DIR"

count=\$(ls -1 "\$LOG_DIR" 2>/dev/null | wc -l)
if [ "\$count" -ge ${maxLogFiles} ]; then
  to_delete=\$((count - ${maxLogFiles} + 1))
  ls -1t "\$LOG_DIR" | tail -n "\$to_delete" | while read -r f; do rm -f "\$LOG_DIR/\$f"; done
fi

LOG_FILE="\$LOG_DIR/cleanup_\$(date +%Y%m%d_%H%M%S)_${env.BUILD_NUMBER}.txt"
{
  echo "Build: ${env.BUILD_NUMBER} - Fecha: \$(date -Iseconds)"
  docker image prune -f --filter "dangling=true" --filter "label=cicd.project=${env.PATH_APP}"
} > "\$LOG_FILE" 2>&1 || true
"""
    } catch (Exception e) {
        echo "CLEANUP_WARNING: no se pudieron limpiar las imagenes intermedias sin tag (<none>:<none>) del proyecto ${env.PATH_APP} en el agente (${e.message})"
    }
}
