/**
 * GitHub Integration Functions for Jenkins Pipelines
 * Handles GitHub status updates and deployment tracking
 * 
 * Usage:
 *   github.updateGitHubStatus('success', 'Build passed', 'Jenkins Build')
 *   github.createDeployment('production', 'main')
 *   github.trackDeployment('staging') { /* deployment steps */ }
 * 
 * Or as a step:
 *   github(status: [state: 'success', description: 'Build passed', context: 'Jenkins Build'])
 */

def call(Map config = [:]) {
    if (config.status) {
        def status = config.status
        updateGitHubStatus(
            status.state ?: 'pending',
            status.description ?: '',
            status.context ?: 'Jenkins Pipeline'
        )
    } else if (config.deployment) {
        def deployment = config.deployment
        createDeployment(
            deployment.environment ?: 'production',
            deployment.ref ?: env.GIT_COMMIT_SHA ?: env.GITHUB_BRANCH_NAME
        )
    } else if (config.trackDeployment) {
        def track = config.trackDeployment
        trackDeployment(track.environment ?: 'production', track.steps ?: {})
    } else {
        error("github: Invalid parameters. Use status, deployment, or trackDeployment")
    }
}

def updateGitHubStatus(String state, String description, String context_description) {
    if (!env.GIT_COMMIT_SHA) {
        logging.logWarning("No Git commit SHA available, attempting to retrieve...")
        
        try {
            def sha = null
            
            try {
                sha = bat(script: '@git rev-parse HEAD', returnStdout: true).trim()
            } catch (Exception e) {
                logging.logWarning("Direct git command failed: ${e.getMessage()}")
            }
            
            if (!sha && env.GIT_COMMIT) {
                sha = env.GIT_COMMIT
            }
            
            if (sha) {
                env.GIT_COMMIT_SHA = sha
                logging.logSuccess("Retrieved Git commit SHA: ${sha}")
            } else {
                logging.logWarning("Unable to retrieve Git commit SHA, skipping GitHub status update")
                return
            }
        } catch (Exception e) {
            logging.logError("Failed to retrieve Git commit SHA: ${e.getMessage()}")
            return
        }
    }
    
    if (env.GITHUB_STATUS_UPDATE == "true") {
        try {
            def statusUrl = "https://api.github.com/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/statuses/${env.GIT_COMMIT_SHA}"
            def buildUrl = "${env.BUILD_URL}"
            
            logging.logInfo("GitHub Status", "${state} - ${description}")
            logging.logInfo("Repository", "${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}")
            logging.logInfo("Commit SHA", env.GIT_COMMIT_SHA)
            
            def payloadMap = [:]
            payloadMap.state = state
            payloadMap.target_url = buildUrl
            payloadMap.description = context_description
            payloadMap.context = env.GITHUB_STATUS_CONTEXT
            
            def jsonPayload = groovy.json.JsonOutput.toJson(payloadMap)
            
            withCredentials([usernamePassword(
                credentialsId: env.GITHUB_TOKEN_CREDENTIAL_ID,
                usernameVariable: 'GITHUB_APP_ID',
                passwordVariable: 'GITHUB_TOKEN'
            )]) {
                def headersMap = [:]
                headersMap.put('Accept', 'application/vnd.github.v3+json')
                headersMap.put('Content-Type', 'application/json')
                headersMap.put('User-Agent', 'Jenkins-Generic-Pipeline/2.0')
                
                def authHeader = "Bearer " + env.GITHUB_TOKEN
                headersMap.put('Authorization', authHeader)
                
                def requestHeaders = []
                headersMap.each { key, value ->
                    if (key == 'Authorization') {
                        requestHeaders.add([name: key, value: value, maskValue: true])
                    } else {
                        requestHeaders.add([name: key, value: value])
                    }
                }
                
                try {
                    def response = httpRequest(
                        url: statusUrl,
                        httpMode: 'POST',
                        customHeaders: requestHeaders,
                        requestBody: jsonPayload,
                        validResponseCodes: '200:299',
                        timeout: 30,
                        ignoreSslErrors: false,
                        consoleLogResponseBody: false,
                        quiet: true
                    )
                    
                    logging.logSuccess("GitHub status updated successfully (HTTP ${response.status})")
                    
                } catch (Exception httpEx) {
                    logging.logWarning("Primary GitHub auth failed, trying fallback method...")
                    
                    try {
                        def fallbackHeaders = []
                        headersMap.each { key, value ->
                            if (key == 'Authorization') {
                                def fallbackAuth = "token " + env.GITHUB_TOKEN
                                fallbackHeaders.add([name: key, value: fallbackAuth, maskValue: true])
                            } else {
                                fallbackHeaders.add([name: key, value: value])
                            }
                        }
                        
                        def fallbackResponse = httpRequest(
                            url: statusUrl,
                            httpMode: 'POST',
                            customHeaders: fallbackHeaders,
                            requestBody: jsonPayload,
                            validResponseCodes: '200:299',
                            timeout: 30,
                            ignoreSslErrors: false,
                            consoleLogResponseBody: false,
                            quiet: true
                        )
                        
                        logging.logSuccess("GitHub status updated with fallback method (HTTP ${fallbackResponse.status})")
                    } catch (Exception fallbackEx) {
                        logging.logWarning("Both GitHub authentication methods failed - this won't affect the build process")
                    }
                }
            }
            
        } catch (Exception e) {
            logging.logWarning("Failed to update GitHub status: ${e.class.simpleName} - ${e.getMessage()}")
            logging.logInfo("Note", "GitHub status update failure won't affect the build process")
        }
    }
}

