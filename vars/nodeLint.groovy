 
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
 
def call() {
    def buildDir = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/eslint"
    def jsonReport = "${reportDir}/eslint-report.json"
 
    sh "mkdir -p '${reportDir}'"
 
    echo """
====================================
NODE VERSION
====================================
"""
 
    sh '''
        set -eu
        node -v
        npm -v
        npm ci
    '''
 
    echo '===================================='
    echo 'RUNNING ESLINT'
    echo '===================================='
 
    int eslintExitCode = sh(
        script: """
            export NODE_OPTIONS="--max-old-space-size=4096"
 
            npx eslint . \\
              --ignore-pattern node_modules \\
              --ignore-pattern .scannerwork \\
              --ignore-pattern builds \\
              --ignore-pattern dist \\
              --ignore-pattern coverage \\
              --format json \\
              --output-file '${jsonReport}'
        """,
        returnStatus: true
    )
 
    echo "ESLint Exit Code: ${eslintExitCode}"
 
    if (!fileExists(jsonReport)) {
        error("""
ESLint report was not generated.
 
Exit code: ${eslintExitCode}
 
Check Node.js memory, ESLint configuration,
dependencies, and Jenkins workspace permissions.
""")
    }
 
    def report
 
    try {
        report = new JsonSlurper().parseText(
            readFile(jsonReport)
        )
    } catch (Exception ex) {
        error("""
ESLint generated an invalid JSON report.
 
File: ${jsonReport}
Error: ${ex.getMessage()}
""")
    }
 
    if (!(report instanceof List)) {
        error(
            "Unexpected ESLint report structure. Expected a JSON array."
        )
    }
 
    int errors = 0
    int warnings = 0
 
    report.each { file ->
        errors += (file.errorCount ?: 0) as int
        warnings += (file.warningCount ?: 0) as int
    }
 
    String status
 
    if (eslintExitCode > 1) {
        status = 'FAILURE'
    } else if (errors > 0) {
        status = 'APPROVAL_REQUIRED'
    } else if (warnings > 0 || eslintExitCode != 0) {
        status = 'WARNING'
    } else {
        status = 'SUCCESS'
    }
 
    def summary = [
        tool     : 'ESLint',
        errors   : errors,
        warnings : warnings,
        exitCode : eslintExitCode,
        status   : status
    ]
 
    def fullReport = new StringBuilder()
 
    fullReport << """
=========================================
ESLINT FINDINGS REPORT
=========================================
 
Build Number : ${env.BUILD_NUMBER}
 
Errors       : ${errors}
Warnings     : ${warnings}
Exit Code    : ${eslintExitCode}
Status       : ${status}
 
=========================================
"""
 
    report.each { file ->
        if (file.messages?.size() > 0) {
            fullReport << """
 
File: ${file.filePath}
 
-----------------------------------------
"""
 
            file.messages.eachWithIndex { msg, index ->
                String severity =
                    msg.severity == 2 ? 'ERROR' : 'WARNING'
 
                fullReport << """
Finding #${index + 1}
 
Line     : ${msg.line ?: 'N/A'}
Column   : ${msg.column ?: 'N/A'}
Severity : ${severity}
Rule     : ${msg.ruleId ?: 'N/A'}
Message  : ${msg.message ?: 'N/A'}
 
-----------------------------------------
"""
            }
        }
    }
 
    writeFile(
        file: "${reportDir}/eslint-findings.txt",
        text: fullReport.toString()
    )
 
    writeFile(
        file: "${reportDir}/eslint-summary.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(summary)
        )
    )
 
    archiveArtifacts(
        artifacts: "${reportDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )
 
    currentBuild.description =
        (currentBuild.description ?: '') +
        " | ESLint[E=${errors},W=${warnings}]"
 
    echo """
=========================================
ESLINT SUMMARY
=========================================
 
Errors   : ${errors}
Warnings : ${warnings}
Exit Code: ${eslintExitCode}
Status   : ${status}
 
Reports  : ${reportDir}
 
=========================================
"""
 
    return summary
}