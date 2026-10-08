import groovy.json.JsonOutput

def call() {

    def projectType = detectProject()

    def summary = [
        projectType : projectType,
        tool        : '',
        errors      : 0,
        warnings    : 0,
        status      : 'SUCCESS'
    ]

    switch(projectType) {

        case "NODE":
        case "NESTJS":

            summary.tool = "ESLint"

            sh '''

                npm ci

                npx eslint . \
                    --format json \
                    -o eslint-report.json || true
            '''

            def eslintReport = readJSON file: 'eslint-report.json'

            eslintReport.each {

                summary.errors += it.errorCount ?: 0
                summary.warnings += it.warningCount ?: 0
            }

            break

        case "MAVEN":

            summary.tool = "Checkstyle"

            sh '''
                mvn clean verify checkstyle:checkstyle
            '''

            summary.warnings = 0
            summary.errors = 0

            break

        case "GRADLE":

            summary.tool = "Checkstyle"

            sh '''
                ./gradlew check
            '''

            summary.warnings = 0
            summary.errors = 0

            break

        case "PYTHON":

            summary.tool = "Flake8"

            sh '''
                pip3 install flake8

                flake8 . > flake8-report.txt || true
            '''

            def count = sh(
                script: "cat flake8-report.txt | wc -l || true",
                returnStdout: true
            ).trim()

            summary.errors = count.toInteger()

            break

        case "GO":

            summary.tool = "golangci-lint"

            sh '''
                golangci-lint run \
                --out-format json \
                > golangci-report.json || true
            '''

            def count = sh(
                script: "grep -c severity golangci-report.json || true",
                returnStdout: true
            ).trim()

            summary.errors = count.toInteger()

            break
    }

    if(summary.errors > 0) {
        summary.status = "FAILED"
    }
    else if(summary.warnings > 0) {
        summary.status = "UNSTABLE"
    }

    writeFile(
        file: 'quality-summary.json',
        text: JsonOutput.prettyPrint(
            JsonOutput.toJson(summary)
        )
    )

    archiveArtifacts artifacts: '''
        quality-summary.json,
        eslint-report.json,
        flake8-report.txt,
        golangci-report.json,
        **/checkstyle*.xml
    ''', allowEmptyArchive: true

    echo """
===================================================

CODE QUALITY SUMMARY

Project Type : ${summary.projectType}
Tool         : ${summary.tool}

Errors       : ${summary.errors}
Warnings     : ${summary.warnings}

Status       : ${summary.status}

===================================================
"""

    currentBuild.description =
        "Quality[E=${summary.errors},W=${summary.warnings}]"

    if(summary.errors > 0) {
        error("Code Quality errors found")
    }

    if(summary.warnings > 0) {
        currentBuild.result = 'UNSTABLE'
    }

    return summary
}