import groovy.json.JsonOutput
 
/*
 * mavenBuild
 *
 * Builds a Maven project with: clean verify
 *
 * Behaviour:
 *   - Uses ./mvnw (Maven Wrapper) when the repo has one,
 *     otherwise the agent's mvn.
 *   - ENABLE_TESTS != 'true' : tests are skipped (-DskipTests),
 *     test sources are still compiled.
 *   - ENABLE_TESTS  = 'true' : tests run with JaCoCo coverage
 *     (plugin is called by full name, so no pom.xml change
 *     is needed). Failing tests FAIL the build.
 *   - Any Maven error (compile, dependency, plugin) FAILS
 *     the build. Nothing here is a "scan finding".
 *   - Finds the runnable jar (Spring Boot jar with a
 *     Start-Class entry, or the only jar) and stages it at
 *     builds/Build_N/app/app.jar for the Docker image.
 *     No runnable jar = pipeline failure.
 *
 * Requires JDK on the agent (Jenkinsfile tools block),
 * and Maven unless the repo has a wrapper.
 *
 * Optional environment:
 *   ENABLE_TESTS      'true' to run unit tests
 *   MAVEN_SETTINGS    path to a settings.xml (adds -s)
 *   MAVEN_EXTRA_ARGS  extra arguments for mvn
 *
 * Returns:
 *   [tool, status, testsMode, testsTotal, testsFailed,
 *    testsSkipped, coverageReport, javaVersion,
 *    mavenVersion, usedWrapper, artifacts, runnableJar,
 *    stagedJar, durationSeconds, exitCode]
 */
 
