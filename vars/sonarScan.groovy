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

            sh """
                curl -s \
                -u "\$SONAR_TOKEN:" \
                "\$SONAR_HOST_URL/api/issues/search?componentKeys=${config.projectKey}&ps=500" \
                -o sonar-${config.projectKey}-issues.json
            """
        }
    }

    def sonarData =
        new JsonSlurperClassic()
            .parse(new File("${env.WORKSPACE}/sonar-${config.projectKey}-summary-raw.json"))

    def issuesData =
        new JsonSlurperClassic()
            .parse(new File("${env.WORKSPACE}/sonar-${config.projectKey}-issues.json"))

    def measures = sonarData.component.measures

    int blocker  = 0
    int critical = 0
    int major    = 0
    int minor    = 0
    int info     = 0

    issuesData.issues.each { issue ->

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

    if(summary.blocker > 0 ||
       summary.critical > 0) {

        summary.status = "FAILED"
    }
    else if(summary.major > 0 ||
            summary.minor > 0) {

        summary.status = "UNSTABLE"
    }

    writeFile(
        file: 'sonar-summary.json',
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(summary)
        )
    )

    archiveArtifacts(
        artifacts: """
            sonar-summary.json,
            sonar-${config.projectKey}-summary-raw.json,
            sonar-${config.projectKey}-issues.json
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

    if(summary.blocker > 0 ||
       summary.critical > 0) {

        error("SonarQube Blocker/Critical issues found")
    }

    if(summary.major > 0 ||
       summary.minor > 0) {

        currentBuild.result = 'UNSTABLE'
    }

    return summary
}