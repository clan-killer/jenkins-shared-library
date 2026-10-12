def call(
    Map eslintResult = [:],
    Map sonarResult = [:],
    Map trivyResult = [:]
) {

    int eslintErrors =
        eslintResult?.errors ?: 0

    int eslintWarnings =
        eslintResult?.warnings ?: 0

    int sonarCritical =
        sonarResult?.critical ?: 0

    int trivyCritical =
        trivyResult?.critical ?: 0

    int trivyHigh =
        trivyResult?.high ?: 0

    String sonarQualityGate =
        sonarResult?.qualityGate ?: "UNKNOWN"

    int riskScore =

        (eslintErrors * 2) +

        (sonarCritical * 5) +

        (trivyCritical * 10) +

        (trivyHigh * 3)

    if ("ERROR".equalsIgnoreCase(sonarQualityGate)) {
        riskScore += 5
    }

    String riskLevel = "LOW"

    if (riskScore >= 25) {

        riskLevel = "CRITICAL"

    } else if (riskScore >= 15) {

        riskLevel = "HIGH"

    } else if (riskScore >= 5) {

        riskLevel = "MEDIUM"
    }

    boolean approvalRequired =

        eslintErrors > 0 ||

        sonarCritical > 0 ||

        "ERROR".equalsIgnoreCase(
            sonarQualityGate
        ) ||

        trivyCritical > 0 ||

        trivyHigh > 0

    if (!approvalRequired) {

        echo """
===================================================

SECURITY GATE

No approval required.

---------------------------------------------------

RISK ASSESSMENT

Risk Score   : ${riskScore}

Risk Level   : ${riskLevel}

---------------------------------------------------

ESLINT

Status       : ${eslintResult?.status ?: 'SUCCESS'}

Errors       : ${eslintErrors}

Warnings     : ${eslintWarnings}

---------------------------------------------------

SONAR

Status       : ${sonarResult?.status ?: 'SUCCESS'}

Quality Gate : ${sonarQualityGate}

---------------------------------------------------

TRIVY

Status       : ${trivyResult?.status ?: 'SUCCESS'}

===================================================
"""

        return [
            approvalRequired : false,
            approved         : true,
            riskScore        : riskScore,
            riskLevel        : riskLevel,
            approvedBy       : "AUTO"
        ]
    }

    try {

        timeout(time: 60, unit: 'MINUTES') {

            def approver = input(
                id: 'SecurityApproval',
                message: """
===================================================

SECURITY GATE APPROVAL

Build       : ${env.BUILD_NUMBER}

Project     : ${env.APP_NAME}

Image       : ${env.IMAGE_NAME}:${env.BUILD_NUMBER}

---------------------------------------------------

RISK ASSESSMENT

Risk Score   : ${riskScore}

Risk Level   : ${riskLevel}

---------------------------------------------------

ESLINT

Status       : ${eslintResult?.status ?: 'N/A'}

Errors       : ${eslintErrors}

Warnings     : ${eslintWarnings}

---------------------------------------------------

SONARQUBE

Status       : ${sonarResult?.status ?: 'N/A'}

Quality Gate : ${sonarQualityGate}

Critical     : ${sonarResult?.critical ?: 0}

Major        : ${sonarResult?.major ?: 0}

Minor        : ${sonarResult?.minor ?: 0}

---------------------------------------------------

TRIVY

Status       : ${trivyResult?.status ?: 'N/A'}

Critical     : ${trivyResult?.critical ?: 0}

High         : ${trivyResult?.high ?: 0}

Medium       : ${trivyResult?.medium ?: 0}

Low          : ${trivyResult?.low ?: 0}

---------------------------------------------------

BUILD ARTIFACTS

Source

builds/Build_${env.BUILD_NUMBER}/source/

ESLint Reports

builds/Build_${env.BUILD_NUMBER}/reports/eslint/

Sonar Reports

builds/Build_${env.BUILD_NUMBER}/reports/sonar/

Trivy Reports

builds/Build_${env.BUILD_NUMBER}/reports/trivy/

---------------------------------------------------

APPROVAL REASONS

${eslintErrors > 0 ? '✓ ESLint Errors Found' : ''}

${sonarCritical > 0 ? '✓ Sonar Critical Issues Found' : ''}

${"ERROR".equalsIgnoreCase(sonarQualityGate) ? '✓ Sonar Quality Gate Failed' : ''}

${trivyCritical > 0 ? '✓ Trivy Critical Vulnerabilities Found' : ''}

${trivyHigh > 0 ? '✓ Trivy High Vulnerabilities Found' : ''}

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
                riskLevel        : riskLevel
            ]
        }

    } catch (Exception ex) {

        error(
            """
===================================================

SECURITY APPROVAL FAILED

Build : ${env.BUILD_NUMBER}

Reason :

Approval was rejected or not received
within 60 minutes.

Risk Score : ${riskScore}

Risk Level : ${riskLevel}

===================================================
"""
        )
    }
}