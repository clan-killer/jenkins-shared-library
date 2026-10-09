def call(Map sonarResult = [:], Map trivyResult = [:]) {

    int sonarCritical =
        sonarResult?.critical ?: 0

    int trivyCritical =
        trivyResult?.critical ?: 0

    int trivyHigh =
        trivyResult?.high ?: 0

    String sonarQualityGate =
        sonarResult?.qualityGate ?: "UNKNOWN"

    boolean approvalRequired =

        sonarCritical > 0 ||

        "ERROR".equalsIgnoreCase(
       sonarResult?.qualityGate ?: ""
        ) ||

        trivyCritical > 0 ||

        trivyHigh > 0

    if (!approvalRequired) {

        echo """
===================================================

SECURITY GATE

No approval required.

SONAR

Status       : ${sonarResult?.status ?: 'SUCCESS'}
Quality Gate : ${sonarQualityGate}

TRIVY

Status       : ${trivyResult?.status ?: 'SUCCESS'}

===================================================
"""

        return true
    }

    try {

        timeout(time: 60, unit: 'MINUTES') {

            input(
                id: 'SecurityApproval',
                message: """
===================================================

SECURITY GATE APPROVAL

Build       : ${env.BUILD_NUMBER}

Project     : ${env.APP_NAME}

Image       : ${env.IMAGE_NAME}:${env.BUILD_NUMBER}

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

Sonar Reports

builds/Build_${env.BUILD_NUMBER}/reports/sonar/

Trivy Reports

builds/Build_${env.BUILD_NUMBER}/reports/trivy/

---------------------------------------------------

Approval Reasons

Sonar Critical : ${sonarCritical}

Sonar QG Error : ${sonarQualityGate == 'ERROR'}

Trivy Critical : ${trivyCritical}

Trivy High     : ${trivyHigh}

---------------------------------------------------

Approve continuation?

Timeout = FAIL

Reject  = FAIL

Approve = Continue

===================================================
""",
                ok: 'Approve'
            )
        }

        echo """
===================================================

SECURITY APPROVAL GRANTED

Build : ${env.BUILD_NUMBER}

===================================================
"""

        return true

    } catch (Exception ex) {

        error(
            """
===================================================

SECURITY APPROVAL FAILED

Build : ${env.BUILD_NUMBER}

Reason :

Approval was rejected or not received
within 60 minutes.

===================================================
"""
        )
    }
}