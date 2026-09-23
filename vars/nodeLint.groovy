def call() {

    sh '''
        echo "Installing dependencies..."

        npm install

        echo "Running ESLint..."

        npm run lint
    '''
}