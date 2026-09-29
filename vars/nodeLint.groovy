stage('ESLint Summary') {
    steps {
        script {

            def report = readJSON file: 'eslint-report.json'

            int errors = 0
            int warnings = 0

            report.each { file ->
                errors += file.errorCount
                warnings += file.warningCount
            }

            echo "ESLint Errors : ${errors}"
            echo "ESLint Warnings : ${warnings}"

            // Display in Jenkins build history
            currentBuild.description =
                "Errors=${errors} Warnings=${warnings}"

            if (warnings > 0) {
                currentBuild.result = 'UNSTABLE'
            }

            if (errors > 0) {
                error("Build failed due to ESLint errors")
            }
        }
    }
}