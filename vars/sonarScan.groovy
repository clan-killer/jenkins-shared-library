def scannerHome = tool 'SonarScanner'

echo "Sonar Scanner Home: ${scannerHome}"

sh """
    ls -la ${scannerHome}/bin
    ${scannerHome}/bin/sonar-scanner --version
"""