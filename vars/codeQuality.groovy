def call() {

    def projectType = detectProject()

    switch(projectType) {

        case "NODE":

            sh '''
                npm ci
                npm run lint
            '''
            break

        case "MAVEN":

            sh '''
                mvn clean test
            '''
            break

        case "GRADLE":

            sh '''
                ./gradlew test
            '''
            break
    }
}