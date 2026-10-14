import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
 
/*
 * mavenLint
 *
 * Static code analysis for Maven projects.
 * Java counterpart of nodeLint (ESLint).
 *
 * Runs ONE Maven invocation with three report-only goals,
 * called by full plugin coordinates (no pom.xml change needed):
 *
 *   Checkstyle  google_checks.xml     -> all findings = warnings
 *   SpotBugs    default threshold     -> priority 1   = errors, rest = warnings
 *   PMD         plugin default rules  -> priority 1   = errors, rest = warnings
 *
 * Findings NEVER fail the build. They are returned to the
 * Security Gate, where a person decides.
 *
 * These FAIL the pipeline (they are tool errors, not findings):
 *   - pom.xml missing, Java/Maven missing
 *   - compiled classes missing (mavenBuild must run first)
 *   - Maven exits non-zero (plugin could not run)
 *   - a tool produced no report at all
 *   - a report is not valid XML
 *
 * Optional environment:
 *   MAVEN_SETTINGS     path to a settings.xml (adds -s)
 *   MAVEN_EXTRA_ARGS   extra arguments for mvn
 *   CHECKSTYLE_CONFIG  path to a company checkstyle.xml
 *                      (default: bundled google_checks.xml)
 *
 * Returns (same shape as nodeLint, so securityGate works):
 *   [tool, errors, warnings, exitCode, status,
 *    checkstyleWarnings, spotbugsErrors, spotbugsWarnings,
 *    pmdErrors, pmdWarnings, durationSeconds]
 *
 * status: SUCCESS | WARNING | APPROVAL_REQUIRED (errors > 0)
 */
 
// Plugin versions (full coordinates, so no pom change is needed)
@groovy.transform.Field String CHECKSTYLE_PLUGIN =
    'org.apache.maven.plugins:maven-checkstyle-plugin:3.3.1'
@groovy.transform.Field String SPOTBUGS_PLUGIN =
    'com.github.spotbugs:spotbugs-maven-plugin:4.8.6.2'
@groovy.transform.Field String PMD_PLUGIN =
    'org.apache.maven.plugins:maven-pmd-plugin:3.22.0'
 
