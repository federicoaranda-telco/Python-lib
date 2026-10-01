# Limpieza de imágenes Docker en el flujo Jenkins → GitLab Registry → Servidor remoto

## Contexto

Hoy la shared library (`jenkins-shared-libraries/vars/`) hace build (`buildImage.groovy`) → push a `registry.gitlab.com` (`pushImage.groovy`, que ya borra localmente la imagen recién pusheada con `docker rmi`) → deploy remoto vía `sshPublisher`/`execCommand` (`deploy.groovy`). El tag de cada imagen es siempre `0.${BUILD_NUMBER}` (definido en las 6 pipelines: `defaultPipeline.groovy`, `goPipeline.groovy`, `laravelPipeline.groovy`, `nodePipeline.groovy`, `vitePipeline.groovy`, `flutterPipeline.groovy`, línea 10 en todas).

No existe hoy:
- Limpieza de imágenes intermedias sin tag (`<none>`) en el agente Jenkins.
- Ninguna integración con la API de GitLab Container Registry (no hay token, ni llamadas REST) para controlar cuántos tags acumula un repositorio antes de subir uno nuevo.
- Ninguna lógica de retención mínima de imágenes en el servidor remoto de deploy.

El objetivo es cerrar las tres puntas para evitar que se acumulen imágenes sin control tanto en los agentes de Jenkins, como en GitLab Container Registry, como en los servidores de destino del deploy — todo parametrizable para no romper proyectos consumidores existentes (deben seguir funcionando si no pasan los nuevos parámetros, usando valores por defecto).

## Componente A — Limpieza local en el agente Jenkins

Extiende `vars/pushImage.groovy` (y de paso corrige el bug preexistente de la llave de cierre mal ubicada en la línea 5, ya que se toca el archivo):

- Se agrega un nuevo step **`vars/cleanDockerImages.groovy`** que ejecuta `docker image prune -f --filter "dangling=true"` para eliminar las imágenes intermedias sin tag (`<none>:<none>`) que deja cada build multi-stage.
- `pushImage.groovy` queda: fix del brace → push → si el push fue exitoso, llamar a `gitlabRegistryCleanup()` (Componente B) → `docker rmi ${REPO_IMAGE}` (ya existe, se mantiene) → `cleanDockerImages()` (nuevo, al final).

  **Por qué después y no antes:** a diferencia de una limpieza de disco local, "liberar espacio antes" no aplica en un registry remoto (no hay límite de disco que lo justifique). Si el cleanup corriera antes y el push fallara después, el proyecto quedaría con menos tags recuperables y sin el nuevo. Correrlo solo tras un push exitoso evita ese escenario.

## Componente B — Límite de tags en GitLab Container Registry

Nuevo step **`vars/gitlabRegistryCleanup.groovy`**, invocado desde `pushImage.groovy` **después** de un `docker push` exitoso (ver justificación de orden en Componente A). Usa la API REST de GitLab (`/api/v4/projects/:id/registry/repositories`), que hoy no está integrada en ningún lado del repo.

