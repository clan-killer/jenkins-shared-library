import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

def call(Map config = [:]) {

    def imageName = config.imageName
    def imageTag  = config.imageTag

    def buildDir  = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/trivy"

    def cacheDir = "/var/lib/jenkins/trivy-cache"

    if (!fileExists(buildDir)) {

        sh """
            mkdir -p ${buildDir}
        """
    }

    if (!fileExists(reportDir)) {

        sh """
            mkdir -p ${reportDir}
        """
    }

    def image = "${imageName}:${imageTag}"

    echo "Starting Trivy scan for ${image}"

    sh """
        TRIVY_CACHE_DIR=${cacheDir} \
        trivy image \
        --skip-version-check \
        --scanners vuln \
        --format json \
        -o ${reportDir}/trivy-${imageName}-raw.json \
        ${image}
    """

    def trivyData =
        new JsonSlurperClassic()
            .parse(
                new File(
                    "${env.WORKSPACE}/${reportDir}/trivy-${imageName}-raw.json"
                )
            )

    def vulnerabilities = []

    trivyData.Results?.each { result ->

        if (result.Vulnerabilities) {

            vulnerabilities.addAll(result.Vulnerabilities)
        }
    }

    writeFile(
        file: "${reportDir}/trivy-${imageName}-vulnerabilities.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(vulnerabilities)
        )
    )

    int critical = 0
    int high     = 0
    int medium   = 0
    int low      = 0

    vulnerabilities.each { vuln ->

        switch(vuln.Severity) {

            case "CRITICAL":
                critical++
                break

            case "HIGH":
                high++
                break

            case "MEDIUM":
                medium++
                break

            case "LOW":
                low++
                break
        }
    }

    def summary = [
        image    : image,
        critical : critical,
        high     : high,
        medium   : medium,
        low      : low,
        status   : "SUCCESS"
    ]

    if (summary.critical > 0 ||
        summary.high > 0) {

        summary.status = "APPROVAL_REQUIRED"

    } else if (summary.medium > 0) {

        summary.status = "UNSTABLE"
    }

    writeFile(
        file: "${reportDir}/trivy-${imageName}-summary.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(summary)
        )
    )

    def fullReport = new StringBuilder()
    def consoleReport = new StringBuilder()

    fullReport << """
=========================================
TRIVY VULNERABILITY REPORT

Image : ${image}

=========================================

"""

    consoleReport << """
=========================================
TRIVY VULNERABILITY REPORT

Image : ${image}

=========================================

Showing first ${Math.min(vulnerabilities.size(), 1000)}
of ${vulnerabilities.size()} findings

"""

    vulnerabilities.eachWithIndex { vuln, index ->

        def vulnText = """
Finding #${index + 1}

Severity      : ${vuln.Severity}

Package       : ${vuln.PkgName}

Installed     : ${vuln.InstalledVersion ?: 'N/A'}

Fixed Version : ${vuln.FixedVersion ?: 'N/A'}

CVE           : ${vuln.VulnerabilityID}

Title         : ${vuln.Title ?: 'N/A'}

-----------------------------------------

"""

        fullReport << vulnText

        if (index < 1000) {

            consoleReport << vulnText
        }
    }

    if (vulnerabilities.size() > 1000) {

        consoleReport << """

WARNING:

Console output truncated.

Showing first 1000 findings.

See:

${reportDir}/trivy-${imageName}-report.txt

for complete findings.

"""
    }

    fullReport << """

=========================================
SUMMARY
=========================================

Critical : ${summary.critical}

High     : ${summary.high}

Medium   : ${summary.medium}

Low      : ${summary.low}

Status   : ${summary.status}

=========================================
"""

    consoleReport << """

=========================================
SUMMARY
=========================================

Critical : ${summary.critical}

High     : ${summary.high}

Medium   : ${summary.medium}

Low      : ${summary.low}

Status   : ${summary.status}

=========================================
"""

    writeFile(
        file: "${reportDir}/trivy-${imageName}-report.txt",
        text: fullReport.toString()
    )

    echo consoleReport.toString()

    sh """
        echo "===== GENERATED TRIVY FILES ====="
        ls -ltr ${reportDir}
    """

    archiveArtifacts(
        artifacts: "${buildDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )

    echo """
====================================

TRIVY SUMMARY

Image       : ${summary.image}

Critical    : ${summary.critical}
High        : ${summary.high}
Medium      : ${summary.medium}
Low         : ${summary.low}

Status      : ${summary.status}

Reports Location:
${reportDir}

====================================
"""

    currentBuild.description =
        (currentBuild.description ?: "") +
        " | Trivy[C=${summary.critical},H=${summary.high}]"

    if (summary.medium > 0) {

        currentBuild.result = 'UNSTABLE'
    }

    return summary
}