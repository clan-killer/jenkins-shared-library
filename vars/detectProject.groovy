/*
 * detectProject
 *
 * Detects the project type from the workspace root.
 * Must run AFTER checkout.
 *
 * Returns one of:
 *   FLUTTER | MAVEN | GRADLE | NESTJS | NODE | GO | PYTHON
 *
 * Optional override: job parameter PROJECT_TYPE
 *   (AUTO or empty = detect automatically)
 *
 * Detection order (first match wins):
 *   1. pubspec.yaml containing "flutter"
 *   2. pom.xml
 *   3. build.gradle / build.gradle.kts
 *   4. package.json (NESTJS if @nestjs/core, else NODE)
 *   5. go.mod
 *   6. requirements.txt / pyproject.toml
 */
 
def call() {
 
    def supportedTypes = [
        'FLUTTER', 'MAVEN', 'GRADLE',
        'NESTJS', 'NODE', 'GO', 'PYTHON'
    ]
 
    String detected = null
    String method = 'AUTO-DETECTED'
 
    /*
     * Manual override
     */
    String override =
        (params?.PROJECT_TYPE ?: 'AUTO')
            .toString()
            .trim()
            .toUpperCase()
 
    if (override && override != 'AUTO') {
 
        if (!supportedTypes.contains(override)) {
            error("""
Invalid PROJECT_TYPE override: ${override}
 
Supported values:
AUTO, ${supportedTypes.join(', ')}
""")
        }
 
        detected = override
        method = 'MANUAL OVERRIDE'
    }
 
    /*
     * Auto detection
     */
    if (detected == null) {
 
        if (fileExists('pubspec.yaml') &&
            readFile('pubspec.yaml').contains('flutter')) {
 
            detected = 'FLUTTER'
 
        } else if (fileExists('pom.xml')) {
 
            detected = 'MAVEN'
 
        } else if (fileExists('build.gradle') ||
                   fileExists('build.gradle.kts')) {
 
            detected = 'GRADLE'
 
        } else if (fileExists('package.json')) {
 
            detected =
                readFile('package.json').contains('@nestjs/core')
                    ? 'NESTJS'
                    : 'NODE'
 
        } else if (fileExists('go.mod')) {
 
            detected = 'GO'
 
        } else if (fileExists('requirements.txt') ||
                   fileExists('pyproject.toml')) {
 
            detected = 'PYTHON'
        }
    }
 
    if (detected == null) {
        error("""
Unsupported project type.
 
No known build file found in the workspace root.
 
Looked for:
pubspec.yaml (flutter), pom.xml, build.gradle,
build.gradle.kts, package.json, go.mod,
requirements.txt, pyproject.toml
 
If the project lives in a sub-directory,
this pipeline does not support it yet.
""")
    }
 
    echo """
====================================
PROJECT DETECTION
====================================
 
Detected Type : ${detected}
Method        : ${method}
 
====================================
"""
 
    return detected
}