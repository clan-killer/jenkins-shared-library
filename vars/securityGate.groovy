/*
 * securityGate
 *
 * Shared by every project type (Node, NestJS, Maven, ...).
 * Collects the scan results and decides whether a person
 * must approve before the pipeline continues.
 *
 * Approval REQUIRED when any of these is found:
 *   - lint errors            (ESLint errors, SpotBugs/PMD priority 1)
 *   - Sonar BLOCKER issues
 *   - Sonar CRITICAL issues
 *   - Sonar quality gate ERROR
 *   - Trivy CRITICAL or HIGH vulnerabilities
 *
 * NO approval for low-impact findings (auto-approved, shown
 * in the summary only):
 *   - lint warnings
 *   - Sonar MAJOR / MINOR issues
 *   - Trivy MEDIUM / LOW vulnerabilities
 *
 * Pipeline FAILS (not a scan finding) when:
 *   - a required scan result is missing or empty
 *   - approval is rejected or not given within 60 minutes
 *
 * Usage (same positional call as before):
 *   gateResult = securityGate(lintResult, sonarResult, trivyResult)
 *
 * Optional 4th argument:
 *   securityGate(lintResult, sonarResult, trivyResult, [
 *     deployMode    : cfg.deployMode,     // DOCKER | JAR | APK
 *     trivyRequired : cfg.trivyTarget != 'NONE',
 *     lintReportDir : 'reports/maven-lint'
 *   ])
 *
 * Returns:
 *   [approvalRequired, approved, approvedBy, riskScore,
 *    riskLevel, reasons]
 */
 
