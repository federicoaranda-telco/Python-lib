def call(String language, Map additionalEnv = [:]) {
    switch (language) {
        case 'go':
            goPipeline(additionalEnv)
            break
        case 'vite':
            vitePipeline(additionalEnv)
            break
        case 'laravel':
            laravelPipeline(additionalEnv)
            break
        case 'node':
            nodePipeline(additionalEnv)
            break
        case 'flutter':
            flutterPipeline(additionalEnv)
            break
        case 'default':
            defaultPipeline(additionalEnv)
            break
        default:
            error "Unsupported language: ${language}"
    }
}
