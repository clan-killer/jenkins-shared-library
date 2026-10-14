import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
 
/*
 * trivyMavenScan
 *
 * Trivy vulnerability scan for MAVEN projects.
 * Node/NestJS projects keep using trivyScan.
 *
 * Two modes, chosen by DEPLOY_MODE (cfg.trivyTarget):
 *
 *   IMAGE       DOCKER mode. Scans the built image
 *               <imageName>:<imageTag> (OS packages + the jar).
 *
 *   FILESYSTEM  JAR mode. No image exists, so it scans the
 *               staged jar folder builds/Build_N/app/.
 *               Trivy opens the Spring Boot jar and checks every
 *               library inside it, which is exactly what runs.
 *
 * Same report files, console report and summary as trivyScan.
 *
 * Scan results NEVER fail the pipeline. Critical/High go to
 * the Security Gate. Medium marks the build UNSTABLE (same
 * behaviour as trivyScan).
 *
 * These FAIL the pipeline (tool errors, not findings):
 *   - trivy not installed, invalid mode or missing target
 *   - image or staged jar not found
 *   - trivy exits with an error (DB download, scan error)
 *   - raw report missing or not valid JSON
 *
 * Usage:
 *   trivyResult = trivyMavenScan(
 *     mode      : cfg.trivyTarget,          // IMAGE | FILESYSTEM
 *     imageName : env.IMAGE_NAME,           // IMAGE mode
 *     imageTag  : env.BUILD_NUMBER,         // IMAGE mode
 *     appName   : env.APP_NAME,             // FILESYSTEM mode
 *     stagedJar : buildResult.stagedJar     // FILESYSTEM mode
 *   )
 *
 * Optional environment:
 *   TRIVY_CACHE_DIR    cache folder (default /var/lib/jenkins/trivy-cache)
 *   TRIVY_CONSOLE_LIMIT  findings printed to the console (default 500,
 *                        the report file always has all findings)
 *   TRIVY_EXTRA_ARGS   extra trivy arguments, for example
 *                      --skip-java-db-update (offline agents with a
 *                      pre-loaded Java DB) or --ignore-unfixed
 *
 * Returns (same keys as trivyScan, plus mode and target):
 *   [image, mode, target, critical, high, medium, low, status]
 */
 
