def call() {

    def scannerHome = tool 'SonarScanner'

    withSonarQubeEnv('SonarQube') {

        sh """
            ${scannerHome}/bin/sonar-scanner
        """
    }
}