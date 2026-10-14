/*
 * resolveTools
 *
 * Picks the build tool versions the PROJECT declares and puts
 * the matching Jenkins tools on the PATH for all later stages.
 * Replaces the fixed tools { } block in the Jenkinsfile.
 *
 * Runs right after Detect. Must run after checkout.
 *
 * Where versions come from (first match wins):
 *
 *   Java   pom.xml: maven.compiler.release, maven.compiler.target,
 *          java.version, or <release> of maven-compiler-plugin.
 *          "1.8" counts as 8. ${property} references are resolved.
 *          Nothing declared -> DEFAULT_JAVA_VERSION (17)
 *
 *   Maven  mvnw present -> the wrapper downloads its own version,
 *          no Jenkins tool needed (version read for the summary).
 *          Otherwise Jenkins tool Maven<major> (default Maven3).
 *
 *   Node   .nvmrc, .node-version, or engines.node in package.json
 *          (major version). Nothing declared -> DEFAULT_NODE_VERSION (24)
 *
 * Jenkins tool names (Manage Jenkins > Tools), pattern <Tool><Major>:
 *   JDK8, JDK11, JDK17, JDK21     type: JDK
 *   Maven3                        type: Maven
 *   Node18, Node20, Node22, Node24 type: NodeJS
 *
 * The pipeline FAILS if the project needs a version that has no
 * Jenkins tool. It never builds silently with a different version.
 *
 * Optional environment:
 *   JDK_TOOL_PATTERN       default 'JDK%s'
 *   MAVEN_TOOL_PATTERN     default 'Maven%s'
 *   NODE_TOOL_PATTERN      default 'Node%s'
 *   DEFAULT_JAVA_VERSION   default '17'
 *   DEFAULT_NODE_VERSION   default '24'
 *
 * Returns:
 *   [projectType, javaVersion, javaSource, javaTool, javaActual,
 *    mavenMode, mavenVersion, mavenSource, mavenTool, mavenActual,
 *    nodeVersion, nodeSource, nodeTool, nodeActual]
 */
 