Lógica:
1. Autenticación vía credencial Jenkins tipo *Secret text* (nueva, a crear manualmente) con un GitLab Personal/Project Access Token, scope **estrictamente `read_registry`+`write_registry`** (no `api` completo: el script solo lista y borra tags del registry, así que darle más permiso que eso aumenta el blast radius si el token se filtra). Se referencia por id fijo, ej. `'GitLab-API-Token'`, igual que `pushImage.groovy` ya referencia `'GitLab-Registry'`.
2. `GET /projects/<telco-{GITLAB_GROUP}%2F{PATH_APP} url-encoded>/registry/repositories` para encontrar el repository cuyo `path` termina en `/${DIR_ENV}` → obtener su `id`. Si no existe todavía (primer push del proyecto), no hay nada que limpiar y se corta ahí.
3. `GET /projects/:id/registry/repositories/:repository_id/tags?per_page=100`, paginando explícitamente: seguir el header `Link` de la respuesta (o iterar `page=1,2,...` hasta obtener una página vacía) hasta agotar todos los tags. Con `GITLAB_REGISTRY_MAX_TAGS` alto, o simplemente con un proyecto viejo, puede haber más de 100 tags — si no se pagina, el cálculo del paso 5 queda mal por trabajar sobre un subconjunto.
4. Ordenar los tags por el número de build (parseado del propio nombre `0.<BUILD_NUMBER>`, ya que ese es el único esquema de tagging usado) de más antiguo a más nuevo.
5. Con el parámetro **`GITLAB_REGISTRY_MAX_TAGS`** (nuevo), calcular `cantidad_a_borrar = max(0, cantidad_actual - GITLAB_REGISTRY_MAX_TAGS)` y borrar esa cantidad de tags más antiguos vía `DELETE /projects/:id/registry/repositories/:repository_id/tags/:tag_name`.

   El `max(0, ...)` es obligatorio: si `cantidad_actual <= GITLAB_REGISTRY_MAX_TAGS` no debe borrarse nada. Nótese además que, al correr el cleanup después del push (y no antes), `cantidad_actual` ya incluye el tag nuevo — por eso la fórmula ya no suma `+1` para "hacer lugar": no hay lugar que hacer, el tag nuevo ya está adentro.
6. Parseo de JSON con el step `readJSON` (plugin *Pipeline Utility Steps*). Se fija esta única estrategia de antemano — no se mantiene un code path alternativo con `jq`, para no duplicar lógica de parseo; el prerrequisito es verificar que el plugin esté instalado antes de implementar, no decidir en runtime cuál usar.

`GITLAB_REGISTRY_MAX_TAGS` se agrega con valor por defecto (ej. `10`) en las 6 pipelines (`defaultPipeline.groovy` línea ~11, y análogo en `goPipeline.groovy`, `laravelPipeline.groovy`, `nodePipeline.groovy`, `vitePipeline.groovy`, `flutterPipeline.groovy`), mismo patrón que ya usan para `PATH_APP`/`REPO_IMAGE`/`S3_ENV`: `env.GITLAB_REGISTRY_MAX_TAGS = env.GITLAB_REGISTRY_MAX_TAGS ?: '10'`. Así los proyectos consumidores pueden override vía el `Map params`, y los que no lo pasen siguen funcionando igual que hoy.

## Componente C — Mínimo de imágenes y espacio en disco en el servidor remoto de deploy

Se modifica el `execCommand` que ya ejecuta `sshPublisher` en `vars/deploy.groovy`, en las funciones **`call()`, `go()`, `vite()`, `node()`** (no en `laravel()`, que solo hace `git pull` y no maneja imágenes Docker en el remoto).

**Salvaguarda de alcance (clave para ambos scripts de abajo):** toda la lógica de limpieza remota filtra siempre por el repositorio base exacto del proyecto que se está desplegando, `IMG_BASE="registry.gitlab.com/telco-${GITLAB_GROUP}/${PATH_APP}/${DIR_ENV}"` (mismo nombre de repo, ignorando solo el número de build del tag). Nunca se listan ni se tocan imágenes de otros repositorios/proyectos que puedan compartir el mismo servidor remoto — no importa si esos otros proyectos están temporalmente sin contenedor corriendo (reinicio del servidor, mantenimiento, etc.), ya que el filtro es por nombre de imagen, no por si hay o no un contenedor activo.

**C.1 — Chequeo de espacio en disco antes del pull** (nuevo, se antepone a `${env.CMD_DOCKER}` en el `execCommand`):

