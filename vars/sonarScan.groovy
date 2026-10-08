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

    withCredentials([
        string(
            credentialsId: 'sonar-token',
            variable: 'SONAR_TOKEN'
        )
    ]) {

        sh """
            curl -s -u ${SONAR_TOKEN}: \
            '${config.sonarUrl}/api/measures/component?component=${config.projectKey}&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density' \
            -o sonar-${config.projectKey}-summary-raw.json
        """
    }



    def sonarData =
        new JsonSlurperClassic()
            .parse(new File("${env.WORKSPACE}/sonar-${config.projectKey}-summary-raw.json"))

    def measures = sonarData.component.measures

    def summary = [
        projectType     : projectType,
        bugs            : 0,
        vulnerabilities : 0,
        codeSmells      : 0,
        coverage        : 0,
        duplication     : 0,
        qualityGate     : "PASSED"
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

    writeFile(
        file: 'sonar-summary.json',
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(summary)
        )
    )

    archiveArtifacts(
        artifacts: '''
            sonar-summary.json,
            sonar-${config.projectKey}-summary-raw.json
        ''',
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

Quality Gate    : ${summary.qualityGate}

====================================
"""

    currentBuild.description =
        (currentBuild.description ?: "") +
        " | Sonar[B=${summary.bugs},V=${summary.vulnerabilities}]"

    return summary
}