def createDeployment(String environment, String ref) {
    if (!env.GIT_COMMIT_SHA) {
        logging.logWarning("No Git commit SHA available, skipping deployment creation")
        return null
    }
    
    try {
        def deploymentUrl = "https://api.github.com/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/deployments"
        
        logging.logInfo("Creating Deployment", "Environment: ${environment}")
        logging.logInfo("Deployment Ref", ref)
        
        def payloadMap = [:]
        payloadMap.ref = ref
        payloadMap.environment = environment
        payloadMap.description = "Deployment to ${environment} environment via Jenkins"
        payloadMap.auto_merge = false
        payloadMap.required_contexts = []
        
        def jsonPayload = groovy.json.JsonOutput.toJson(payloadMap)
        
        withCredentials([usernamePassword(
            credentialsId: env.GITHUB_TOKEN_CREDENTIAL_ID,
            usernameVariable: 'GITHUB_APP_ID',
            passwordVariable: 'GITHUB_TOKEN'
        )]) {
            def headersMap = [:]
            headersMap.put('Accept', 'application/vnd.github+json')
            headersMap.put('Content-Type', 'application/json')
            headersMap.put('User-Agent', 'Jenkins-Deployment-Tracker/1.0')
            headersMap.put('Authorization', "Bearer " + env.GITHUB_TOKEN)
            
            def requestHeaders = []
            headersMap.each { key, value ->
                if (key == 'Authorization') {
                    requestHeaders.add([name: key, value: value, maskValue: true])
                } else {
                    requestHeaders.add([name: key, value: value])
                }
            }
            
            try {
                def response = httpRequest(
                    url: deploymentUrl,
                    httpMode: 'POST',
                    customHeaders: requestHeaders,
                    requestBody: jsonPayload,
                    validResponseCodes: '200:299,422',
                    timeout: 30,
                    ignoreSslErrors: false,
                    quiet: true
                )
                
                if (response.status == 422) {
                    logging.logWarning("GitHub API returned 422 - deployment may already exist")
                    return null
                }
                
                def responseData = readJSON text: response.content
                env.DEPLOYMENT_ID = responseData.id.toString()
                
                logging.logSuccess("GitHub deployment created successfully (ID: ${env.DEPLOYMENT_ID})")
                return env.DEPLOYMENT_ID
            } catch (Exception httpEx) {
                // Handle permission errors gracefully
                def errorMsg = httpEx.getMessage() ?: ''
                if (errorMsg.contains('403') || errorMsg.contains('401')) {
                    logging.logWarning("GitHub deployment creation failed: Token lacks 'repo' or 'deployments' permission")
                    logging.logInfo("Note", "GitHub deployment tracking is optional - continuing without it")
                    return null
                } else {
                    throw httpEx
                }
            }
        }
        
    } catch (Exception e) {
        logging.logWarning("Failed to create GitHub deployment: ${e.getMessage()}")
        logging.logInfo("Note", "GitHub deployment tracking is optional - continuing without it")
        return null
    }
}