```bash
DOCKER_ROOT=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo '/var/lib/docker')
IMG_BASE="registry.gitlab.com/telco-${GITLAB_GROUP}/${PATH_APP}/${DIR_ENV}"
usage() { df --output=pcent "$DOCKER_ROOT" | tail -1 | tr -dc '0-9'; }
if [ "$(usage)" -ge "${REMOTE_DISK_USAGE_THRESHOLD_PERCENT}" ]; then
  echo "Disco al $(usage)% (umbral ${REMOTE_DISK_USAGE_THRESHOLD_PERCENT}%): liberando imágenes viejas de ${IMG_BASE}..."
  docker images --format '{{.Repository}}:{{.Tag}}' | grep -F -- "${IMG_BASE}:0." | while read -r line; do
    build_num=$(echo "$line" | sed 's/.*:0\.//')
    echo "$build_num $line"
  done | sort -k1,1 -nr | awk -v keep="${REMOTE_MIN_DOCKER_IMAGES_TO_KEEP}" 'NR>keep {print $2}' | xargs -r -n1 docker rmi
fi
if [ "$(usage)" -ge "${REMOTE_DISK_USAGE_THRESHOLD_PERCENT}" ]; then
  echo "ERROR: espacio en disco insuficiente en ${DOCKER_ROOT} ($(usage)%) incluso tras liberar imágenes de ${IMG_BASE}. Abortando deploy."
  exit 1
fi
```

Este chequeo solo libera espacio recortando las imágenes **del propio proyecto** hasta `REMOTE_MIN_DOCKER_IMAGES_TO_KEEP` (nunca de otros); si con eso no alcanza, aborta el deploy con un error explícito en lugar de dejar que el `pull` falle a mitad de camino con el disco lleno (lo que podría corromper el estado del contenedor en ejecución).

Nota: si **todas** las imágenes recortables del propio proyecto están en uso (por ejemplo, sostenidas por contenedores que no se bajaron), el `xargs -r ... docker rmi` de la línea 52 falla en silencio esas líneas puntuales (comportamiento esperado, no aborta el pipe), pero como no se liberó espacio, el segundo chequeo de `usage()` en la línea 54 sí lo detecta y aborta el deploy con el `ERROR` explícito. Este caso debe agregarse como prueba explícita en la sección de Verificación (ver punto 9 más abajo), no solo asumirse cubierto por el diseño.

**Nota de concurrencia:** si dos builds del mismo proyecto (por ejemplo dos ramas distintas) corrieran en paralelo contra el mismo servidor remoto, C.1 y C.2 podrían pisarse entre sí (`docker rmi` concurrente sobre las mismas imágenes, o un `usage()` leído por uno mientras el otro está liberando espacio). Se descartó proteger esto con tooling (ver "Concurrencia en Componente C" más abajo): por proceso solo se corre un build (prod, dev o test) a la vez, nunca en simultáneo, así que el escenario no se da en la práctica. Es una garantía operativa, no técnica — si esa práctica cambia en el futuro, este riesgo vuelve a estar sobre la mesa y hay que revisar esta nota.

**C.2 — Recorte final post-deploy** (ya diseñado antes, se mantiene igual, encadenado con `&&` después de `${env.CMD_DOCKER}` y terminando en `|| true` para no tumbar el deploy si la limpieza falla):

```bash
IMG_BASE="registry.gitlab.com/telco-${GITLAB_GROUP}/${PATH_APP}/${DIR_ENV}"
docker images --format '{{.Repository}}:{{.Tag}}' | grep -F -- "${IMG_BASE}:0." | while read -r line; do
  build_num=$(echo "$line" | sed 's/.*:0\.//')
  echo "$build_num $line"
done | sort -k1,1 -nr | awk -v keep="${REMOTE_MIN_DOCKER_IMAGES_TO_KEEP}" 'NR>keep {print $2}' | xargs -r -n1 docker rmi
```

Conserva las `REMOTE_MIN_DOCKER_IMAGES_TO_KEEP` imágenes más recientes (por número de build) del repositorio del proyecto y borra el resto una por una (`docker rmi` sin `-f`, así si alguna sigue en uso por un contenedor corriendo simplemente falla esa línea sin afectar a las demás ni al deploy).

