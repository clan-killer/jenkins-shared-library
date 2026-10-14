 
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
 
def call(Map config = [:]) {
 
    def sonarUrl = config.sonarUrl?.toString()?.replaceAll('/+$', '')
    def projectKey = config.projectKey?.toString()
    def projectName = config.projectName?.toString() ?: projectKey
    def serverName = config.serverName?.toString() ?: 'SonarQube-Production'
    def scannerTool = config.scannerTool?.toString() ?: 'SonarScanner'
    def sources = config.sources?.toString() ?: 'src'
 
    def analysisCredentialId =
        config.analysisCredentialsId?.toString()
 
    def reportCredentialId =
        config.reportCredentialsId?.toString()
 
    if (!sonarUrl ||
        !projectKey ||
        !analysisCredentialId ||
        !reportCredentialId) {
 
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
 
    sh "mkdir -p '${reportDir}'"
 
    def scannerHome = tool scannerTool
 
    echo """
=========================================
SONARQUBE ANALYSIS
=========================================
 
Project      : ${projectName}
Project Key  : ${projectKey}
Server       : ${sonarUrl}
Scanner Tool : ${scannerTool}
 
=========================================
"""
 
    /*
     * PHASE 1: Submit analysis.
     * The Jenkins SonarQube plugin supplies the
     * selected project's analysis credential.
     */
 
    withSonarQubeEnv(
        installationName: serverName,
        credentialsId: analysisCredentialId
    ) {
        withEnv([
            "SONAR_PROJECT_KEY=${projectKey}",
            "SONAR_PROJECT_NAME=${projectName}",
            "SONAR_SOURCES=${sources}",
            "SONAR_SCANNER_BIN=${scannerHome}/bin/sonar-scanner"
        ]) {
            sh(
                label: 'Run SonarScanner',
                script: '''
                    set -eu
 
                    "$SONAR_SCANNER_BIN" \
                      -Dsonar.projectKey="$SONAR_PROJECT_KEY" \
                      -Dsonar.projectName="$SONAR_PROJECT_NAME" \
                      -Dsonar.sources="$SONAR_SOURCES" \
                      -Dsonar.sourceEncoding=UTF-8 \
                      -Dsonar.exclusions="node_modules/**,dist/**,coverage/**,builds/**"
                '''
            )
        }
    }
 
    /*
     * PHASE 2: Wait for the completed quality gate.
     *
     * Requires a SonarQube webhook pointing to:
     * https://jenkins.example.com/sonarqube-webhook/
     */
 
    def qualityGate
 
    timeout(time: 10, unit: 'MINUTES') {
        qualityGate = waitForQualityGate()
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
            error(
                "Invalid SonarQube API JSON in ${outputFile}: " +
                ex.getMessage()
            )
        }
 
        if (response.errors) {
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
        status       : qualityGateStatus == 'OK' ? 'SUCCESS' : 'FAILURE',
        projectKey   : projectKey,
        qualityGate  : qualityGateStatus,
 
        bugs         : metrics['bugs'] ?: '0',
        vulnerabilities : metrics['vulnerabilities'] ?: '0',
        codeSmells   : metrics['code_smells'] ?: '0',
        coverage     : metrics['coverage'] ?: 'N/A',
        duplications : metrics['duplicated_lines_density'] ?: '0',
 
        blocker      : issueCounts.blocker,
        critical     : issueCounts.critical,
        major        : issueCounts.major,
        minor        : issueCounts.minor
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
 
Project       : ${projectKey}
Status        : ${result.status}
Quality Gate  : ${result.qualityGate}
 
Bugs          : ${result.bugs}
Vulnerabilities: ${result.vulnerabilities}
Code Smells   : ${result.codeSmells}
Coverage      : ${result.coverage}%
Duplications  : ${result.duplications}%
 
Blocker       : ${result.blocker}
Critical      : ${result.critical}
Major         : ${result.major}
Minor         : ${result.minor}
 
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
 
Project      : ${projectKey}
Quality Gate : ${result.qualityGate}
Bugs         : ${result.bugs}
Vulnerabilities: ${result.vulnerabilities}
Code Smells  : ${result.codeSmells}
Coverage     : ${result.coverage}
Duplications : ${result.duplications}
 
Blocker      : ${result.blocker}
Critical     : ${result.critical}
Major        : ${result.major}
Minor        : ${result.minor}
 
Reports      : ${reportDir}
 
=========================================
"""
 
    return result
}