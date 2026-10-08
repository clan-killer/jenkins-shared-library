def call() {

    def projectType = detectProject()

    switch(projectType) {

        case "NODE":

            echo "Running NodeJS code quality checks..."

            sh '''
                node -v
                npm -v

                npm ci
                npm run lint
            '''
            break

        case "MAVEN":

            echo "Running Maven code quality checks..."

            sh '''
                mvn -version
                mvn clean verify
            '''
            break

        case "GRADLE":

            echo "Running Gradle code quality checks..."

            sh '''
                ./gradlew check
            '''
            break

        case "PYTHON":

            echo "Running Python code quality checks..."

            sh '''
                python3 --version
                pip3 install flake8
                flake8 .
            '''
            break

        case "GO":

            echo "Running Go code quality checks..."

            sh '''
                golangci-lint run
            '''
            break

        default:
            error("Unsupported project type: ${projectType}")
    }
}