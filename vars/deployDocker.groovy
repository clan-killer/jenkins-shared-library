import groovy.json.JsonOutput
 
/*
 * dockerDeploy
 *
 * DOCKER mode deploy. Runs the approved image as a
 * background container on the Jenkins agent.
 *
 * Steps:
 *   1. Check Docker and the image <imageName>:<imageTag>
 *   2. Note the image the old container was running (rollback info)
 *   3. Remove the old container
 *   4. docker run -d --restart unless-stopped
 *   5. Check the container stays running and does not restart
 *      during startup (shows last log lines if it fails)
 *   6. Tag the image as <imageName>:latest
 *      (latest only ever points at a deployed, approved image)
 *
 * Anything that goes wrong FAILS the pipeline. The HTTP
 * health check is a separate stage that runs afterwards.
 *
 * Usage:
 *   deployResult = dockerDeploy(
 *     appName   : env.APP_NAME,       // container name
 *     imageName : env.IMAGE_NAME,
 *     imageTag  : env.BUILD_NUMBER,
 *     port      : cfg.appPort         // container port
 *   )
 *
 * Optional environment:
 *   HOST_PORT          host port (default: same as app port)
 *   JAVA_OPTS          passed into the container (Java images)
 *   DOCKER_RUN_ARGS    extra docker run arguments
 *                      (for example --memory 1g or -e KEY=VALUE)
 *
 * Rollback (manual):
 *   docker rm -f <app>
 *   docker run -d --name <app> --restart unless-stopped \
 *     -p <port>:<port> <previous image>
 *
 * Returns:
 *   [tool, status, appName, image, containerId, previousImage,
 *    hostPort, containerPort, durationSeconds, exitCode]
 */
 