def call(Map config = [:]) {
 
    String mode = (config.mode ?: 'IMAGE').toString().toUpperCase()
 
    def buildDir = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/trivy"
    def logFile = "${reportDir}/trivy.log"
 
    String cacheDir = env.TRIVY_CACHE_DIR ?: '/var/lib/jenkins/trivy-cache'
 
    sh "mkdir -p '${reportDir}'"
 
    /*
     * Pre-checks
     */
    if (sh(script: 'command -v trivy >/dev/null 2>&1', returnStatus: true) != 0) {
        env.FAILURE_REASON = 'Trivy not found on the agent'
        error('Trivy is not installed on this agent.')
    }
 
    String target
    String reportName
    String trivyCommand
 
    if (mode == 'IMAGE') {
 
        String imageName = config.imageName?.toString()
        String imageTag = config.imageTag?.toString()
 
        if (!imageName || !imageTag) {
            env.FAILURE_REASON = 'Trivy IMAGE mode needs imageName and imageTag'
            error('Trivy IMAGE mode needs imageName and imageTag.')
        }
 
        target = "${imageName}:${imageTag}"
        reportName = imageName.replaceAll('[^A-Za-z0-9._-]', '_')
 
        if (sh(script: "docker image inspect '${target}' >/dev/null 2>&1",
               returnStatus: true) != 0) {
 
            env.FAILURE_REASON = "Image to scan not found: ${target}"
            error("""
Image to scan not found: ${target}
 
The Build Image stage must run first.
""")
        }
 
        trivyCommand = 'image'
 
    } else if (mode == 'FILESYSTEM') {
 
        String stagedJar = config.stagedJar?.toString()
        String appName = (config.appName ?: env.APP_NAME ?: 'app').toString()
 
        if (!stagedJar || stagedJar == 'N/A' || !fileExists(stagedJar)) {
            env.FAILURE_REASON = "Staged jar not found for Trivy: ${stagedJar}"
            error("""
The staged jar was not found.
 
Path: ${stagedJar}
 
The Maven build stage must run first and
produce builds/Build_N/app/app.jar.
""")
        }
 
        target = stagedJar.substring(0, stagedJar.lastIndexOf('/'))
        reportName = appName.replaceAll('[^A-Za-z0-9._-]', '_')
        trivyCommand = 'fs'
 
    } else {
 
        env.FAILURE_REASON = "Invalid Trivy mode: ${mode}"
        error("""
Invalid Trivy mode: ${mode}
 
Supported: IMAGE, FILESYSTEM
""")
    }
 
    def rawReport = "${reportDir}/trivy-${reportName}-raw.json"
 
    echo """
====================================
TRIVY SCAN (MAVEN)
====================================
 
Mode     : ${mode}
Target   : ${target}
Cache    : ${cacheDir}
 
====================================
"""
 
    /*
     * Run Trivy. Without --exit-code, Trivy returns 0 even when
     * it finds vulnerabilities, so a non-zero exit is a tool error.
     */
    int exitCode
 
    withEnv([
        "TRIVY_CACHE_DIR=${cacheDir}",
        "TRIVY_SCAN_CMD=${trivyCommand}",
        "TRIVY_TARGET=${target}",
        "TRIVY_RAW_REPORT=${rawReport}",
        "TRIVY_ARGS=${env.TRIVY_EXTRA_ARGS ?: ''}"
    ]) {
        exitCode = sh(
            label: 'Run Trivy',
            script: """#!/bin/bash
set -o pipefail
trivy "\$TRIVY_SCAN_CMD" \\
    --skip-version-check \\
    --scanners vuln \\
    --format json \\
    -o "\$TRIVY_RAW_REPORT" \\
    \$TRIVY_ARGS \\
    "\$TRIVY_TARGET" 2>&1 | tee '${logFile}'
""",
            returnStatus: true
        )
    }
 
    echo "Trivy Exit Code: ${exitCode}"
 
    if (exitCode != 0 || !fileExists(rawReport)) {
 
        String errorLines = sh(
            script: "grep -i -m 15 -E 'fatal|error' '${logFile}' || true",
            returnStdout: true
        ).trim()
 
        env.FAILURE_REASON = exitCode != 0
            ? "Trivy scan failed to run (exit code ${exitCode})"
            : 'Trivy did not produce a report'
 
        archiveArtifacts(
            artifacts: "${reportDir}/**",
            fingerprint: true,
            allowEmptyArchive: true
        )
 
        error("""
TRIVY SCAN FAILED TO RUN
 
This is a pipeline error, not a vulnerability finding.
Common causes: vulnerability DB or Java DB could not be
downloaded, or the target could not be read.
 
Target    : ${target}
Exit Code : ${exitCode}
 
First errors:
${errorLines ?: 'See ' + logFile}
 
Full log:
${logFile}
""")
    }
 
    /*
     * Parse the report (readFile works on any agent)
     */
    def trivyData
 
    try {
        trivyData = new JsonSlurperClassic().parseText(readFile(rawReport))
    } catch (Exception ex) {
        env.FAILURE_REASON = "Invalid Trivy JSON report: ${rawReport}"
        error("""
Invalid Trivy JSON report.
 
File  : ${rawReport}
Error : ${ex.getMessage()}
""")
    }
 
    def vulnerabilities = []
 
    trivyData.Results?.each { result ->
 
        if (result.Vulnerabilities) {
 
            vulnerabilities.addAll(result.Vulnerabilities)
        }
    }
 
    writeFile(
        file: "${reportDir}/trivy-${reportName}-vulnerabilities.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(vulnerabilities)
        )
    )
 
    int critical = 0
    int high = 0
    int medium = 0
    int low = 0
 
    vulnerabilities.each { vuln ->
 
        switch (vuln.Severity) {
 
            case 'CRITICAL':
                critical++
                break
 
            case 'HIGH':
                high++
                break
 
            case 'MEDIUM':
                medium++
                break
 
            case 'LOW':
                low++
                break
        }
    }
 
    def summary = [
        image    : target,
        mode     : mode,
        target   : target,
        critical : critical,
        high     : high,
        medium   : medium,
        low      : low,
        status   : 'SUCCESS'
    ]
 
    if (summary.critical > 0 ||
        summary.high > 0) {
 
        summary.status = 'APPROVAL_REQUIRED'
 
    } else if (summary.medium > 0) {
 
        summary.status = 'UNSTABLE'
    }
 
    writeFile(
        file: "${reportDir}/trivy-${reportName}-summary.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(summary)
        )
    )
 
    // Console shows the first N findings; the report file has all of them
    int consoleLimit = (env.TRIVY_CONSOLE_LIMIT ?: '500') as int
 
    def fullReport = new StringBuilder()
    def consoleReport = new StringBuilder()
 
    fullReport << """
=========================================
TRIVY VULNERABILITY REPORT
 
Mode   : ${mode}
Target : ${target}
 
=========================================
 
"""
 
    consoleReport << """
=========================================
TRIVY VULNERABILITY REPORT
 
Mode   : ${mode}
Target : ${target}
 
=========================================
 
Showing first ${Math.min(vulnerabilities.size(), consoleLimit)}
of ${vulnerabilities.size()} findings
 
"""
 
    vulnerabilities.eachWithIndex { vuln, index ->
 
        def vulnText = """
Finding #${index + 1}
 
Severity : ${vuln.Severity}
 
Package : ${vuln.PkgName}
 
Installed : ${vuln.InstalledVersion ?: 'N/A'}
 
Fixed Version : ${vuln.FixedVersion ?: 'N/A'}
 
CVE : ${vuln.VulnerabilityID}
 
Title : ${vuln.Title ?: 'N/A'}
 
-----------------------------------------
 
"""
 
        fullReport << vulnText
 
        if (index < consoleLimit) {
 
            consoleReport << vulnText
        }
    }
 
    if (vulnerabilities.size() > consoleLimit) {
 
        consoleReport << """
 
WARNING:
 
Console output truncated.
 
Showing first ${consoleLimit} findings.
 
See:
 
${reportDir}/trivy-${reportName}-report.txt
 
for complete findings.
 
"""
    }
 
    String summaryBlock = """
 
=========================================
SUMMARY
=========================================
 
Critical : ${summary.critical}
 
High : ${summary.high}
 
Medium : ${summary.medium}
 
Low : ${summary.low}
 
Status : ${summary.status}
 
=========================================
"""
 
    fullReport << summaryBlock
    consoleReport << summaryBlock
 
    writeFile(
        file: "${reportDir}/trivy-${reportName}-report.txt",
        text: fullReport.toString()
    )
 
    echo consoleReport.toString()
 
    sh """
        echo "===== GENERATED TRIVY FILES ====="
        ls -ltr '${reportDir}'
    """
 
    archiveArtifacts(
        artifacts: "${reportDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )
 
    echo """
====================================
 
TRIVY SUMMARY
 
Mode : ${mode}
Target : ${target}
 
Critical : ${summary.critical}
High : ${summary.high}
Medium : ${summary.medium}
Low : ${summary.low}
 
Status : ${summary.status}
 
Reports Location:
${reportDir}
 
====================================
"""
 
    currentBuild.description =
        (currentBuild.description ?: '') +
        " | Trivy[C=${summary.critical},H=${summary.high}]"
 
    if (summary.medium > 0) {
 
        currentBuild.result = 'UNSTABLE'
    }
 
    return summary
}