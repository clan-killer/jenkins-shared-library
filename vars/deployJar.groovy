import groovy.json.JsonOutput
 
/*
 * deployJar
 *
 * JAR mode deploy. Starts the application in the background
 * with nohup java -jar, on the Jenkins agent itself.
 *
 * Folder layout:
 *   <appDir>/releases/<BUILD_NUMBER>/app.jar   every deployed build
 *   <appDir>/current  -> releases/<BUILD_NUMBER>   symlink
 *   <appDir>/app.pid                                running process
 *   <logDir>/app.log                                application log
 *
 * Steps:
 *   1. Copy the jar into a new release folder
 *   2. Stop the previous instance (SIGTERM, then SIGKILL)
 *   3. Check that the port is free
 *   4. Point "current" at the new release
 *   5. Start with nohup (survives the end of the Jenkins job)
 *   6. Check the process stays alive during startup
 *   7. Keep the newest N releases for rollback, delete older
 *
 * Anything that goes wrong FAILS the pipeline. The HTTP
 * health check is a separate stage that runs afterwards.
 *
 * Usage:
 *   deployJar(
 *     appName : env.APP_NAME,
 *     jarPath : buildResult.stagedJar,
 *     port    : cfg.appPort,
 *     jar     : cfg.jar          // from projectConfig (JAR mode)
 *   )
 *
 * cfg.jar keys: appDir, logDir, jvmOpts, keepVersions
 *
 * Rollback (manual): point "current" at an older release,
 * then restart:
 *   ln -sfn <appDir>/releases/<N> <appDir>/current
 *
 * Returns:
 *   [tool, status, appName, release, pid, previousRelease,
 *    port, appDir, logFile, jvmOpts, durationSeconds, exitCode]
 */
 
