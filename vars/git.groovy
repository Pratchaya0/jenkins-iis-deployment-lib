/**
 * Git Utility Functions for Jenkins Pipelines
 * Handles source code checkout and Git operations
 * 
 * Usage:
 *   git.checkoutCode()
 * 
 * Or as a step:
 *   git(action: 'checkoutCode')
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("git: 'action' parameter is required")
    }
    
    switch(action) {
        case 'checkoutCode':
            checkoutCode()
            break
        default:
            error("git: Unknown action '${action}'. Valid actions: checkoutCode")
    }
}

def checkoutCode() {
    logging.logSubSection("Source Code Checkout")
    
    cleanWs()
    checkout scmGit(
        branches: [[name: "*/${env.GITHUB_BRANCH_NAME}"]], 
        extensions: [], 
        userRemoteConfigs: [[
            credentialsId: "${env.GITHUB_TOKEN_CREDENTIAL_ID}", 
            url: "https://github.com/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}.git"
        ]]
    )
    
    // Capture Git commit SHA
    try {
        def gitCommitResult = powershell(
            script: 'git rev-parse HEAD',
            returnStdout: true
        )
        env.GIT_COMMIT_SHA = gitCommitResult.trim()
        logging.logSuccess("Git commit SHA captured: ${env.GIT_COMMIT_SHA}")
    } catch (Exception e) {
        logging.logWarning("Could not capture Git commit SHA - GitHub status updates will be limited")
        env.GIT_COMMIT_SHA = null
    }
}