def call(
    Map lintResult = [:],
    Map sonarResult = [:],
    Map trivyResult = [:],
    Map options = [:]
) {
 
    String deployMode = (options.deployMode ?: 'DOCKER').toString()
    boolean trivyRequired =
        options.containsKey('trivyRequired') ? (options.trivyRequired as boolean) : true
 
    /*
     * Missing results are a pipeline error, never an auto-approve
     */
    def missing = []
 
    if (!lintResult) { missing << 'Lint' }
    if (!sonarResult) { missing << 'SonarQube' }
    if (trivyRequired && !trivyResult) { missing << 'Trivy' }
 
    if (missing) {
        env.FAILURE_REASON =
            "Security Gate received no result from: ${missing.join(', ')}"
        error("""
===================================================
 
SECURITY GATE CANNOT DECIDE
 
No scan result received from:
${missing.join('\n')}
 
The gate never auto-approves missing results.
Check that these stages ran and returned a summary.
 
===================================================
""")
    }
 
    /*
     * Values
     */
    String lintTool = (lintResult?.tool ?: 'Lint').toString()
 
    String lintReportDir = options.lintReportDir ?:
        (lintTool.contains('ESLint') ? 'reports/eslint' : 'reports/maven-lint')
 
    int lintErrors = (lintResult?.errors ?: 0) as int
    int lintWarnings = (lintResult?.warnings ?: 0) as int
 
    int sonarBlocker = (sonarResult?.blocker ?: 0) as int
    int sonarCritical = (sonarResult?.critical ?: 0) as int
    int sonarMajor = (sonarResult?.major ?: 0) as int
    int sonarMinor = (sonarResult?.minor ?: 0) as int
 
    String sonarQualityGate =
        (sonarResult?.qualityGate ?: 'UNKNOWN').toString()
 
    boolean qualityGateFailed =
        'ERROR'.equalsIgnoreCase(sonarQualityGate)
 
    int trivyCritical = (trivyResult?.critical ?: 0) as int
    int trivyHigh = (trivyResult?.high ?: 0) as int
    int trivyMedium = (trivyResult?.medium ?: 0) as int
    int trivyLow = (trivyResult?.low ?: 0) as int
 
    String trivyMode = trivyRequired
        ? (trivyResult?.mode ?: 'IMAGE').toString()
        : 'SKIPPED'
 
    String scanTarget = trivyRequired
        ? (trivyResult?.target ?: trivyResult?.image ?:
            "${env.IMAGE_NAME}:${env.BUILD_NUMBER}").toString()
        : 'N/A'
 
    /*
     * Risk score (medium/low/warnings are not scored)
     */
    int riskScore =
        (lintErrors * 2) +
        (sonarBlocker * 8) +
        (sonarCritical * 5) +
        (trivyCritical * 10) +
        (trivyHigh * 3)
 
    if (qualityGateFailed) {
        riskScore += 5
    }
 
    String riskLevel = 'LOW'
 
    if (riskScore >= 25) {
        riskLevel = 'CRITICAL'
    } else if (riskScore >= 15) {
        riskLevel = 'HIGH'
    } else if (riskScore >= 5) {
        riskLevel = 'MEDIUM'
    }
 
    /*
     * Approval reasons
     */
    def reasons = []
 
    if (lintErrors > 0) {
        reasons << "${lintTool} errors found (${lintErrors})"
    }
    if (sonarBlocker > 0) {
        reasons << "Sonar blocker issues found (${sonarBlocker})"
    }
    if (sonarCritical > 0) {
        reasons << "Sonar critical issues found (${sonarCritical})"
    }
    if (qualityGateFailed) {
        reasons << 'Sonar quality gate failed'
    }
    if (trivyCritical > 0) {
        reasons << "Trivy critical vulnerabilities found (${trivyCritical})"
    }
    if (trivyHigh > 0) {
        reasons << "Trivy high vulnerabilities found (${trivyHigh})"
    }
 
    boolean approvalRequired = !reasons.isEmpty()
 
    String b = "builds/Build_${env.BUILD_NUMBER}"
 
    String resultsBlock = """
---------------------------------------------------
 
RISK ASSESSMENT
 
Risk Score : ${riskScore}
Risk Level : ${riskLevel}
 
---------------------------------------------------
 
${lintTool.toUpperCase()}
 
Status   : ${lintResult?.status ?: 'N/A'}
Errors   : ${lintErrors}
Warnings : ${lintWarnings}
 
---------------------------------------------------
 
SONARQUBE
 
Status       : ${sonarResult?.status ?: 'N/A'}
Quality Gate : ${sonarQualityGate}
Blocker      : ${sonarBlocker}
Critical     : ${sonarCritical}
Major        : ${sonarMajor}
Minor        : ${sonarMinor}
 
---------------------------------------------------
 
TRIVY (summary)
 
Mode     : ${trivyMode}
Status   : ${trivyRequired ? (trivyResult?.status ?: 'N/A') : 'SKIPPED'}
Critical : ${trivyCritical}
High     : ${trivyHigh}
Medium   : ${trivyMedium}
Low      : ${trivyLow}
"""
 
    /*
     * No approval needed
     */
    if (!approvalRequired) {
 
        echo """
===================================================
 
SECURITY GATE
 
No approval required.
Only low-impact findings (warnings, Sonar major/minor,
Trivy medium/low). Auto-approved.
 
Deploy Mode : ${deployMode}
${resultsBlock}
===================================================
"""
 
        return [
            approvalRequired : false,
            approved         : true,
            approvedBy       : 'AUTO',
            riskScore        : riskScore,
            riskLevel        : riskLevel,
            reasons          : []
        ]
    }
 
    /*
     * Approval needed
     */
    String approver
 
    try {
 
        timeout(time: 60, unit: 'MINUTES') {
 
            approver = input(
                id: 'SecurityApproval',
                message: """
===================================================
 
SECURITY GATE APPROVAL
 
Build       : ${env.BUILD_NUMBER}
Project     : ${env.APP_NAME}
Deploy Mode : ${deployMode}
Scanned     : ${scanTarget}
${resultsBlock}
---------------------------------------------------
 
APPROVAL REASONS
 
${reasons.collect { '- ' + it }.join('\n')}
 
---------------------------------------------------
 
REPORTS
 
Lint   : ${b}/${lintReportDir}/
Sonar  : ${b}/reports/sonar/
Trivy  : ${b}/reports/trivy/
Source : ${b}/source/
 
---------------------------------------------------
 
Approve continuation?
 
Timeout = FAIL
Reject  = FAIL
Approve = Continue
 
===================================================
""",
                ok: 'Approve',
                submitterParameter: 'APPROVER'
            )
        }
 
    } catch (Exception ex) {
 
        env.FAILURE_REASON =
            'Security approval was rejected or not received within 60 minutes'
 
        error("""
===================================================
 
SECURITY APPROVAL FAILED
 
Build : ${env.BUILD_NUMBER}
 
Reason :
Approval was rejected or not received
within 60 minutes.
 
Risk Score : ${riskScore}
Risk Level : ${riskLevel}
 
Approval reasons:
${reasons.collect { '- ' + it }.join('\n')}
 
===================================================
""")
    }
 
    echo """
===================================================
 
SECURITY APPROVAL GRANTED
 
Build       : ${env.BUILD_NUMBER}
Approved By : ${approver}
Risk Score  : ${riskScore}
Risk Level  : ${riskLevel}
 
===================================================
"""
 
    return [
        approvalRequired : true,
        approved         : true,
        approvedBy       : approver,
        riskScore        : riskScore,
        riskLevel        : riskLevel,
        reasons          : reasons
    ]
}