def call(Map config = [:]) {
 
    long startMillis = System.currentTimeMillis()
 
    String appName = config.appName?.toString()
    String jarPath = config.jarPath?.toString()
    String port = config.port?.toString()
    Map jarCfg = (config.jar ?: [:]) as Map
 
    /*
     * Validate inputs. Names are checked because they become
     * folder paths that this file creates and cleans up.
     */
    if (!appName || !(appName ==~ /^[A-Za-z0-9._-]+$/)) {
        env.FAILURE_REASON = "Invalid app name for JAR deploy: ${appName}"
        error("""
Invalid app name: ${appName}
 
Allowed characters: letters, digits, dot, dash, underscore.
""")
    }
 
    if (!port || !(port ==~ /^[0-9]{2,5}$/)) {
        env.FAILURE_REASON = "Invalid port for JAR deploy: ${port}"
        error("Invalid application port: ${port}")
    }
 
    if (!jarPath || !fileExists(jarPath)) {
        env.FAILURE_REASON = "Jar to deploy not found: ${jarPath}"
        error("""
The jar to deploy was not found.
 
Path: ${jarPath}
 
The Maven build stage must run first and
produce builds/Build_N/app/app.jar.
""")
    }
 
    String appDir = jarCfg.appDir?.toString() ?: "/opt/apps/${appName}"
    String logDir = jarCfg.logDir?.toString() ?: "/var/log/${appName}"
    String jvmOpts = jarCfg.jvmOpts?.toString() ?: '-Xms512m -Xmx1g'
    int keepVersions = (jarCfg.keepVersions ?: 5) as int
 
    if (!appDir.startsWith('/') || !logDir.startsWith('/') ||
        appDir == '/' || logDir == '/') {
 
        env.FAILURE_REASON = 'appDir and logDir must be absolute paths'
        error("""
appDir and logDir must be absolute paths.
 
appDir : ${appDir}
logDir : ${logDir}
""")
    }
 
    if (keepVersions < 2) {
        keepVersions = 2
    }
 
    def buildDir = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/deploy-jar"
    def scriptFile = "${reportDir}/deploy-jar.sh"
    def resultFile = "${reportDir}/deploy-result.env"
    def logFile = "${reportDir}/deploy-jar.log"
 
    sh "mkdir -p '${reportDir}'"
 
    echo """
====================================
DEPLOY (JAR MODE)
====================================
 
Application : ${appName}
Jar         : ${jarPath}
Port        : ${port}
App Folder  : ${appDir}
Log Folder  : ${logDir}
JVM Options : ${jvmOpts}
Keep        : ${keepVersions} releases
 
====================================
"""
 
    /*
     * The deploy script. Single quoted so nothing is expanded
     * by Groovy. Values arrive as environment variables.
     *
     * Exit codes:
     *   10 cannot prepare folders or copy the jar
     *   11 could not stop the previous instance
     *   12 port still in use
     *   13 application died during startup
     */
    String script = '''#!/bin/bash
set -u
 
RELEASES="$APP_DIR/releases"
RELEASE="$RELEASES/$BUILD_NUMBER"
CURRENT="$APP_DIR/current"
PID_FILE="$APP_DIR/app.pid"
LOG_FILE="$LOG_DIR/app.log"
JAR_IN_USE="$CURRENT/app.jar"
 
echo "===== PREPARE ====="
mkdir -p "$RELEASE" "$LOG_DIR" || exit 10
cp "$JAR_SRC" "$RELEASE/app.jar" || exit 10
[ -s "$RELEASE/app.jar" ] || exit 10
touch "$LOG_FILE" || exit 10
 
PREV_RELEASE=""
if [ -L "$CURRENT" ]; then
  PREV_RELEASE=$(readlink "$CURRENT")
fi
 
stop_pid() {
  local pid="$1"
  kill "$pid" 2>/dev/null || return 0
  for i in $(seq 1 "$STOP_WAIT"); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  echo "PID $pid did not stop in ${STOP_WAIT}s, sending SIGKILL"
  kill -9 "$pid" 2>/dev/null
  sleep 1
  if kill -0 "$pid" 2>/dev/null; then
    return 1
  fi
  return 0
}
 
echo "===== STOP PREVIOUS INSTANCE ====="
if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE")
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    if ps -p "$OLD_PID" -o args= | grep -q -F "$JAR_IN_USE"; then
      echo "Stopping PID $OLD_PID"
      stop_pid "$OLD_PID" || exit 11
    else
      echo "PID $OLD_PID in pid file is not this application, ignoring"
    fi
  fi
fi
 
# Orphans of this application that have no valid pid file
for pid in $(pgrep -f -- "$JAR_IN_USE" 2>/dev/null); do
  echo "Stopping orphan PID $pid"
  stop_pid "$pid" || exit 11
done
 
echo "===== CHECK PORT $APP_PORT ====="
# ss when installed, otherwise bash's built-in /dev/tcp
port_in_use() {
  if command -v ss >/dev/null 2>&1; then
    ss -ltn "( sport = :$APP_PORT )" | grep -q LISTEN
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$APP_PORT") 2>/dev/null
  fi
}
 
for i in $(seq 1 10); do
  if port_in_use; then
    echo "Port $APP_PORT busy, waiting ($i/10)"
    sleep 1
  else
    break
  fi
done
 
if port_in_use; then
  echo "Port $APP_PORT is still in use by another process"
  exit 12
fi
echo "Port $APP_PORT is free"
 
echo "===== ACTIVATE RELEASE $BUILD_NUMBER ====="
ln -sfn "$RELEASE" "$CURRENT" || exit 10
 
echo "===== START APPLICATION ====="
export JENKINS_NODE_COOKIE=dontKillMe
export BUILD_ID=dontKillMe
 
cd "$APP_DIR" || exit 10
 
echo "===== $(date '+%F %T') build $BUILD_NUMBER starting =====" >> "$LOG_FILE"
 
LAUNCH="nohup"
if command -v setsid >/dev/null 2>&1; then
  LAUNCH="setsid nohup"
fi
 
$LAUNCH java $JVM_OPTS -Dserver.port="$APP_PORT" -jar "$JAR_IN_USE" \\
  >> "$LOG_FILE" 2>&1 < /dev/null &
 
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"
 
echo "===== VERIFY STARTUP (${STARTUP_WAIT}s) ====="
for i in $(seq 1 "$STARTUP_WAIT"); do
  sleep 1
  REAL_PID=$(pgrep -f -- "$JAR_IN_USE" | head -1)
  if [ -z "$REAL_PID" ]; then
    echo "Application exited during startup. Last 30 log lines:"
    tail -n 30 "$LOG_FILE"
    exit 13
  fi
done
 
echo "$REAL_PID" > "$PID_FILE"
echo "Application running, PID $REAL_PID"
 
echo "===== CLEAN OLD RELEASES (keep $KEEP_VERSIONS) ====="
cd "$RELEASES" || exit 10
for r in $(ls -1 | sort -rn | tail -n +$((KEEP_VERSIONS + 1))); do
  if [ "$RELEASES/$r" != "$RELEASE" ] && [ "$RELEASES/$r" != "$PREV_RELEASE" ]; then
    echo "Removing old release $r"
    rm -rf "${RELEASES:?}/$r"
  fi
done
 
{
  echo "PID=$REAL_PID"
  echo "RELEASE=$RELEASE"
  echo "PREVIOUS_RELEASE=$PREV_RELEASE"
} > "$RESULT_FILE"
 
exit 0
'''
 
    writeFile(file: scriptFile, text: script)
 
    int exitCode
 
    withEnv([
        "APP_DIR=${appDir}",
        "LOG_DIR=${logDir}",
        "JAR_SRC=${pwd()}/${jarPath}",
        "APP_PORT=${port}",
        "JVM_OPTS=${jvmOpts}",
        "KEEP_VERSIONS=${keepVersions}",
        'STOP_WAIT=30',
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
 
    String applicationLog = "${logDir}/app.log"
 
    if (exitCode != 0) {
 
        String reason
 
        switch (exitCode) {
            case 10:
                reason = "Cannot prepare ${appDir} or ${logDir} " +
                    '(check folder permissions for the Jenkins user)'
                break
            case 11:
                reason = 'Could not stop the previous instance'
                break
            case 12:
                reason = "Port ${port} is still in use by another process"
                break
            case 13:
                reason = "Application exited during startup (see ${applicationLog})"
                break
            default:
                reason = "JAR deploy failed (exit code ${exitCode})"
        }
 
        env.FAILURE_REASON = reason
 
        writeFile(
            file: "${reportDir}/jar-deploy-summary.json",
            text: JsonOutput.prettyPrint(JsonOutput.toJson([
                tool    : 'JAR',
                status  : 'FAILURE',
                appName : appName,
                exitCode: exitCode,
                reason  : reason
            ]))
        )
 
        archiveArtifacts(
            artifacts: "${reportDir}/**",
            fingerprint: true,
            allowEmptyArchive: true
        )
 
        error("""
JAR DEPLOY FAILED
 
Reason    : ${reason}
Exit Code : ${exitCode}
 
Deploy log:
${logFile}
 
Application log:
${applicationLog}
""")
    }
 
    /*
     * Read the result written by the script
     */
    def result = [:]
 
    readFile(resultFile).split('\n').each { String line ->
        int idx = line.indexOf('=')
        if (idx > 0) {
            result[line.substring(0, idx)] = line.substring(idx + 1).trim()
        }
    }
 
    long durationSeconds =
        (System.currentTimeMillis() - startMillis).intdiv(1000)
 
    def summary = [
        tool            : 'JAR',
        status          : 'SUCCESS',
        appName         : appName,
        release         : result.RELEASE ?: 'N/A',
        pid             : result.PID ?: 'N/A',
        previousRelease : result.PREVIOUS_RELEASE ?: 'none',
        port            : port,
        appDir          : appDir,
        logFile         : applicationLog,
        jvmOpts         : jvmOpts,
        durationSeconds : durationSeconds,
        exitCode        : exitCode
    ]
 
    writeFile(
        file: "${reportDir}/jar-deploy-summary.json",
        text: JsonOutput.prettyPrint(JsonOutput.toJson(summary))
    )
 
    archiveArtifacts(
        artifacts: "${reportDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )
 
    currentBuild.description =
        (currentBuild.description ?: '') +
        ' | Deploy[JAR]'
 
    echo """
=========================================
JAR DEPLOY SUMMARY
=========================================
 
Status           : ${summary.status}
Application      : ${appName}
Build            : ${env.BUILD_NUMBER}
PID              : ${summary.pid}
Port             : ${port}
 
Release          : ${summary.release}
Previous Release : ${summary.previousRelease}
 
App Folder       : ${appDir}
Application Log  : ${applicationLog}
JVM Options      : ${jvmOpts}
 
Duration         : ${durationSeconds}s
 
Rollback         :
ln -sfn <previous release> ${appDir}/current
then restart the application
 
=========================================
"""
 
    return summary
}
 