El `execCommand` final queda entonces (ejemplo para `go()`): `cd ${pathApp} && ${chequeoDiscoYLimpiezaC1} && ${env.CMD_DOCKER} && ${recorteFinalC2}`.

**Importante al editar esta línea en el futuro:** C.2 depende de que la cadena siga unida con `&&` — si `${env.CMD_DOCKER}` falla, C.2 no debe ejecutarse, porque si `REMOTE_MIN_DOCKER_IMAGES_TO_KEEP` es bajo (ej. `1`) y el deploy nuevo falló, correr C.2 igual podría borrar la imagen anterior que todavía sostiene el contenedor viejo corriendo, dejando sin imagen para hacer rollback. Como es un único string de bash, es fácil romper esa cadena por accidente en una edición futura (por ejemplo cambiando `&&` por `;` o agregando algo entre medio); cualquier cambio a esta línea debe revisar explícitamente que la condición siga encadenada antes de mergear.

`REMOTE_MIN_DOCKER_IMAGES_TO_KEEP` y `REMOTE_DISK_USAGE_THRESHOLD_PERCENT` se agregan igual que `GITLAB_REGISTRY_MAX_TAGS`: con default (ej. `3` y `85` respectivamente) en las 6 pipelines, override vía `Map params`.

## Componente D — Manejo de errores y notificación por Slack

Hoy el único mecanismo de reporte de errores a Slack (`commonFunction.groovy:35-40`, función `errors()`) hace `grep -E` sobre el log crudo del build (`${env.JENKINS_HOME}/jobs/${env.JOB_NAME}/builds/${env.BUILD_NUMBER}/log`) buscando palabras genéricas (`ERROR`, `expecting`, `unknown`, `no space left`, etc.) y pega el resultado crudo en el mensaje de Slack (`notifySlack.groovy`, `notifySlackSonarQube.groovy`, `commonFunction.notifySlackQube`). Es ambiguo (puede matchear texto no relacionado) y no distingue qué componente falló.

Para los 3 componentes nuevos se adopta una convención dedicada y explícita, reutilizando el mismo mecanismo de grep-sobre-el-log ya establecido (consistente con el resto del código) pero con un tag propio y sin ambigüedad:

