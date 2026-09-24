def call() {

    sh '''
        export PATH=/home/virendra-mahajan/.nvm/versions/node/v24.*/bin:$PATH

        node -v
        npm -v

        echo "Installing dependencies..."
        npm install

        echo "Running ESLint..."
        npm run lint
    '''
}