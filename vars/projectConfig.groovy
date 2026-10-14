/*
 * projectConfig
 *
 * Returns the per-type settings used by the
 * universal pipeline. No build logic lives here,
 * only configuration.
 *
 * Usage:
 *   def cfg = projectConfig(projectType, params.DEPLOY_MODE)
 *
 * DEPLOY_MODE (DOCKER default, or JAR for Maven/Gradle)
 * adds these keys, used by the Jenkinsfile `when` blocks:
 *   deployMode, runBuildImage, runDeploy, runHealthCheck,
 *   trivyTarget (IMAGE | FILESYSTEM | NONE), jar (JAR mode)
 *
 * Optional overrides (set in Jenkinsfile environment):
 *   APP_PORT_OVERRIDE
 *   HEALTH_PATH_OVERRIDE
 *
 * Returned keys:
 *   type              project type
 *   displayName       name used in summaries
 *   lintTool          label shown in lint summary and gate
 *   producesImage     true  = Docker image is built and deployed
 *                     false = artifact only (Flutter APK)
 *   dockerTemplate    resource name of default Dockerfile
 *   appPort           container port
 *   healthPath        HTTP path used by Health Check
 *   sonarSources      comma separated source paths
 *   sonarProps        extra sonar properties (Map)
 *   sonarExclusions   comma separated exclusions
 *   artifactPattern   files archived after build
 *   snapshotExcludes  rsync excludes for Build Snapshot
 */
 