- El prefijo `CLEANUP_WARNING:`/`CLEANUP_ERROR:` es literal y fijo, igual que los patrones ya grepeados por `errors()`. Antes de implementar, verificar que ese texto no pueda aparecer nunca en la salida normal de `docker`/`curl`/`git` que se logea (por ejemplo, en un mensaje de error de Docker o en el body de una respuesta de la API de GitLab), para no generar falsos positivos en `cleanupNotices()`. Es una validación de bajo esfuerzo pero hay que hacerla explícitamente, no asumirla.
- Toda condición **recuperable** (no debe frenar el pipeline) imprime `echo "CLEANUP_WARNING: <mensaje claro en español>"` y continúa. Ej: "no se pudo eliminar el tag 0.38 en GitLab Registry (HTTP 403 - revisar credencial GitLab-API-Token)", "no se pudo eliminar la imagen registry.gitlab.com/.../app:0.40 en el remoto (probablemente en uso por un contenedor)".
- La única condición **fatal** ya diseñada (Componente C.1: disco insuficiente incluso tras liberar imágenes del propio proyecto) imprime `echo "CLEANUP_ERROR: <mensaje claro>"` y hace `exit 1` (o `error(...)` del lado Groovy), cortando el pipeline con un motivo explícito en vez de que el `docker pull` falle a mitad de camino con un error críptico de Docker.
- En Groovy (`cleanDockerImages.groovy`, `gitlabRegistryCleanup.groovy`), cada operación de riesgo (llamada `sh` a la API de GitLab, `docker image prune`) va envuelta en `try/catch`; en el `catch` se hace `echo "CLEANUP_WARNING: ..."` con el mensaje del error y **no se relanza la excepción** (housekeeping no crítico no debe tumbar el build). En `gitlabRegistryCleanup.groovy` puntualmente: el `curl` a la API se hace pidiendo también el status HTTP (`curl -sw '\n%{http_code}' ...`), separando body y código antes de intentar `readJSON`, para poder distinguir un 401/403 (token inválido/sin permisos) de un 404 (repositorio inexistente, caso normal en primer push) de un error de parseo, y emitir un `CLEANUP_WARNING` específico para cada caso en vez de una excepción genérica de parseo JSON.
- Nuevo helper **`cleanupNotices()`** en `commonFunction.groovy`, mismo patrón que `errors()`/`sonar()` pero grepeando `'CLEANUP_WARNING:|CLEANUP_ERROR:'`, con un cuidado extra: se agrega `|| true` al `grep` (algo que `errors()`/`sonar()` no tienen hoy) para que la función no reviente cuando no hay ninguna coincidencia — que es el caso normal en un build sano, y justamente el caso que más importa cubrir bien porque `cleanupNotices()` se va a invocar también en el mensaje de **SUCCESS**, no solo en UNSTABLE/FAILURE como `errors()`.
- `notifySlack.groovy`, `notifySlackSonarQube.groovy` y `commonFunction.notifySlackQube` (dentro de `commonFunction.groovy`) agregan `${commonFunction.cleanupNotices()}` al mensaje en los 4 casos (STARTED no aplica, pero SUCCESS/UNSTABLE/FAILURE sí), para que quien esté mirando Slack vea los avisos de limpieza aunque el build general haya sido exitoso (ej. "el push funcionó pero no se pudo limpiar GitLab Registry").

## Archivos a tocar

**Nuevos:**
- `jenkins-shared-libraries/vars/cleanDockerImages.groovy`
- `jenkins-shared-libraries/vars/gitlabRegistryCleanup.groovy`

**Modificados:**
- `jenkins-shared-libraries/vars/pushImage.groovy` (fix brace + llamadas a los dos nuevos steps)
- `jenkins-shared-libraries/vars/deploy.groovy` (append del script de limpieza remota en `call()`, `go()`, `vite()`, `node()`)
- `jenkins-shared-libraries/vars/defaultPipeline.groovy`, `goPipeline.groovy`, `laravelPipeline.groovy`, `nodePipeline.groovy`, `vitePipeline.groovy`, `flutterPipeline.groovy` (defaults de `GITLAB_REGISTRY_MAX_TAGS`, `REMOTE_MIN_DOCKER_IMAGES_TO_KEEP` y `REMOTE_DISK_USAGE_THRESHOLD_PERCENT` junto a donde ya se setea `PATH_APP`/`REPO_IMAGE`)
- `jenkins-shared-libraries/vars/commonFunction.groovy` (nueva función `cleanupNotices()`, se agrega su llamada dentro de `notifySlackQube()`)
- `jenkins-shared-libraries/vars/notifySlack.groovy`, `notifySlackSonarQube.groovy` (se agrega `${commonFunction.cleanupNotices()}` a los mensajes SUCCESS/UNSTABLE/FAILURE)

## Prerrequisitos manuales (fuera del código)

- Credencial `GitLab-Registry` (Username with password, ya existía para `docker login`) reusada para `gitlabRegistryCleanup.groovy` vía `usernamePassword` binding — no se creó `GitLab-API-Token` por separado.
- **Plugin *Pipeline Utility Steps* confirmado como NO instalado, y no se va a instalar.** `readJSON` y `findFiles` (que se iban a usar en `gitlabRegistryCleanup.groovy` y `gitlabRepoSetup.groovy`) se reemplazaron por alternativas sin dependencia de plugins: `readJSON` → `groovy.json.JsonSlurper` envuelto en un método `@NonCPS` (necesario porque el objeto que devuelve `JsonSlurper` no es serializable entre steps de Jenkins); `findFiles` → `sh 'find . -type f'` + `readFile`. Ningún step de este componente depende ya de ese plugin.