def call(String projectType) {
 
    String type = (projectType ?: '').toString().toUpperCase()
 
    String jdkPattern = env.JDK_TOOL_PATTERN ?: 'JDK%s'
    String mavenPattern = env.MAVEN_TOOL_PATTERN ?: 'Maven%s'
    String nodePattern = env.NODE_TOOL_PATTERN ?: 'Node%s'
 
    String defaultJava = env.DEFAULT_JAVA_VERSION ?: '17'
    String defaultNode = env.DEFAULT_NODE_VERSION ?: '24'
 
    def tools = [
        projectType : type,
        javaVersion : 'N/A', javaSource  : 'N/A', javaTool  : 'N/A', javaActual  : 'N/A',
        mavenMode   : 'N/A', mavenVersion: 'N/A', mavenSource: 'N/A',
        mavenTool   : 'N/A', mavenActual : 'N/A',
        nodeVersion : 'N/A', nodeSource  : 'N/A', nodeTool  : 'N/A', nodeActual  : 'N/A'
    ]
 
    def binDirs = []
 
    /*
     * JAVA + MAVEN
     */
    if (type == 'MAVEN') {
 
        // Java version from pom.xml
        def java = readJavaVersion(readFile('pom.xml'))
 
        if (java.version) {
            tools.javaVersion = java.version
            tools.javaSource = "pom.xml (${java.source})"
        } else {
            tools.javaVersion = defaultJava
            tools.javaSource = 'not declared, pipeline default'
        }
 
        tools.javaTool = String.format(jdkPattern, tools.javaVersion)
 
        String javaHome = installTool(tools.javaTool, 'jdk',
            "Java ${tools.javaVersion}", tools.javaSource)
 
        env.JAVA_HOME = javaHome
        binDirs << "${javaHome}/bin"
 
        // Maven: wrapper or Jenkins tool
        if (fileExists('mvnw')) {
 
            tools.mavenMode = 'WRAPPER'
            tools.mavenTool = 'none (mvnw)'
 
            String props = '.mvn/wrapper/maven-wrapper.properties'
            String wrapperVersion = fileExists(props)
                ? readWrapperMavenVersion(readFile(props))
                : null
 
            tools.mavenVersion = wrapperVersion ?: 'from mvnw'
            tools.mavenSource = 'Maven Wrapper'
 
        } else {
 
            tools.mavenMode = 'TOOL'
 
            String required = readMavenPrerequisite(readFile('pom.xml'))
 
            if (required) {
                tools.mavenVersion = required
                tools.mavenSource = 'pom.xml (prerequisites)'
            } else {
                tools.mavenVersion = '3'
                tools.mavenSource = 'not declared, pipeline default'
            }
 
            tools.mavenTool = String.format(mavenPattern, tools.mavenVersion)
 
            String mavenHome = installTool(tools.mavenTool, 'maven',
                "Maven ${tools.mavenVersion}", tools.mavenSource)
 
            env.MAVEN_HOME = mavenHome
            binDirs << "${mavenHome}/bin"
        }
    }
 
    /*
     * NODE
     */
    if (type in ['NODE', 'NESTJS']) {
 
        def node = [version: null, source: null]
 
        if (fileExists('.nvmrc')) {
            node = [version: readNodeMajor(readFile('.nvmrc')), source: '.nvmrc']
        } else if (fileExists('.node-version')) {
            node = [version: readNodeMajor(readFile('.node-version')), source: '.node-version']
        } else if (fileExists('package.json')) {
            String engines = readEnginesNode(readFile('package.json'))
            if (engines) {
                node = [version: readNodeMajor(engines), source: "package.json engines.node (${engines})"]
            }
        }
 
        if (node.source && !node.version) {
            env.FAILURE_REASON = "Unsupported Node version format in ${node.source}"
            error("""
Node version in ${node.source} could not be read.
 
Use a plain version number, for example: 20 or v20.11.0
(names such as lts/iron are not supported).
""")
        }
 
        if (node.version) {
            tools.nodeVersion = node.version
            tools.nodeSource = node.source
        } else {
            tools.nodeVersion = defaultNode
            tools.nodeSource = 'not declared, pipeline default'
        }
 
        tools.nodeTool = String.format(nodePattern, tools.nodeVersion)
 
        String nodeHome = installTool(tools.nodeTool, 'nodejs',
            "Node ${tools.nodeVersion}", tools.nodeSource)
 
        binDirs << "${nodeHome}/bin"
    }
 
    /*
     * Put the tools first on the PATH for every later stage
     */
    if (binDirs) {
        env.PATH = "${binDirs.join(':')}:${env.PATH}"
    }
 
    /*
     * Report the versions actually in use
     */
    if (tools.javaTool != 'N/A') {
        tools.javaActual = sh(
            script: 'java -version 2>&1 | grep -i version | head -1',
            returnStdout: true
        ).trim()
    }
 
    if (tools.mavenMode == 'TOOL') {
        tools.mavenActual = sh(
            script: 'mvn -v 2>&1 | head -1',
            returnStdout: true
        ).trim()
    } else if (tools.mavenMode == 'WRAPPER') {
        tools.mavenActual = "Maven Wrapper ${tools.mavenVersion}"
    }
 
    if (tools.nodeTool != 'N/A') {
        tools.nodeActual = sh(
            script: 'node -v 2>&1 | head -1',
            returnStdout: true
        ).trim()
    }
 
    echo """
====================================
BUILD TOOLS
====================================
 
Project Type : ${type}
 
Java         : ${tools.javaVersion}
  Source     : ${tools.javaSource}
  Tool       : ${tools.javaTool}
  In Use     : ${tools.javaActual}
 
Maven        : ${tools.mavenVersion}
  Source     : ${tools.mavenSource}
  Tool       : ${tools.mavenTool}
  In Use     : ${tools.mavenActual}
 
Node         : ${tools.nodeVersion}
  Source     : ${tools.nodeSource}
  Tool       : ${tools.nodeTool}
  In Use     : ${tools.nodeActual}
 
====================================
"""
 
    return tools
}
 
