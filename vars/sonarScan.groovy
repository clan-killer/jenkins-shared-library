import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

def call(Map config = [:]) {

    def buildDir  = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/sonar"

    if (!fileExists(buildDir)) {
        sh "mkdir -p ${buildDir}"
    }

    if (!fileExists(reportDir)) {
        sh "mkdir -p ${reportDir}"
    }

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

    def qgStatus = "UNKNOWN"

    timeout(time: 5, unit: 'MINUTES') {

        def qg = waitForQualityGate()

        qgStatus = qg.status
    }

    echo "Quality Gate Status: ${qgStatus}"

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
                -o ${reportDir}/sonar-${config.projectKey}-summary-raw.json
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

            while(hasMore) {

                sh """
                    curl -s \
                    -u "\$SONAR_TOKEN:" \
                    "\$SONAR_HOST_URL/api/issues/search?componentKeys=${config.projectKey}&ps=${pageSize}&p=${page}" \
                    -o ${reportDir}/sonar-page.json
                """

                def pageData =
                    new JsonSlurperClassic()
                        .parse(
                            new File(
                                "${env.WORKSPACE}/${reportDir}/sonar-page.json"
                            )
                        )

                if(pageData.issues) {
                    allIssues.addAll(pageData.issues)
                }

                hasMore = pageData.issues?.size() == pageSize
                page++
            }
        }
    }

    writeFile(
        file: "${reportDir}/sonar-${config.projectKey}-issues.json",
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson([
                total : allIssues.size(),
                issues: allIssues
            ])
        )
    )

    def sonarData =
        new JsonSlurperClassic()
            .parse(
                new File(
                    "${env.WORKSPACE}/${reportDir}/sonar-${config.projectKey}-summary-raw.json"
                )
            )

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
        qualityGate     : qgStatus,
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

    if(summary.blocker > 0) {

        summary.status = "FAILED"

    } else if(summary.qualityGate == "ERROR") {

        summary.status = "APPROVAL_REQUIRED"

    } else if(summary.critical > 0) {

        summary.status = "APPROVAL_REQUIRED"

    } else if(summary.major > 0 ||
              summary.minor > 0) {

        summary.status = "UNSTABLE"
    }

    currentBuild.description =
        (currentBuild.description ?: "") +
        " | Sonar[QG=${summary.qualityGate},BL=${summary.blocker},CR=${summary.critical}]"

    if(summary.blocker > 0) {
        error("SonarQube Blocker issues found")
    }

    if(summary.major > 0 ||
       summary.minor > 0) {

        currentBuild.result = 'UNSTABLE'
    }

    return summary
}