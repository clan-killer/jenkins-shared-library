import groovy.json.JsonSlurper

def call() {

    withCredentials([
        string(
            credentialsId: 'sonar-api-token',
            variable: 'SONAR_TOKEN'
        )
    ]) {

        sh '''
        curl -s \
        -u $SONAR_TOKEN: \
        "http://YOUR_SERVER_IP:9000/api/measures/component?component=nodejs-demo&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density" \
        -o sonar-summary.json
        '''

        def json =
            new JsonSlurper()
            .parseText(readFile('sonar-summary.json'))

        def metrics = [:]

        json.component.measures.each {
            metrics[it.metric] = it.value
        }

        echo "================================"
        echo "SONARQUBE REPORT"
        echo "================================"

        echo "Bugs            : ${metrics['bugs'] ?: '0'}"
        echo "Vulnerabilities : ${metrics['vulnerabilities'] ?: '0'}"
        echo "Code Smells     : ${metrics['code_smells'] ?: '0'}"
        echo "Coverage        : ${metrics['coverage'] ?: 'N/A'}%"
        echo "Duplications    : ${metrics['duplicated_lines_density'] ?: '0'}%"

        echo "================================"

        currentBuild.description =
            "Bugs=${metrics['bugs'] ?: '0'} | " +
            "Vulns=${metrics['vulnerabilities'] ?: '0'} | " +
            "Smells=${metrics['code_smells'] ?: '0'}"
    }
}