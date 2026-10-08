def scannerHome = tool 'SonarScanner'

echo "Scanner Home = ${scannerHome}"

withSonarQubeEnv('SonarQube') {
    sh "${scannerHome}/bin/sonar-scanner --