import groovy.json.JsonOutput
import groovy.json.JsonSlurper
 
/*
 * sonarMavenScan
 *
 * SonarQube analysis for MAVEN projects.
 * Node/NestJS projects keep using sonarScan (CLI scanner).
 *
 * Why a separate file:
 *   Uses the Sonar Maven plugin (mvn sonar:sonar) instead of
 *   the sonar-scanner CLI. The plugin reads sources, tests,
 *   compiled classes and JaCoCo reports from pom.xml for every
 *   module, so single and multi-module repos work without
 *   passing paths by hand.
 *
 * Must run AFTER mavenBuild (needs target/classes).
 * Does not run "clean", so the build output is reused.
 *
 * Same three phases and same result map as sonarScan,
 * so securityGate and build-info.json need no change:
 *   PHASE 1  submit analysis      (withSonarQubeEnv)
 *   PHASE 2  wait for quality gate (webhook)
 *   PHASE 3  read metrics and issue counts (report credential)
 *
 * Scan results NEVER fail the pipeline. A failed quality gate
 * or issues go to the Security Gate.
 *
 * These FAIL the pipeline (tool errors, not findings):
 *   - missing config, pom.xml, Java/Maven or compiled classes
 *   - Maven/Sonar plugin error (server down, bad token, ...)
 *   - quality gate result not received within the timeout
 *   - SonarQube API errors
 *
 * Usage:
 *   sonarResult = sonarMavenScan(
 *     sonarUrl              : env.SONAR_URL,
 *     serverName            : env.SONAR_SERVER_NAME,
 *     projectKey            : env.SONAR_PROJECT,
 *     projectName           : env.APP_NAME,
 *     analysisCredentialsId : env.SONAR_ANALYSIS_CRED_ID,
 *     reportCredentialsId   : env.SONAR_REPORT_CRED_ID
 *   )
 *
 * Optional config keys:
 *   exclusions          extra sonar.exclusions (comma separated)
 *   qualityGateTimeout  minutes to wait for the gate (default 10)
 *
 * Optional environment:
 *   MAVEN_SETTINGS     path to a settings.xml (adds -s)
 *   MAVEN_EXTRA_ARGS   extra arguments for mvn
 *
 * Returns:
 *   [status, projectKey, qualityGate, bugs, vulnerabilities,
 *    codeSmells, coverage, duplications,
 *    blocker, critical, major, minor]
 */
 
// Plugin version (full coordinates, so no pom change is needed)
@groovy.transform.Field String SONAR_MAVEN_PLUGIN =
    'org.sonarsource.scanner.maven:sonar-maven-plugin:4.0.0.4121'
 