def call(String projectType, String deployMode = 'DOCKER') {
 
    String type = (projectType ?: '').toString().toUpperCase()
 
    String mode =
        (deployMode ?: 'DOCKER').toString().trim().toUpperCase()
 
    if (!['DOCKER', 'JAR'].contains(mode)) {
        error("""
Invalid DEPLOY_MODE: ${mode}
 
Supported values: DOCKER, JAR
""")
    }
 
    def commonExclusions =
        '.scannerwork/**,builds/**,coverage/**'
 
    def commonSnapshotExcludes = [
        '.git', '.scannerwork', 'builds', 'coverage'
    ]
 
    def cfg
 
    switch (type) {
 
        case 'MAVEN':
 
            boolean springBoot =
                fileExists('pom.xml') &&
                readFile('pom.xml').contains('spring-boot')
 
            /*
             * Find all module source and test folders so
             * single-module and multi-module repos both work.
             */
            String mainDirs = sh(
                script: '''
                    find . -type d -path '*/src/main/java' \
                        -not -path './builds/*' \
                        -not -path '*/target/*' \
                        | sed 's|^\\./||' | sort | paste -sd, -
                ''',
                returnStdout: true
            ).trim()
 
            String testDirs = sh(
                script: '''
                    find . -type d -path '*/src/test/java' \
                        -not -path './builds/*' \
                        -not -path '*/target/*' \
                        | sed 's|^\\./||' | sort | paste -sd, -
                ''',
                returnStdout: true
            ).trim()
 
            if (!mainDirs) {
                error("""
Maven project has no src/main/java folder.
 
Checked all modules for src/main/java.
Sonar sources cannot be determined.
""")
            }
 
            def sonarProps = [
                'sonar.java.binaries'                 : '**/target/classes',
                'sonar.java.test.binaries'            : '**/target/test-classes',
                'sonar.coverage.jacoco.xmlReportPaths': '**/target/site/jacoco/jacoco.xml'
            ]
 
            if (testDirs) {
                sonarProps['sonar.tests'] = testDirs
            }
 
            cfg = [
                displayName     : springBoot ? 'Maven (Spring Boot)' : 'Maven',
                lintTool        : 'Checkstyle+SpotBugs+PMD',
                producesImage   : true,
                dockerTemplate  : 'maven.Dockerfile',
                appPort         : '8080',
                healthPath      : springBoot ? '/actuator/health' : '/',
                sonarSources    : mainDirs,
                sonarProps      : sonarProps,
                sonarExclusions : "${commonExclusions},**/target/**",
                artifactPattern : '**/target/*.jar',
                snapshotExcludes: commonSnapshotExcludes + ['target']
            ]
            break
 
        case 'GRADLE':
            cfg = [
                displayName     : 'Gradle',
                lintTool        : 'Checkstyle+SpotBugs+PMD',
                producesImage   : true,
                dockerTemplate  : 'gradle.Dockerfile',
                appPort         : '8080',
                healthPath      : '/',
                sonarSources    : 'src/main/java',
                sonarProps      : [
                    'sonar.tests'                         : 'src/test/java',
                    'sonar.java.binaries'                 : '**/build/classes',
                    'sonar.coverage.jacoco.xmlReportPaths': '**/build/reports/jacoco/**/*.xml'
                ],
                sonarExclusions : "${commonExclusions},**/build/**,.gradle/**",
                artifactPattern : '**/build/libs/*.jar',
                snapshotExcludes: commonSnapshotExcludes + ['build', '.gradle']
            ]
            break
 
        case 'NODE':
            cfg = [
                displayName     : 'Node.js',
                lintTool        : 'ESLint',
                producesImage   : true,
                dockerTemplate  : 'node.Dockerfile',
                appPort         : '5173',
                healthPath      : '/',
                sonarSources    : 'src',
                sonarProps      : [:],
                sonarExclusions : "${commonExclusions},node_modules/**,dist/**",
                artifactPattern : 'dist/**',
                snapshotExcludes: commonSnapshotExcludes + ['node_modules', 'dist']
            ]
            break
 
        case 'NESTJS':
            cfg = [
                displayName     : 'NestJS',
                lintTool        : 'ESLint',
                producesImage   : true,
                dockerTemplate  : 'nest.Dockerfile',
                appPort         : '3000',
                healthPath      : '/',
                sonarSources    : 'src',
                sonarProps      : [:],
                sonarExclusions : "${commonExclusions},node_modules/**,dist/**",
                artifactPattern : 'dist/**',
                snapshotExcludes: commonSnapshotExcludes + ['node_modules', 'dist']
            ]
            break
 
        case 'GO':
            cfg = [
                displayName     : 'Go',
                lintTool        : 'golangci-lint',
                producesImage   : true,
                dockerTemplate  : 'go.Dockerfile',
                appPort         : '8080',
                healthPath      : '/',
                sonarSources    : '.',
                sonarProps      : [
                    'sonar.tests'              : '.',
                    'sonar.test.inclusions'    : '**/*_test.go',
                    'sonar.go.coverage.reportPaths': 'coverage.out'
                ],
                sonarExclusions : "${commonExclusions},vendor/**,**/*_test.go",
                artifactPattern : 'bin/**',
                snapshotExcludes: commonSnapshotExcludes + ['vendor', 'bin']
            ]
            break
 
        case 'PYTHON':
            cfg = [
                displayName     : 'Python',
                lintTool        : 'Pylint+Flake8',
                producesImage   : true,
                dockerTemplate  : 'python.Dockerfile',
                appPort         : '8000',
                healthPath      : '/',
                sonarSources    : '.',
                sonarProps      : [
                    'sonar.python.coverage.reportPaths': 'coverage.xml'
                ],
                sonarExclusions : "${commonExclusions},venv/**,.venv/**,**/__pycache__/**",
                artifactPattern : 'dist/**',
                snapshotExcludes: commonSnapshotExcludes + ['venv', '.venv', '__pycache__']
            ]
            break
 
        case 'FLUTTER':
            cfg = [
                displayName     : 'Flutter (APK)',
                lintTool        : 'flutter analyze',
                producesImage   : false,
                dockerTemplate  : '',
                appPort         : '',
                healthPath      : '',
                sonarSources    : 'lib',
                sonarProps      : [
                    'sonar.tests': 'test'
                ],
                sonarExclusions : "${commonExclusions},build/**,.dart_tool/**",
                artifactPattern : 'build/app/outputs/flutter-apk/*.apk',
                snapshotExcludes: commonSnapshotExcludes + ['build', '.dart_tool']
            ]
            break
 
        default:
            error("""
No configuration for project type: ${type}
 
Supported types:
MAVEN, GRADLE, NODE, NESTJS, GO, PYTHON, FLUTTER
""")
    }
 
    cfg.type = type
 
    /*
     * Deploy mode decides which stages run.
     *
     *   DOCKER : Build Image, Trivy image scan, docker run
     *   JAR    : no image, Trivy filesystem scan, nohup java -jar
     *            (Maven and Gradle only)
     *   APK    : Flutter only, artifact is archived, nothing deployed
     */
    if (type == 'FLUTTER') {
 
        mode = 'APK'
 
    } else if (mode == 'JAR' && !['MAVEN', 'GRADLE'].contains(type)) {
 
        error("""
DEPLOY_MODE=JAR is only supported for MAVEN and GRADLE.
 
Detected project type: ${type}
Use DEPLOY_MODE=DOCKER for this project.
""")
    }
 
    cfg.deployMode = mode
    cfg.producesImage = (mode == 'DOCKER')
    cfg.runBuildImage = (mode == 'DOCKER')
    cfg.runDeploy = (mode != 'APK')
    cfg.runHealthCheck = (mode != 'APK')
 
    cfg.trivyTarget =
        mode == 'DOCKER' ? 'IMAGE' :
        mode == 'JAR'    ? 'FILESYSTEM' : 'NONE'
 
    if (mode == 'JAR') {
        cfg.dockerTemplate = ''
 
        cfg.jar = [
            appDir      : "/opt/apps/${env.APP_NAME}",
            logDir      : "/var/log/${env.APP_NAME}",
            jvmOpts     : env.JAVA_OPTS ?: '-Xms512m -Xmx1g',
            keepVersions: 5
        ]
    }
 
    /*
     * Optional overrides from Jenkinsfile environment
     */
    if (env.APP_PORT_OVERRIDE) {
        cfg.appPort = env.APP_PORT_OVERRIDE
    }
 
    if (env.HEALTH_PATH_OVERRIDE) {
        cfg.healthPath = env.HEALTH_PATH_OVERRIDE
    }
 
    echo """
====================================
PROJECT CONFIGURATION
====================================
 
Type            : ${cfg.displayName}
Lint Tools      : ${cfg.lintTool}
Deploy Mode     : ${cfg.deployMode}
Build Image     : ${cfg.runBuildImage}
Trivy Target    : ${cfg.trivyTarget}
Deploy          : ${cfg.runDeploy}
Health Check    : ${cfg.runHealthCheck}
App Port        : ${cfg.appPort ?: 'N/A'}
Health Path     : ${cfg.healthPath ?: 'N/A'}
Sonar Sources   : ${cfg.sonarSources}
Artifacts       : ${cfg.artifactPattern}
 
====================================
"""
 
    return cfg
}