/*
 * Install or locate a Jenkins tool. Fails the pipeline with a
 * clear message if no tool of that name is configured.
 */
def installTool(String toolName, String toolType, String label, String source) {
 
    try {
        return tool(name: toolName, type: toolType)
    } catch (Exception ex) {
        env.FAILURE_REASON =
            "Project needs ${label} but Jenkins has no tool named ${toolName}"
        error("""
REQUIRED BUILD TOOL NOT CONFIGURED
 
Project needs : ${label}
Declared in   : ${source}
Jenkins tool  : ${toolName} (type: ${toolType})
 
Add it in Manage Jenkins > Tools with the exact
name "${toolName}", or change the version the
project declares.
 
Error: ${ex.getMessage()}
""")
    }
}
 
/*
 * Parsing helpers. Regex Matcher objects are not serializable,
 * so these are @NonCPS and return plain strings and maps.
 */
 
@NonCPS
def readJavaVersion(String pom) {
 
    // Only the project's own <properties>, not dependency blocks
    def props = [:]
    def propsBlock = (pom =~ /(?s)<properties>(.*?)<\/properties>/)
 
    if (propsBlock.find()) {
        def entries = (propsBlock.group(1) =~ /<([A-Za-z0-9._-]+)>\s*([^<]*?)\s*<\/\1>/)
        while (entries.find()) {
            props[entries.group(1)] = entries.group(2)
        }
    }
 
    def resolve = { String value ->
        String v = value
        int guard = 0
        while (v != null && v.startsWith('${') && v.endsWith('}') && guard < 5) {
            v = props[v.substring(2, v.length() - 1)]
            guard++
        }
        return v
    }
 
    def candidates = [
        'maven.compiler.release',
        'maven.compiler.target',
        'java.version'
    ]
 
    for (String key : candidates) {
        if (props[key]) {
            String major = normalizeJava(resolve(props[key]))
            if (major) {
                return [version: major, source: key]
            }
        }
    }
 
    // <release> inside maven-compiler-plugin configuration
    def plugin = (pom =~ /(?s)<artifactId>maven-compiler-plugin<\/artifactId>(.*?)<\/plugin>/)
 
    if (plugin.find()) {
        def release = (plugin.group(1) =~ /<(release|target)>\s*([^<]+?)\s*<\/\1>/)
        if (release.find()) {
            String major = normalizeJava(resolve(release.group(2)))
            if (major) {
                return [version: major, source: "maven-compiler-plugin <${release.group(1)}>"]
            }
        }
    }
 
    return [version: null, source: null]
}
 
@NonCPS
def normalizeJava(String value) {
    if (!value) {
        return null
    }
    String v = value.trim()
    if (v.startsWith('1.')) {
        v = v.substring(2)
    }
    def m = (v =~ /^(\d+)/)
    return m.find() ? m.group(1) : null
}
 
@NonCPS
def readWrapperMavenVersion(String props) {
    def m = (props =~ /apache-maven\/(\d+\.\d+\.\d+)\//)
    return m.find() ? m.group(1) : null
}
 
@NonCPS
def readMavenPrerequisite(String pom) {
    def m = (pom =~ /(?s)<prerequisites>.*?<maven>\s*\[?(\d+)/)
    return m.find() ? m.group(1) : null
}
 
@NonCPS
def readEnginesNode(String packageJson) {
    def m = (packageJson =~ /(?s)"engines"\s*:\s*\{[^}]*?"node"\s*:\s*"([^"]+)"/)
    return m.find() ? m.group(1) : null
}
 
@NonCPS
def readNodeMajor(String value) {
    if (!value) {
        return null
    }
    def m = (value.trim() =~ /^[^0-9]*?(\d+)/)
    if (value.trim().startsWith('lts') || !m.find()) {
        return null
    }
    return m.group(1)
}