def call(Map config = [:]) {
 
    long startMillis = System.currentTimeMillis()
 
    def buildDir = "builds/Build_${env.BUILD_NUMBER}"
    def reportDir = "${buildDir}/reports/maven-lint"
    def rawDir = "${reportDir}/raw"
    def logFile = "${reportDir}/maven-lint.log"
 
    sh "mkdir -p '${rawDir}'"
 
    /*
     * Pre-checks: fail fast with a clear reason
     */
    if (!fileExists('pom.xml')) {
        env.FAILURE_REASON = 'pom.xml not found in workspace root'
        error('pom.xml not found in the workspace root. Maven lint cannot start.')
    }
 
    boolean useWrapper = fileExists('mvnw')
    String mvnBin = useWrapper ? './mvnw' : 'mvn'
 
    if (useWrapper) {
        sh 'chmod +x mvnw'
    }
 
    String toolsCheck = useWrapper
        ? 'command -v java >/dev/null 2>&1'
        : 'command -v java >/dev/null 2>&1 && command -v mvn >/dev/null 2>&1'
 
    if (sh(script: toolsCheck, returnStatus: true) != 0) {
        env.FAILURE_REASON = 'Java or Maven not found on the agent'
        error("""
Java or Maven is not available on this agent.
 
Add to the Jenkinsfile tools block:
  maven 'Maven3'
  jdk 'JDK17'
""")
    }
 
    // SpotBugs and Sonar need compiled classes
    if (sh(script: "find . -path './builds' -prune -o -path '*/target/classes' -type d -print | grep -q .",
           returnStatus: true) != 0) {
 
        env.FAILURE_REASON = 'Compiled classes not found (target/classes)'
        error("""
Compiled classes were not found.
 
SpotBugs needs target/classes.
Run the Maven build stage before Code Quality.
""")
    }
 
    /*
     * Checkstyle ruleset
     */
    String checkstyleConfig = env.CHECKSTYLE_CONFIG ?: 'google_checks.xml'
 
    if (env.CHECKSTYLE_CONFIG && !fileExists(env.CHECKSTYLE_CONFIG)) {
        env.FAILURE_REASON = "Checkstyle config not found: ${env.CHECKSTYLE_CONFIG}"
        error("CHECKSTYLE_CONFIG file does not exist: ${env.CHECKSTYLE_CONFIG}")
    }
 
    echo """
====================================
RUNNING MAVEN CODE QUALITY
====================================
 
Checkstyle : ${checkstyleConfig}
SpotBugs   : ${SPOTBUGS_PLUGIN.split(':')[-1]}
PMD        : ${PMD_PLUGIN.split(':')[-1]}
 
====================================
"""
 
    /*
     * One Maven run, three report-only goals.
     * Failure options are off so findings never fail Maven.
     */
    def args = ['-B', '-ntp']
 
    if (env.MAVEN_SETTINGS) {
        args << "-s '${env.MAVEN_SETTINGS}'"
    }
 
    if (env.MAVEN_EXTRA_ARGS) {
        args << env.MAVEN_EXTRA_ARGS
    }
 
    String goals = [
        "${CHECKSTYLE_PLUGIN}:checkstyle",
        "${SPOTBUGS_PLUGIN}:spotbugs",
        "${PMD_PLUGIN}:pmd"
    ].join(' ')
 
    String props = [
        "-Dcheckstyle.config.location='${checkstyleConfig}'",
        '-Dcheckstyle.consoleOutput=false',
        '-Dcheckstyle.failOnViolation=false',
        '-Dcheckstyle.failsOnError=false',
        '-Dspotbugs.failOnError=false',
        '-Dspotbugs.xmlOutput=true',
        '-Dpmd.skipEmptyReport=false'
    ].join(' ')
 
    String mvnCommand = "${mvnBin} ${args.join(' ')} ${goals} ${props}"
 
    echo "Running: ${mvnCommand}"
 
    int exitCode = sh(
        script: """#!/bin/bash
set -o pipefail
${mvnCommand} 2>&1 | tee '${logFile}'
""",
        returnStatus: true
    )
 
    echo "Maven Lint Exit Code: ${exitCode}"
 
    if (exitCode != 0) {
 
        String errorLines = sh(
            script: "grep -m 15 '\\[ERROR\\]' '${logFile}' || true",
            returnStdout: true
        ).trim()
 
        env.FAILURE_REASON =
            "Maven code analysis plugins failed (exit code ${exitCode})"
 
        error("""
MAVEN CODE QUALITY FAILED TO RUN
 
A plugin could not execute. This is a pipeline
error, not a code finding.
 
Exit Code : ${exitCode}
 
First errors:
${errorLines ?: 'See ' + logFile}
 
Full log:
${logFile}
""")
    }
 
    /*
     * Collect reports from every module
     */
    def findReports = { String name ->
        String out = sh(
            script: "find . -path './builds' -prune -o -path '*/target/${name}' -type f -print | sed 's|^\\./||' | sort",
            returnStdout: true
        ).trim()
        return out ? out.split('\n').toList() : []
    }
 
    def checkstyleFiles = findReports('checkstyle-result.xml')
    def spotbugsFiles = findReports('spotbugsXml.xml')
    def pmdFiles = findReports('pmd.xml')
 
    def missing = []
    if (!checkstyleFiles) { missing << 'Checkstyle (target/checkstyle-result.xml)' }
    if (!spotbugsFiles) { missing << 'SpotBugs (target/spotbugsXml.xml)' }
    if (!pmdFiles) { missing << 'PMD (target/pmd.xml)' }
 
    if (missing) {
        env.FAILURE_REASON = "Report not generated: ${missing.join(', ')}"
        error("""
MAVEN CODE QUALITY REPORT MISSING
 
No report was generated for:
${missing.join('\n')}
 
Check ${logFile}
""")
    }
 
    /*
     * Parse reports (XML parsing runs in @NonCPS helpers)
     */
    int csWarnings = 0
    int sbErrors = 0
    int sbWarnings = 0
    int pmdErrors = 0
    int pmdWarnings = 0
 
    def fullReport = new StringBuilder()
 
    def moduleOf = { String path ->
        String m = path.replaceFirst('/?target/[^/]+$', '')
        return m ?: '.'
    }
 
    def parseAll = { List files, Closure parser, String title ->
 
        int e = 0
        int w = 0
 
        fullReport << "\n=========================================\n"
        fullReport << "${title}\n"
        fullReport << "=========================================\n"
 
        files.each { String path ->
 
            sh "cp '${path}' '${rawDir}/${path.replace('/', '_')}'"
 
            def parsed
 
            try {
                parsed = parser(readFile(path))
            } catch (Exception ex) {
                env.FAILURE_REASON = "Invalid report XML: ${path}"
                error("""
Invalid XML report.
 
File  : ${path}
Error : ${ex.getMessage()}
""")
            }
 
            e += parsed.errors
            w += parsed.warnings
 
            fullReport << "\nModule: ${moduleOf(path)}  " +
                "(errors=${parsed.errors}, warnings=${parsed.warnings})\n"
            fullReport << parsed.report
        }
 
        return [errors: e, warnings: w]
    }
 
    def cs = parseAll(checkstyleFiles, { String x -> parseCheckstyle(x) },
        'CHECKSTYLE FINDINGS')
    def sb = parseAll(spotbugsFiles, { String x -> parseSpotBugs(x) },
        'SPOTBUGS FINDINGS')
    def pmd = parseAll(pmdFiles, { String x -> parsePmd(x) },
        'PMD FINDINGS')
 
    csWarnings = cs.warnings
    sbErrors = sb.errors
    sbWarnings = sb.warnings
    pmdErrors = pmd.errors
    pmdWarnings = pmd.warnings
 
    int errors = sbErrors + pmdErrors
    int warnings = csWarnings + sbWarnings + pmdWarnings
 
    String status
 
    if (errors > 0) {
        status = 'APPROVAL_REQUIRED'
    } else if (warnings > 0) {
        status = 'WARNING'
    } else {
        status = 'SUCCESS'
    }
 
    long durationSeconds =
        (System.currentTimeMillis() - startMillis).intdiv(1000)
 
    def summary = [
        tool              : 'Checkstyle+SpotBugs+PMD',
        errors            : errors,
        warnings          : warnings,
        exitCode          : exitCode,
        status            : status,
        checkstyleWarnings: csWarnings,
        spotbugsErrors    : sbErrors,
        spotbugsWarnings  : sbWarnings,
        pmdErrors         : pmdErrors,
        pmdWarnings       : pmdWarnings,
        durationSeconds   : durationSeconds
    ]
 
    String header = """
=========================================
MAVEN CODE QUALITY FINDINGS REPORT
=========================================
 
Build Number : ${env.BUILD_NUMBER}
 
Errors       : ${errors}
Warnings     : ${warnings}
Status       : ${status}
 
Checkstyle   : warnings=${csWarnings}
SpotBugs     : errors=${sbErrors}, warnings=${sbWarnings}
PMD          : errors=${pmdErrors}, warnings=${pmdWarnings}
 
Policy       : SpotBugs and PMD priority 1 = error,
               everything else = warning.
               Findings go to the Security Gate.
 
=========================================
"""
 
    writeFile(
        file: "${reportDir}/maven-lint-findings.txt",
        text: header + fullReport.toString()
    )
 
    writeFile(
        file: "${reportDir}/maven-lint-summary.json",
        text: JsonOutput.prettyPrint(JsonOutput.toJson(summary))
    )
 
    archiveArtifacts(
        artifacts: "${reportDir}/**",
        fingerprint: true,
        allowEmptyArchive: false
    )
 
    currentBuild.description =
        (currentBuild.description ?: '') +
        " | Lint[E=${errors},W=${warnings}]"
 
    echo """
=========================================
MAVEN CODE QUALITY SUMMARY
=========================================
 
Errors     : ${errors}
Warnings   : ${warnings}
Status     : ${status}
 
Checkstyle : warnings=${csWarnings}
SpotBugs   : errors=${sbErrors}, warnings=${sbWarnings}
PMD        : errors=${pmdErrors}, warnings=${pmdWarnings}
 
Duration   : ${durationSeconds}s
Reports    : ${reportDir}
 
=========================================
"""
 
    return summary
}
 