## Verificación

Este repo no tiene tests automatizados (no hay carpeta de tests ni harness de Groovy/JenkinsPipelineUnit). La verificación es necesariamente manual, contra un Jenkins real:

1. Revisión sintáctica manual de cada `.groovy` nuevo/modificado (no hay linter configurado en el repo).
2. Correr un build de un proyecto de prueba con `GITLAB_REGISTRY_MAX_TAGS` bajo (ej. `2`) y confirmar en la UI/API de GitLab Container Registry que los tags más antiguos se borran antes de que aparezca el nuevo.
3. Confirmar que `cleanDockerImages()` deja sin imágenes `<none>:<none>` (`docker images -f dangling=true`) en el agente tras el build.
4. Correr un deploy con `REMOTE_MIN_DOCKER_IMAGES_TO_KEEP` bajo (ej. `2`) contra un servidor de prueba y verificar con `docker images | grep <repo>` en el remoto que solo quedan esa cantidad de imágenes tras el deploy, y que el contenedor sigue corriendo sin errores.
5. En ese mismo servidor de prueba, desplegar/tener otro proyecto distinto (con su propio `IMG_BASE`) y confirmar que la limpieza remota del Componente C nunca toca sus imágenes ni sus tags, aunque ese otro proyecto esté momentáneamente sin contenedor corriendo (ej. simulando un reinicio del servidor deteniendo su contenedor antes de correr el deploy del proyecto bajo prueba).
6. Forzar el umbral `REMOTE_DISK_USAGE_THRESHOLD_PERCENT` muy bajo (ej. `1`) en un servidor de prueba para validar que: (a) dispara la limpieza proactiva de imágenes viejas del propio proyecto antes del `pull`, y (b) si aun así no hay espacio suficiente, el deploy aborta con el mensaje de error explícito en vez de fallar a mitad del `docker pull`.
7. Confirmar que un proyecto consumidor que **no** pasa `GITLAB_REGISTRY_MAX_TAGS`/`REMOTE_MIN_DOCKER_IMAGES_TO_KEEP`/`REMOTE_DISK_USAGE_THRESHOLD_PERCENT` sigue construyendo/desplegando igual que antes (defaults aplicados, sin romper compatibilidad).
8. Provocar deliberadamente cada condición de error de los componentes A/B/C (ej. token de GitLab inválido, umbral de disco imposible de cumplir) y confirmar que el mensaje de Slack muestra la línea `CLEANUP_WARNING`/`CLEANUP_ERROR` con texto claro y específico, incluso en un build que termina en SUCCESS (para los casos no fatales).
9. Caso borde de C.1: dejar corriendo (sin detener) todos los contenedores de las imágenes "recortables" del propio proyecto en el servidor de prueba, de forma que ninguna se pueda borrar, y confirmar que el deploy aborta con el `CLEANUP_ERROR` de disco insuficiente en vez de fallar en silencio o a mitad del `pull`.

## Estado de implementación (código ya escrito)

Todos los archivos de "Archivos a tocar" fueron creados/modificados. Detalle en el historial de conversación. Diferencia respecto al diseño original: el Componente C no arma el bash inline dentro del `execCommand` de Groovy (hubiera requerido anidar comillas Groovy+bash+`bash -c`); en su lugar `vars/deploy.groovy` genera dos archivos `.cleanup_pre.sh`/`.cleanup_post.sh` con `writeFile` y los transfiere junto al `docker-compose.yml`, ejecutándolos como `bash .cleanup_pre.sh && ${CMD_DOCKER} && (bash .cleanup_post.sh || true)`. Misma lógica y mismo orden que el diseño.

