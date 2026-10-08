def call() {

    if (fileExists('package.json')) {

        def packageJson = readFile('package.json')

        if (packageJson.contains('@nestjs/core')) {
            echo "Project Type Detected: NESTJS"
            return 'NESTJS'
        }

        echo "Project Type Detected: NODE"
        return 'NODE'
    }

    if (fileExists('pom.xml')) {
        echo "Project Type Detected: MAVEN"
        return 'MAVEN'
    }

    if (fileExists('build.gradle') ||
        fileExists('build.gradle.kts')) {
        echo "Project Type Detected: GRADLE"
        return 'GRADLE'
    }

    if (fileExists('requirements.txt') ||
        fileExists('pyproject.toml')) {
        echo "Project Type Detected: PYTHON"
        return 'PYTHON'
    }

    if (fileExists('go.mod')) {
        echo "Project Type Detected: GO"
        return 'GO'
    }

    error("Unsupported project type")
}