def call(Map config = [:]) {
 
    long startMillis = System.currentTimeMillis()
 
    def buildDir = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/maven-build"
    def appDir = "${buildDir}/app"
    def logFile = "${reportDir}/maven-build.log"
 
    boolean testsEnabled = (env.ENABLE_TESTS ?: 'false') == 'true'
    String testsMode = testsEnabled ? 'ENABLED' : 'SKIPPED'
 
    sh "mkdir -p '${reportDir}' '${appDir}'"
 
    /*
     * Pre-checks: fail fast with a clear reason
     */
    if (!fileExists('pom.xml')) {
        env.FAILURE_REASON = 'pom.xml not found in workspace root'
        error("""
pom.xml not found in the workspace root.
 
Maven build cannot start.
""")
    }
 
    boolean useWrapper = fileExists('mvnw')
    String mvnBin
 
    if (useWrapper) {
        sh 'chmod +x mvnw'
        mvnBin = './mvnw'
    } else {
        mvnBin = 'mvn'
    }
 
    int javaCheck = sh(
        script: 'command -v java >/dev/null 2>&1',
        returnStatus: true
    )
 
    if (javaCheck != 0) {
        env.FAILURE_REASON = 'Java not found on the agent'
        error("""
Java is not available on this agent.
 
Add to the Jenkinsfile tools block:
  jdk 'JDK17'
 
and configure it in Manage Jenkins > Tools.
""")
    }
 
    if (!useWrapper) {
        int mvnCheck = sh(
            script: 'command -v mvn >/dev/null 2>&1',
            returnStatus: true
        )
 
        if (mvnCheck != 0) {
            env.FAILURE_REASON = 'Maven not found on the agent'
            error("""
Maven is not available on this agent and the repo
has no Maven Wrapper (mvnw).
 
Add to the Jenkinsfile tools block:
  maven 'Maven3'
 
and configure it in Manage Jenkins > Tools.
""")
        }
    }
 
    String javaVersion = sh(
        script: 'java -version 2>&1 | head -1',
        returnStdout: true
    ).trim()
 
    String mavenVersion = sh(
        script: "${mvnBin} -v 2>&1 | head -1",
        returnStdout: true
    ).trim()
 
    echo """
====================================
MAVEN BUILD
====================================
 
Java    : ${javaVersion}
Maven   : ${mavenVersion}
Wrapper : ${useWrapper}
Tests   : ${testsMode}
 
====================================
"""
 
    /*
     * Build the Maven command
     */
    def args = ['-B', '-ntp']
 
    if (env.MAVEN_SETTINGS) {
        args << "-s '${env.MAVEN_SETTINGS}'"
    }
 
    if (env.MAVEN_EXTRA_ARGS) {
        args << env.MAVEN_EXTRA_ARGS
    }
 
    String jacoco = 'org.jacoco:jacoco-maven-plugin:0.8.12'
    String goals
 
    if (testsEnabled) {
        goals = "clean ${jacoco}:prepare-agent verify ${jacoco}:report"
    } else {
        goals = 'clean verify -DskipTests'
    }
 
    String mvnCommand = "${mvnBin} ${args.join(' ')} ${goals}"
 
    echo "Running: ${mvnCommand}"
 
    /*
     * Run Maven. Output goes to the console and to a log file.
     * pipefail keeps Maven's exit code through tee.
     */
    int exitCode = sh(
        script: """#!/bin/bash
set -o pipefail
${mvnCommand} 2>&1 | tee '${logFile}'
""",
        returnStatus: true
    )
 
    /*
     * Test results (only when tests are enabled)
     */
    int testsTotal = 0
    int testsFailed = 0
    int testsSkipped = 0
 
    if (testsEnabled) {
        def testResult = junit(
            allowEmptyResults: true,
            testResults: '**/target/surefire-reports/*.xml'
        )
 
        testsTotal = testResult.totalCount
        testsFailed = testResult.failCount
        testsSkipped = testResult.skipCount
    }
 
    /*
     * Artifacts and runnable jar (only if Maven succeeded)
     */
    def artifacts = []
    String runnableJar = ''
    String stagedJar = ''
    String jarProblem = ''
 
    if (exitCode == 0) {
 
        String artifactList = sh(
            script: '''
                find . -path './builds' -prune -o \
                    -path '*/target/*.jar' -type f \
                    ! -name 'original-*' \
                    ! -name '*-sources.jar' \
                    ! -name '*-javadoc.jar' \
                    ! -name '*-plain.jar' \
                    -print | sed 's|^\\./||' | sort
            ''',
            returnStdout: true
        ).trim()
 
        artifacts = artifactList ? artifactList.split('\n').toList() : []
 
        /*
         * Spring Boot executable jars have a Start-Class entry.
         */
        String bootList = sh(
            script: '''
                find . -path './builds' -prune -o \
                    -path '*/target/*.jar' -type f \
                    ! -name 'original-*' \
                    ! -name '*-sources.jar' \
                    ! -name '*-javadoc.jar' \
                    ! -name '*-plain.jar' \
                    -print | sed 's|^\\./||' | sort | while read -r jar; do
                    if unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null \
                        | grep -q '^Start-Class:'; then
                        echo "$jar"
                    fi
                done
            ''',
            returnStdout: true
        ).trim()
 
        def bootJars = bootList ? bootList.split('\n').toList() : []
 
        if (bootJars.size() == 1) {
            runnableJar = bootJars[0]
        } else if (bootJars.size() > 1) {
            jarProblem =
                "Multiple Spring Boot jars found: ${bootJars.join(', ')}"
        } else if (artifacts.size() == 1) {
            runnableJar = artifacts[0]
        } else if (artifacts.size() > 1) {
            jarProblem =
                "Multiple jars found and none is a Spring Boot jar: ${artifacts.join(', ')}"
        } else {
            jarProblem =
                'No jar was produced (check packaging in pom.xml; war is not supported yet)'
        }
 
        if (runnableJar) {
            stagedJar = "${appDir}/app.jar"
            sh "cp '${runnableJar}' '${stagedJar}'"
        }
    }
 
    String coverageReport = 'N/A'
 
    if (testsEnabled &&
        sh(script: "find . -path '*/target/site/jacoco/jacoco.xml' | head -1 | grep -q .",
           returnStatus: true) == 0) {
 
        coverageReport = 'target/site/jacoco/jacoco.xml'
    }
 
    long durationSeconds =
        (System.currentTimeMillis() - startMillis).intdiv(1000)
 
    String status = (exitCode == 0 && !jarProblem) ? 'SUCCESS' : 'FAILURE'
 
    def summary = [
        tool            : 'Maven',
        status          : status,
        testsMode       : testsMode,
        testsTotal      : testsTotal,
        testsFailed     : testsFailed,
        testsSkipped    : testsSkipped,
        coverageReport  : coverageReport,
        javaVersion     : javaVersion,
        mavenVersion    : mavenVersion,
        usedWrapper     : useWrapper,
        artifacts       : artifacts,
        runnableJar     : runnableJar ?: 'N/A',
        stagedJar       : stagedJar ?: 'N/A',
        durationSeconds : durationSeconds,
        exitCode        : exitCode
    ]
 
    writeFile(
        file: "${reportDir}/maven-build-summary.json",
        text: JsonOutput.prettyPrint(JsonOutput.toJson(summary))
    )
 
    writeFile(
        file: "${reportDir}/maven-build-summary.txt",
        text: """
=========================================
MAVEN BUILD SUMMARY
=========================================
 
Build Number : ${env.BUILD_NUMBER}
Status       : ${status}
Exit Code    : ${exitCode}
Duration     : ${durationSeconds}s
 
Java         : ${javaVersion}
Maven        : ${mavenVersion}
Wrapper      : ${useWrapper}
 
Tests        : ${testsMode}
Total        : ${testsTotal}
Failed       : ${testsFailed}
Skipped      : ${testsSkipped}
Coverage     : ${coverageReport}
 
Artifacts    : ${artifacts ? artifacts.join(', ') : 'none'}
Runnable Jar : ${runnableJar ?: 'N/A'}
 
=========================================
"""
    )
 
    archiveArtifacts(
        artifacts: "${reportDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )
 
    if (artifacts) {
        archiveArtifacts(
            artifacts: artifacts.join(','),
            fingerprint: true,
            allowEmptyArchive: true
        )
    }
 
    echo """
=========================================
MAVEN BUILD SUMMARY
=========================================
 
Status       : ${status}
Duration     : ${durationSeconds}s
Tests        : ${testsMode}
Total        : ${testsTotal}
Failed       : ${testsFailed}
Skipped      : ${testsSkipped}
Artifacts    : ${artifacts.size()}
Runnable Jar : ${runnableJar ?: 'N/A'}
 
Reports      : ${reportDir}
 
=========================================
"""
 
    currentBuild.description =
        (currentBuild.description ?: '') +
        " | Build[${status}]"
 
    /*
     * Build errors FAIL the pipeline.
     */
    if (exitCode != 0) {
 
        String errorLines = sh(
            script: "grep -m 15 '\\[ERROR\\]' '${logFile}' || true",
            returnStdout: true
        ).trim()
 
        String reason
 
        if (testsEnabled && testsFailed > 0) {
            reason = "${testsFailed} unit test(s) failed"
        } else {
            reason = "Maven build failed (exit code ${exitCode})"
        }
 
        env.FAILURE_REASON = reason
 
        error("""
MAVEN BUILD FAILED
 
Reason    : ${reason}
Exit Code : ${exitCode}
 
First errors:
${errorLines ?: 'See ' + logFile}
 
Full log:
${logFile}
""")
    }
 
    if (jarProblem) {
 
        env.FAILURE_REASON = jarProblem
 
        error("""
RUNNABLE JAR NOT FOUND
 
Reason : ${jarProblem}
 
The Docker image needs exactly one runnable jar.
""")
    }
 
    return summary
}