## Cambios posteriores (fuera del diseño original)

- `buildImage.groovy` ahora buildea con `--label "cicd.project=${PATH_APP}"`, para poder scopear por proyecto las imágenes sin tag (`<none>:<none>`) al hacer prune tanto en el agente de Jenkins como en el servidor remoto. Limitación conocida: las imágenes intermedias de *multi-stage builds* nunca llevan label (Docker no las asocia a ningún nombre), así que ni en el agente ni en el remoto se limpian esas — solo la imagen final que perdió su tag al ser reemplazada por un build nuevo.
- `cleanDockerImages.groovy` (agente) ahora además deja un log rotativo por proyecto en `$HOME/.cicd_cleanup_logs/${PATH_APP}/` (un archivo por build, máximo `AGENT_CLEANUP_LOG_MAX_FILES` = 20 por defecto, se borra el más viejo al superar el máximo).
- `.cleanup_post.sh` (remoto, dentro de `deploy.groovy`) ahora también borra los contenedores caídos/detenidos (`status=exited`) del mismo proyecto antes de intentar borrar imágenes sin tag, para liberar su referencia.
- **Concurrencia en Componente C — descartada, no se implementa:** se había agregado un `lock("deploy-${env.HOST_REMOTE}-${env.PATH_APP}")` alrededor del stage `Deploy` en `defaultPipeline.groovy`, `goPipeline.groovy`, `laravelPipeline.groovy`, `nodePipeline.groovy` y `vitePipeline.groovy`, pero requería el plugin *Lockable Resources*, que no está instalado en este Jenkins. Se confirmó que por proceso solo se corre un build (prod, dev o test) a la vez, nunca en simultáneo, por lo que el riesgo de concurrencia de C.1/C.2 no aplica en la práctica — se quitó el `lock()` de los 5 pipelines para no depender de un plugin innecesario. Si en el futuro se corrieran builds en paralelo, este punto hay que revisarlo (instalar el plugin, o encadenar un `flock` remoto dentro de `remoteExecCommand()` en `deploy.groovy`).

## Pendiente (a futuro, no bloqueante)

- Prerrequisitos manuales resueltos: se reusa la credencial `GitLab-Registry` en vez de crear `GitLab-API-Token`, y *Pipeline Utility Steps* quedó descartado (no instalado, código migrado a `JsonSlurper`/`find`). Confirmado también: no se usa `lock()`/*Lockable Resources* (ver "Concurrencia en Componente C" arriba), no es un prerrequisito.
- Falta crear en Jenkins la credencial `GitLab-Group-API-Token` (Secret text, Group Access Token de `telco-${GITLAB_GROUP}` con scope `api` + rol Owner) que usan `gitlabRepoSetup.groovy` (Requisito de automatizar creación de repos) — token ya generado en GitLab, credencial en Jenkins pendiente de crear.
- `gitlabRepoSetup.groovy` (crea el repo en GitLab si no existe, y le sube un primer commit con la base de `copyConfEnv.X()`, excluyendo `config.json`/`.env`/`credenciales/`) todavía no se probó contra un Jenkins real — pendiente el plan de prueba de dos fases (repo+commit aislado primero, integración completa con `copyConfEnv` real después).
- Verificación manual (los 9 puntos de arriba) pendiente de correr contra un Jenkins real. El caso de dos deploys concurrentes del mismo proyecto ya no aplica: no hay `lock()`, y por proceso no se corren builds en simultáneo.
- Verificar (bajo esfuerzo) que `CLEANUP_WARNING:`/`CLEANUP_ERROR:` no puedan aparecer en salida normal de `docker`/`curl`/`git`.
- Si alguna vez se empezara a correr builds en paralelo contra el mismo host, retomar la protección de concurrencia: instalar *Lockable Resources*, o encadenar un `flock` remoto dentro de `remoteExecCommand()` en `deploy.groovy` (no requiere plugin).