def call(Map config = [:]) {
 
    def sonarUrl = config.sonarUrl?.toString()?.replaceAll('/+$', '')
    def projectKey = config.projectKey?.toString()
    def projectName = config.projectName?.toString() ?: projectKey
    def serverName = config.serverName?.toString() ?: 'SonarQube-Production'
    int gateTimeout = (config.qualityGateTimeout ?: 10) as int
 
    def analysisCredentialId =
        config.analysisCredentialsId?.toString()
 
    def reportCredentialId =
        config.reportCredentialsId?.toString()
 
    def exclusions = 'builds/**,.scannerwork/**'
 
    if (config.exclusions) {
        exclusions = "${exclusions},${config.exclusions}"
    }
 
    if (!sonarUrl ||
        !projectKey ||
        !analysisCredentialId ||
        !reportCredentialId) {
 
        env.FAILURE_REASON = 'SonarQube configuration is incomplete'
        error("""
SonarQube configuration is incomplete.
 
Required:
- sonarUrl
- projectKey
- analysisCredentialsId
- reportCredentialsId
""")
    }
 
    def buildDir = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/sonar"
    def logFile = "${reportDir}/sonar-maven.log"
 
    sh "mkdir -p '${reportDir}'"
 
    /*
     * Pre-checks
     */
    if (!fileExists('pom.xml')) {
        env.FAILURE_REASON = 'pom.xml not found in workspace root'
        error('pom.xml not found in the workspace root. Sonar Maven scan cannot start.')
    }
 
    boolean useWrapper = fileExists('mvnw')
    String mvnBin = useWrapper ? './mvnw' : 'mvn'
 
    if (useWrapper) {
        sh 'chmod +x mvnw'
    }
 
    String toolsCheck = useWrapper
        ? 'command -v java >/dev/null 2>&1'
        : 'command -v java >/dev/null 2>&1 && command -v mvn >/dev/null 2>&1'
 
    if (sh(script: toolsCheck, returnStatus: true) != 0) {
        env.FAILURE_REASON = 'Java or Maven not found on the agent'
        error("""
Java or Maven is not available on this agent.
 
Add to the Jenkinsfile tools block:
  maven 'Maven3'
  jdk 'JDK17'
""")
    }
 
    if (sh(script: "find . -path './builds' -prune -o -path '*/target/classes' -type d -print | grep -q .",
           returnStatus: true) != 0) {
 
        env.FAILURE_REASON = 'Compiled classes not found (target/classes)'
        error("""
Compiled classes were not found.
 
Sonar Java analysis needs target/classes.
Run the Maven build stage before SonarQube.
""")
    }
 
    echo """
=========================================
SONARQUBE ANALYSIS (MAVEN)
=========================================
 
Project      : ${projectName}
Project Key  : ${projectKey}
Server       : ${sonarUrl}
Scanner      : sonar-maven-plugin ${SONAR_MAVEN_PLUGIN.split(':')[-1]}
Wrapper      : ${useWrapper}
 
=========================================
"""
 
    /*
     * PHASE 1: Submit analysis.
     * withSonarQubeEnv passes the server URL and the
     * analysis token to the Maven plugin.
     */
    def args = ['-B', '-ntp']
 
    if (env.MAVEN_SETTINGS) {
        args << "-s '${env.MAVEN_SETTINGS}'"
    }
 
    if (env.MAVEN_EXTRA_ARGS) {
        args << env.MAVEN_EXTRA_ARGS
    }
 
    int exitCode
 
    withSonarQubeEnv(
        installationName: serverName,
        credentialsId: analysisCredentialId
    ) {
        withEnv([
            "SONAR_PROJECT_KEY=${projectKey}",
            "SONAR_PROJECT_NAME=${projectName}",
            "SONAR_EXCLUSIONS=${exclusions}"
        ]) {
            exitCode = sh(
                label: 'Run Sonar Maven plugin',
                script: """#!/bin/bash
set -o pipefail
${mvnBin} ${args.join(' ')} ${SONAR_MAVEN_PLUGIN}:sonar \\
    -Dsonar.projectKey="\$SONAR_PROJECT_KEY" \\
    -Dsonar.projectName="\$SONAR_PROJECT_NAME" \\
    -Dsonar.sourceEncoding=UTF-8 \\
    -Dsonar.exclusions="\$SONAR_EXCLUSIONS" \\
    2>&1 | tee '${logFile}'
""",
                returnStatus: true
            )
        }
    }
 
    if (exitCode != 0) {
 
        String errorLines = sh(
            script: "grep -m 15 '\\[ERROR\\]' '${logFile}' || true",
            returnStdout: true
        ).trim()
 
        env.FAILURE_REASON =
            "Sonar Maven analysis failed to run (exit code ${exitCode})"
 
        archiveArtifacts(
            artifacts: "${reportDir}/**",
            fingerprint: true,
            allowEmptyArchive: true
        )
 
        error("""
SONARQUBE ANALYSIS FAILED TO RUN
 
This is a pipeline error, not a code finding.
Check the server, the analysis token and the
Maven log.
 
Exit Code : ${exitCode}
 
First errors:
${errorLines ?: 'See ' + logFile}
 
Full log:
${logFile}
""")
    }
 
    /*
     * PHASE 2: Wait for the completed quality gate.
     *
     * Requires a SonarQube webhook pointing to:
     * https://<jenkins>/sonarqube-webhook/
     */
    def qualityGate
 
    try {
        timeout(time: gateTimeout, unit: 'MINUTES') {
            qualityGate = waitForQualityGate()
        }
    } catch (Exception ex) {
        env.FAILURE_REASON =
            "Sonar quality gate result not received within ${gateTimeout} minutes"
        error("""
SONARQUBE QUALITY GATE NOT RECEIVED
 
No result within ${gateTimeout} minutes.
 
Check the SonarQube webhook to:
https://<jenkins>/sonarqube-webhook/
 
Error: ${ex.getMessage()}
""")
    }
 
    def qualityGateStatus = qualityGate?.status ?: 'N/A'
 
    /*
     * PHASE 3: Read metrics and issues.
     *
     * This credential must have Browse permission
     * on the target project.
     */
    def encodedProjectKey =
        java.net.URLEncoder.encode(projectKey, 'UTF-8')
 
    def apiGet = { String apiPath, String outputFile ->
 
        def httpCode
 
        withEnv([
            "SONAR_API_URL=${sonarUrl}${apiPath}",
            "SONAR_OUTPUT_FILE=${outputFile}"
        ]) {
            httpCode = sh(
                label: 'Query SonarQube API',
                script: '''
                    set +x
 
                    curl --silent --show-error \
                        --output "$SONAR_OUTPUT_FILE" \
                        --write-out '%{http_code}' \
                        --user "$SONAR_TOKEN:" \
                        "$SONAR_API_URL"
                ''',
                returnStdout: true
            ).trim()
        }
 
        if (httpCode != '200') {
            env.FAILURE_REASON = "SonarQube API returned HTTP ${httpCode}"
            error(
                "SonarQube API returned HTTP ${httpCode} " +
                "for project ${projectKey}. " +
                "Check the reporting token, Browse permission, URL and API parameters."
            )
        }
 
        def response
 
        try {
            response = new JsonSlurper().parseText(
                readFile(outputFile)
            )
        } catch (Exception ex) {
            env.FAILURE_REASON = "Invalid SonarQube API JSON in ${outputFile}"
            error(
                "Invalid SonarQube API JSON in ${outputFile}: " +
                ex.getMessage()
            )
        }
 
        if (response.errors) {
            env.FAILURE_REASON = 'SonarQube API returned an error'
            error(
                "SonarQube API error: ${response.errors}"
            )
        }
 
        return response
    }
 
    def metrics
    def issueCounts = [
        blocker  : 0,
        critical : 0,
        major    : 0,
        minor    : 0
    ]
 
    withCredentials([
        string(
            credentialsId: reportCredentialId,
            variable: 'SONAR_TOKEN'
        )
    ]) {
 
        /*
         * Fetch project measures.
         */
        def measuresPath =
            "/api/measures/component" +
            "?component=${encodedProjectKey}" +
            "&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density"
 
        def measuresJson = apiGet(
            measuresPath,
            "${reportDir}/sonar-measures.json"
        )
 
        if (!measuresJson.component) {
            env.FAILURE_REASON = "SonarQube returned no component for ${projectKey}"
            error(
                "SonarQube returned no component for ${projectKey}. " +
                "Verify the project key and project access."
            )
        }
 
        metrics = [:]
 
        (measuresJson.component.measures ?: []).each {
            metrics[it.metric] = it.value
        }
 
        /*
         * Count unresolved issues by legacy severity.
         * API support can differ by SonarQube version.
         */
        def severityMap = [
            BLOCKER  : 'blocker',
            CRITICAL : 'critical',
            MAJOR    : 'major',
            MINOR    : 'minor'
        ]
 
        severityMap.each { severity, resultKey ->
 
            def issuesPath =
                "/api/issues/search" +
                "?componentKeys=${encodedProjectKey}" +
                "&resolved=false" +
                "&severities=${severity}" +
                "&ps=1"
 
            def issuesJson = apiGet(
                issuesPath,
                "${reportDir}/sonar-issues-${severity.toLowerCase()}.json"
            )
 
            issueCounts[resultKey] =
                (issuesJson.total ?: 0) as int
        }
    }
 
    def result = [
        status          : qualityGateStatus == 'OK' ? 'SUCCESS' : 'FAILURE',
        projectKey      : projectKey,
        qualityGate     : qualityGateStatus,
 
        bugs            : metrics['bugs'] ?: '0',
        vulnerabilities : metrics['vulnerabilities'] ?: '0',
        codeSmells      : metrics['code_smells'] ?: '0',
        coverage        : metrics['coverage'] ?: 'N/A',
        duplications    : metrics['duplicated_lines_density'] ?: '0',
 
        blocker         : issueCounts.blocker,
        critical        : issueCounts.critical,
        major           : issueCounts.major,
        minor           : issueCounts.minor
    ]
 
    writeFile(
        file: "${reportDir}/sonar-summary.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(result)
        )
    )
 
    writeFile(
        file: "${reportDir}/sonar-summary.txt",
        text: """
=========================================
SONARQUBE SUMMARY
=========================================
 
Project        : ${projectKey}
Status         : ${result.status}
Quality Gate   : ${result.qualityGate}
 
Bugs           : ${result.bugs}
Vulnerabilities: ${result.vulnerabilities}
Code Smells    : ${result.codeSmells}
Coverage       : ${result.coverage}%
Duplications   : ${result.duplications}%
 
Blocker        : ${result.blocker}
Critical       : ${result.critical}
Major          : ${result.major}
Minor          : ${result.minor}
 
=========================================
"""
    )
 
    archiveArtifacts(
        artifacts: "${reportDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )
 
    echo """
=========================================
SONARQUBE SUMMARY
=========================================
 
Project        : ${projectKey}
Quality Gate   : ${result.qualityGate}
Bugs           : ${result.bugs}
Vulnerabilities: ${result.vulnerabilities}
Code Smells    : ${result.codeSmells}
Coverage       : ${result.coverage}
Duplications   : ${result.duplications}
 
Blocker        : ${result.blocker}
Critical       : ${result.critical}
Major          : ${result.major}
Minor          : ${result.minor}
 
Reports        : ${reportDir}
 
=========================================
"""
 
    return result
}