def call(Map sonarResult = [:], Map trivyResult = [:]) {

    int sonarCritical =
        sonarResult?.critical ?: 0

    int trivyCritical =
        trivyResult?.critical ?: 0

    int trivyHigh =
        trivyResult?.high ?: 0

    boolean approvalRequired =
        sonarCritical > 0 ||
        trivyCritical > 0 ||
        trivyHigh > 0

    if (!approvalRequired) {

        echo """
===================================================

SECURITY GATE

No approval required.

SONAR

Status   : ${sonarResult?.status ?: 'SUCCESS'}

TRIVY

Status   : ${trivyResult?.status ?: 'SUCCESS'}

===================================================
"""

        return
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

Status      : ${sonarResult