def call(Map config = [:]) {
 
    long startMillis = System.currentTimeMillis()
 
    String appName = config.appName?.toString()
    String imageName = config.imageName?.toString()
    String imageTag = (config.imageTag ?: env.BUILD_NUMBER)?.toString()
    String port = config.port?.toString()
    String hostPort = (env.HOST_PORT ?: port)?.toString()
 
    /*
     * Validate inputs
     */
    if (!appName || !(appName ==~ /^[A-Za-z0-9][A-Za-z0-9_.-]*$/)) {
        env.FAILURE_REASON = "Invalid container name: ${appName}"
        error("""
Invalid container name: ${appName}
 
Allowed characters: letters, digits, dot, dash, underscore.
""")
    }
 
    if (!imageName || !imageTag) {
        env.FAILURE_REASON = 'Docker deploy needs imageName and imageTag'
        error('Docker deploy needs imageName and imageTag.')
    }
 
    if (!port || !(port ==~ /^[0-9]{2,5}$/) ||
        !hostPort || !(hostPort ==~ /^[0-9]{2,5}$/)) {
 
        env.FAILURE_REASON = "Invalid port for Docker deploy: ${hostPort}:${port}"
        error("Invalid port mapping: ${hostPort}:${port}")
    }
 
    String image = "${imageName}:${imageTag}"
 
    def buildDir = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/deploy-docker"
    def scriptFile = "${reportDir}/deploy-docker.sh"
    def resultFile = "${reportDir}/deploy-result.env"
    def logFile = "${reportDir}/deploy-docker.log"
 
    sh "mkdir -p '${reportDir}'"
 
    echo """
====================================
DEPLOY (DOCKER MODE)
====================================
 
Container   : ${appName}
Image       : ${image}
Ports       : ${hostPort} (host) -> ${port} (container)
Restart     : unless-stopped
 
====================================
"""
 
    /*
     * The deploy script. Single quoted so nothing is expanded
     * by Groovy. Values arrive as environment variables.
     *
     * Exit codes:
     *   20 Docker not available
     *   21 image not found
     *   22 could not remove the old container
     *   23 docker run failed (port busy, bad options, ...)
     *   24 container stopped or restarted during startup
     */
    String script = '''#!/bin/bash
set -u
 
echo "===== CHECK DOCKER ====="
docker info >/dev/null 2>&1 || exit 20
docker image inspect "$IMAGE" >/dev/null 2>&1 || exit 21
 
echo "===== PREVIOUS CONTAINER ====="
PREV_IMAGE=""
if docker container inspect "$APP_NAME" >/dev/null 2>&1; then
  PREV_IMAGE=$(docker container inspect --format '{{.Config.Image}}' "$APP_NAME")
  echo "Found $APP_NAME running image $PREV_IMAGE, removing it"
  docker rm -f "$APP_NAME" >/dev/null || exit 22
else
  echo "No previous container"
fi
 
echo "===== START CONTAINER ====="
EXTRA_ENV=()
if [ -n "${JAVA_OPTS_IN:-}" ]; then
  EXTRA_ENV=(-e "JAVA_OPTS=$JAVA_OPTS_IN")
fi
 
CONTAINER_ID=$(docker run -d \\
  --name "$APP_NAME" \\
  --restart unless-stopped \\
  -p "$HOST_PORT:$CONTAINER_PORT" \\
  -e "APP_PORT=$CONTAINER_PORT" \\
  ${EXTRA_ENV[@]+"${EXTRA_ENV[@]}"} \\
  --label "build.number=$BUILD_NUMBER" \\
  $RUN_ARGS \\
  "$IMAGE") || exit 23
 
echo "Container ID: $CONTAINER_ID"
 
echo "===== VERIFY STARTUP (${STARTUP_WAIT}s) ====="
for i in $(seq 1 "$STARTUP_WAIT"); do
  sleep 1
  RUNNING=$(docker inspect --format '{{.State.Running}}' "$APP_NAME" 2>/dev/null)
  RESTARTS=$(docker inspect --format '{{.RestartCount}}' "$APP_NAME" 2>/dev/null)
  if [ "$RUNNING" != "true" ] || [ "${RESTARTS:-0}" -gt 0 ]; then
    echo "Container stopped or restarted during startup."
    echo "Running=$RUNNING Restarts=$RESTARTS"
    echo "Last 30 log lines:"
    docker logs --tail 30 "$APP_NAME" 2>&1
    # stop the crash loop, keep the container for docker logs
    docker update --restart no "$APP_NAME" >/dev/null 2>&1
    docker stop "$APP_NAME" >/dev/null 2>&1
    {
      echo "CONTAINER_ID=$CONTAINER_ID"
      echo "PREVIOUS_IMAGE=$PREV_IMAGE"
    } > "$RESULT_FILE"
    exit 24
  fi
done
 
echo "Container running"
 
echo "===== TAG LATEST ====="
docker tag "$IMAGE" "$IMAGE_NAME:latest" \\
  && echo "Tagged $IMAGE_NAME:latest" \\
  || echo "WARNING: could not tag $IMAGE_NAME:latest"
 
{
  echo "CONTAINER_ID=$CONTAINER_ID"
  echo "PREVIOUS_IMAGE=$PREV_IMAGE"
} > "$RESULT_FILE"
 
exit 0
'''
 
    writeFile(file: scriptFile, text: script)
 
    int exitCode
 
    withEnv([
        "APP_NAME=${appName}",
        "IMAGE=${image}",
        "IMAGE_NAME=${imageName}",
        "HOST_PORT=${hostPort}",
        "CONTAINER_PORT=${port}",
        "JAVA_OPTS_IN=${env.JAVA_OPTS ?: ''}",
        "RUN_ARGS=${env.DOCKER_RUN_ARGS ?: ''}",
        'STARTUP_WAIT=15',
        "RESULT_FILE=${pwd()}/${resultFile}"
    ]) {
        exitCode = sh(
            script: """#!/bin/bash
set -o pipefail
bash '${scriptFile}' 2>&1 | tee '${logFile}'
""",
            returnStatus: true
        )
    }
 
    echo "Deploy Exit Code: ${exitCode}"
 
    /*
     * Read the result written by the script (if any)
     */
    def result = [:]
 
    if (fileExists(resultFile)) {
        readFile(resultFile).split('\n').each { String line ->
            int idx = line.indexOf('=')
            if (idx > 0) {
                result[line.substring(0, idx)] = line.substring(idx + 1).trim()
            }
        }
    }
 
    String previousImage = result.PREVIOUS_IMAGE ?: 'none'
 
    if (exitCode != 0) {
 
        String reason
 
        switch (exitCode) {
            case 20:
                reason = 'Docker daemon not reachable'
                break
            case 21:
                reason = "Image not found: ${image}"
                break
            case 22:
                reason = "Could not remove old container ${appName}"
                break
            case 23:
                reason = "docker run failed (is port ${hostPort} already in use?)"
                break
            case 24:
                reason = "Container ${appName} stopped or restarted during startup"
                break
            default:
                reason = "Docker deploy failed (exit code ${exitCode})"
        }
 
        env.FAILURE_REASON = reason
 
        writeFile(
            file: "${reportDir}/docker-deploy-summary.json",
            text: JsonOutput.prettyPrint(JsonOutput.toJson([
                tool          : 'Docker',
                status        : 'FAILURE',
                appName       : appName,
                image         : image,
                previousImage : previousImage,
                exitCode      : exitCode,
                reason        : reason
            ]))
        )
 
        archiveArtifacts(
            artifacts: "${reportDir}/**",
            fingerprint: true,
            allowEmptyArchive: true
        )
 
        error("""
DOCKER DEPLOY FAILED
 
Reason         : ${reason}
Exit Code      : ${exitCode}
 
Previous Image : ${previousImage}
 
Deploy log:
${logFile}
 
Container log:
docker logs ${appName}
 
Rollback:
docker rm -f ${appName}
docker run -d --name ${appName} --restart unless-stopped \\
  -p ${hostPort}:${port} ${previousImage}
""")
    }
 
    long durationSeconds =
        (System.currentTimeMillis() - startMillis).intdiv(1000)
 
    def summary = [
        tool            : 'Docker',
        status          : 'SUCCESS',
        appName         : appName,
        image           : image,
        containerId     : (result.CONTAINER_ID ?: 'N/A').take(12),
        previousImage   : previousImage,
        hostPort        : hostPort,
        containerPort   : port,
        durationSeconds : durationSeconds,
        exitCode        : exitCode
    ]
 
    writeFile(
        file: "${reportDir}/docker-deploy-summary.json",
        text: JsonOutput.prettyPrint(JsonOutput.toJson(summary))
    )
 
    archiveArtifacts(
        artifacts: "${reportDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )
 
    currentBuild.description =
        (currentBuild.description ?: '') +
        ' | Deploy[DOCKER]'
 
    echo """
=========================================
DOCKER DEPLOY SUMMARY
=========================================
 
Status         : ${summary.status}
Container      : ${appName}
Container ID   : ${summary.containerId}
Image          : ${image}
Ports          : ${hostPort} -> ${port}
Restart        : unless-stopped
 
Previous Image : ${previousImage}
Latest Tag     : ${imageName}:latest
 
Duration       : ${durationSeconds}s
 
Logs           : docker logs -f ${appName}
 
Rollback       :
docker rm -f ${appName}
docker run -d --name ${appName} --restart unless-stopped \\
  -p ${hostPort}:${port} <previous image>
 
=========================================
"""
 
    return summary
}