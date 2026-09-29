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

            if (errors > 0) {
                error("Build failed because ESLint errors were found")
            }
        }
    }
}