def updateDeploymentStatus(String deploymentId, String state, String description, String environmentUrl = null) {
    if (!deploymentId) {
        // Silently skip if no deployment ID (deployment creation may have failed)
        return
    }
    
    try {
        def statusUrl = "https://api.github.com/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}/deployments/${deploymentId}/statuses"
        
        logging.logInfo("Updating Deployment", "ID: ${deploymentId}, State: ${state}")
        
        def payloadMap = [:]
        payloadMap.state = state
        payloadMap.description = description
        payloadMap.log_url = "${env.BUILD_URL}console"
        
        if (environmentUrl) {
            payloadMap.environment_url = environmentUrl
        }
        
        def jsonPayload = groovy.json.JsonOutput.toJson(payloadMap)
        
        withCredentials([usernamePassword(
            credentialsId: env.GITHUB_TOKEN_CREDENTIAL_ID,
            usernameVariable: 'GITHUB_APP_ID',
            passwordVariable: 'GITHUB_TOKEN'
        )]) {
            def headersMap = [:]
            headersMap.put('Accept', 'application/vnd.github+json')
            headersMap.put('Content-Type', 'application/json')
            headersMap.put('User-Agent', 'Jenkins-Deployment-Tracker/1.0')
            headersMap.put('Authorization', "Bearer " + env.GITHUB_TOKEN)
            
            def requestHeaders = []
            headersMap.each { key, value ->
                if (key == 'Authorization') {
                    requestHeaders.add([name: key, value: value, maskValue: true])
                } else {
                    requestHeaders.add([name: key, value: value])
                }
            }
            
            try {
                def response = httpRequest(
                    url: statusUrl,
                    httpMode: 'POST',
                    customHeaders: requestHeaders,
                    requestBody: jsonPayload,
                    validResponseCodes: '200:299',
                    timeout: 30,
                    ignoreSslErrors: false,
                    quiet: true
                )
                
                logging.logSuccess("Deployment status updated: ${state}")
            } catch (Exception httpEx) {
                // Handle permission errors gracefully
                def errorMsg = httpEx.getMessage() ?: ''
                if (errorMsg.contains('403') || errorMsg.contains('401') || errorMsg.contains('404')) {
                    logging.logWarning("GitHub deployment status update failed: Token lacks permissions or deployment not found")
                    logging.logInfo("Note", "GitHub deployment tracking is optional - continuing without it")
                } else {
                    logging.logWarning("Failed to update deployment status: ${httpEx.getMessage()}")
                }
            }
        }
        
    } catch (Exception e) {
        logging.logWarning("Failed to update deployment status: ${e.getMessage()}")
        logging.logInfo("Note", "GitHub deployment tracking is optional - continuing without it")
    }
}

def trackDeployment(String environment, Closure deploymentSteps) {
    def deploymentId = null
    
    if (env.GITHUB_DEPLOYMENTS_UPDATE == "true") {
        try {
            // Create deployment record (may fail due to permissions - that's OK)
            deploymentId = createDeployment(environment, env.GIT_COMMIT_SHA ?: env.GITHUB_BRANCH_NAME)
            
            if (deploymentId) {
                updateDeploymentStatus(deploymentId, 'in_progress', "Deployment to ${environment} is in progress...")
            } else {
                logging.logInfo("GitHub Deployment", "Tracking disabled or unavailable - continuing deployment")
            }
            
            // Execute deployment steps (this is the important part - must not fail)
            deploymentSteps()
            
            // Mark as successful (only if we have a deployment ID)
            if (deploymentId) {
                def environmentUrl = env.ENVIRONMENT_URL
                updateDeploymentStatus(deploymentId, 'success', "Successfully deployed to ${environment}", environmentUrl)
            }
            
        } catch (Exception e) {
            // Mark as failed (only if we have a deployment ID)
            if (deploymentId) {
                updateDeploymentStatus(deploymentId, 'failure', "Deployment to ${environment} failed: ${e.getMessage()}")
            }
            
            currentBuild.description = "❌ ${environment} deployment failed (Build #${env.BUILD_NUMBER})"
            throw e
        }
    } else {
        // Execute deployment steps without tracking
        deploymentSteps()
    }
}