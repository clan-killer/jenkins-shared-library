import groovy.json.JsonSlurper

def call() {

    sh '''
        echo "Node Version:"
        node -v

        echo "NPM Version:"
        npm -v

        echo "Installing dependencies..."
        npm ci

        echo "Running ESLint..."

        npx eslint . \
            -f json \
            -o eslint-report.json
    '''

    def report = readJSON file: 'eslint-report.json'

    int errors = 0
    int warnings = 0

    report.each { file ->
        errors += file.errorCount
        warnings += file.warningCount
    }

    echo "======================="
    echo "ESLint Summary"
    echo "Errors   : ${errors}"
    echo "Warnings : ${warnings}"
    echo "======================="

    if (errors > 0) {

        currentBuild.description =
            "FAILED | Errors=${errors} Warnings=${warnings}"

        error("Build failed due to ESLint errors")

    } else if (warnings > 0) {

        currentBuild.description =
            "UNSTABLE | Warnings=${warnings}"

        currentBuild.result = 'UNSTABLE'

    } else {

        currentBuild.description = "CLEAN"

    }
}