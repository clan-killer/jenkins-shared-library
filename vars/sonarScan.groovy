import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

def call(Map config = [:]) {

    def projectType = detectProject()

    echo "Running SonarQube scan for ${projectType}"

    switch(projectType) {

        case "NODE":
        case "NESTJS":

            def scannerHome = tool 'SonarScanner'

            withSonarQubeEnv('SonarQube') {

                sh """
                    ${scannerHome}/bin/sonar-scanner
                """
            }

            break

        case "MAVEN":

            withSonarQubeEnv('SonarQube') {

                sh '''
                    mvn sonar:sonar
                '''
            }

            break

        case "GRADLE":

            withSonarQubeEnv('SonarQube') {

                sh '''
                    ./gradlew sonarqube
                '''
            }

            break

        case "PYTHON":
        case "GO":

            def scannerHome = tool 'SonarScanner'

            withSonarQubeEnv('SonarQube') {

                sh """
                    ${scannerHome}/bin/sonar-scanner
                """
            }

            break
    }

    echo "Waiting for Sonar Quality Gate..."

    timeout(time: 5, unit: 'MINUTES') {

        def qg = waitForQualityGate()

        if (qg.status != 'OK') {
            error "Sonar Quality Gate Failed: ${qg.status}"
        }
    }

    echo "Quality Gate Passed"

    withSonarQubeEnv('SonarQube') {

        withCredentials([
            string(
                credentialsId: 'sonar-token',
                variable: 'SONAR_TOKEN'
            )
        ]) {

            sh """
                curl -s \
                -u "\$SONAR_TOKEN:" \
                "\$SONAR_HOST_URL/api/measures/component?component=${config.projectKey}&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density" \
                -o sonar-${config.projectKey}-summary-raw.json
            """
        }
    }

    def allIssues = []

    withSonarQubeEnv('SonarQube') {

        withCredentials([
            string(
                credentialsId: 'sonar-token',
                variable: 'SONAR_TOKEN'
            )
        ]) {

            int page = 1
            int pageSize = 500
            boolean hasMore = true

            while (hasMore) {

                sh """
                    curl -s \
                    -u "\$SONAR_TOKEN:" \
                    "\$SONAR_HOST_URL/api/issues/search?componentKeys=${config.projectKey}&ps=${pageSize}&p=${page}" \
                    -o sonar-page.json
                """

                def pageData =
                    new JsonSlurperClassic()
                        .parse(new File("${env.WORKSPACE}/sonar-page.json"))

                if (pageData.issues) {
                    allIssues.addAll(pageData.issues)
                }

                hasMore = pageData.issues?.size() == pageSize

                page++
            }
        }
    }

    writeFile(
        file: "sonar-${config.projectKey}-issues.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson([
                total : allIssues.size(),
                issues: allIssues
            ])
        )
    )

    def sonarData =
        new JsonSlurperClassic()
            .parse(new File("${env.WORKSPACE}/sonar-${config.projectKey}-summary-raw.json"))

    def measures = sonarData.component.measures

    int blocker  = 0
    int critical = 0
    int major    = 0
    int minor    = 0
    int info     = 0

    allIssues.each { issue ->

        switch(issue.severity) {

            case "BLOCKER":
                blocker++
                break

            case "CRITICAL":
                critical++
                break

            case "MAJOR":
                major++
                break

            case "MINOR":
                minor++
                break

            case "INFO":
                info++
                break
        }
    }

    def summary = [
        projectType     : projectType,

        bugs            : 0,
        vulnerabilities : 0,
        codeSmells      : 0,
        coverage        : 0,
        duplication     : 0,

        blocker         : blocker,
        critical        : critical,
        major           : major,
        minor           : minor,
        info            : info,

        qualityGate     : "PASSED",
        status          : "SUCCESS"
    ]

    measures.each { measure ->

        switch(measure.metric) {

            case "bugs":
                summary.bugs = measure.value.toInteger()
                break

            case "vulnerabilities":
                summary.vulnerabilities = measure.value.toInteger()
                break

            case "code_smells":
                summary.codeSmells = measure.value.toInteger()
                break

            case "coverage":
                summary.coverage = measure.value.toBigDecimal()
                break

            case "duplicated_lines_density":
                summary.duplication = measure.value.toBigDecimal()
                break
        }
    }

    if (summary.blocker > 0) {

        summary.status = "FAILED"

    } else if (summary.critical > 0) {

        summary.status = "APPROVAL_REQUIRED"

    } else if (summary.major > 0 ||
               summary.minor > 0) {

        summary.status = "UNSTABLE"
    }

    def issuesReport = new StringBuilder()

    issuesReport << """
=========================================
SONARQUBE ISSUES REPORT
Project : ${config.projectKey}
=========================================

"""

    allIssues.eachWithIndex { issue, index ->

        issuesReport << """
Issue #${index + 1}

Severity : ${issue.severity}
Type     : ${issue.type}
Rule     : ${issue.rule}

File     : ${issue.component}
Line     : ${issue.line ?: 'N/A'}

Message  : ${issue.message}

-----------------------------------------

"""
    }

    issuesReport << """

=========================================
SUMMARY
=========================================

Blocker         : ${summary.blocker}
Critical        : ${summary.critical}
Major           : ${summary.major}
Minor           : ${summary.minor}
Info            : ${summary.info}

Bugs            : ${summary.bugs}
Vulnerabilities : ${summary.vulnerabilities}
Code Smells     : ${summary.codeSmells}

Coverage        : ${summary.coverage}%
Duplications    : ${summary.duplication}%

Quality Gate    : ${summary.qualityGate}
Status          : ${summary.status}

=========================================
"""

    writeFile(
        file: "sonar-${config.projectKey}-summary.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(summary)
        )
    )

    writeFile(
        file: "sonar-${config.projectKey}-issues-report.txt",
        text: issuesReport.toString()
    )

    archiveArtifacts(
        artifacts: """
            sonar-${config.projectKey}-summary.json,
            sonar-${config.projectKey}-summary-raw.json,
            sonar-${config.projectKey}-issues.json,
            sonar-${config.projectKey}-issues-report.txt
        """,
        allowEmptyArchive: true
    )

    echo """
====================================

SONARQUBE SUMMARY

Project Type    : ${summary.projectType}

Bugs            : ${summary.bugs}
Vulnerabilities : ${summary.vulnerabilities}
Code Smells     : ${summary.codeSmells}

Coverage        : ${summary.coverage}%
Duplications    : ${summary.duplication}%

------------------------------------

Blocker         : ${summary.blocker}
Critical        : ${summary.critical}
Major           : ${summary.major}
Minor           : ${summary.minor}
Info            : ${summary.info}

Quality Gate    : ${summary.qualityGate}
Status          : ${summary.status}

====================================
"""

    currentBuild.description =
        (currentBuild.description ?: "") +
        " | Sonar[BL=${summary.blocker},CR=${summary.critical},MJ=${summary.major}]"

    if(summary.blocker > 0) {
        error("SonarQube Blocker issues found")
    }

    if(summary.major > 0 ||
       summary.minor > 0) {

        currentBuild.result = 'UNSTABLE'
    }

    return summary
}