/*
 * XML helpers. XmlSlurper objects are not serializable,
 * so these must be @NonCPS and return plain maps and strings.
 */
 
@NonCPS
def newSlurper() {
    def slurper = new XmlSlurper(false, false)
    slurper.setFeature(
        'http://apache.org/xml/features/disallow-doctype-decl', true
    )
    return slurper
}
 
@NonCPS
def parseCheckstyle(String xml) {
 
    def root = newSlurper().parseText(xml)
    int errors = 0
    int warnings = 0
    def sb = new StringBuilder()
 
    root.BugInstance.each { b ->
 
        int priority = (b.@priority.text() ?: '3') as int
        String severity = priority == 1 ? 'ERROR' : 'WARNING'
 
        if (priority == 1) {
            errors++
        } else {
            warnings++
        }
 
        def src = b.depthFirst().find {
            it.name() == 'SourceLine' && it.@sourcepath.text()
        }
 
        String location = src
            ? "${src.@sourcepath.text()}:${src.@start.text() ?: '?'}"
            : 'N/A'
 
        String message = b.ShortMessage.text() ?: b.@type.text()
 
        sb << "\n[${severity}] ${b.@category.text()} / ${b.@type.text()}\n" +
            "  Location : ${location}\n" +
            "  Message  : ${message}\n"
    }
 
    return [errors: errors, warnings: warnings, report: sb.toString()]
}
 
@NonCPS
def parsePmd(String xml) {
 
    def root = newSlurper().parseText(xml)
    int errors = 0
    int warnings = 0
    def sb = new StringBuilder()
 
    root.file.each { f ->
        sb << "\nFile: ${f.@name.text()}\n"
 
        f.violation.each { v ->
            int priority = (v.@priority.text() ?: '5') as int
            String severity = priority == 1 ? 'ERROR' : 'WARNING'
 
            if (priority == 1) {
                errors++
            } else {
                warnings++
            }
 
            sb << "  [${severity}] line ${v.@beginline.text()}: " +
                "${v.@rule.text()} - ${v.text().trim()}\n"
        }
    }
 
    // Files PMD could not analyze (for example unsupported syntax)
    root.error.each { e ->
        warnings++
        sb << "\n[WARNING] PMD could not process ${e.@filename.text()}: " +
            "${e.@msg.text()}\n"
    }
 
    return [errors: errors, warnings: warnings, report: sb.toString()]
}