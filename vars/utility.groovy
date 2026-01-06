/**
 * General Utility Functions for Jenkins Pipelines
 * Common utility operations like workspace cleanup
 * 
 * Usage:
 *   utility.cleanupWorkspace()
 * 
 * Or as a step:
 *   utility(action: 'cleanupWorkspace')
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("utility: 'action' parameter is required")
    }
    
    switch(action) {
        case 'cleanupWorkspace':
            cleanupWorkspace()
            break
        default:
            error("utility: Unknown action '${action}'. Valid actions: cleanupWorkspace")
    }
}

def cleanupWorkspace() {
    logging.logSection("PIPELINE CLEANUP")
    
    // Cleanup build server
    node(env.BUILD_AGENT_LABEL) {
        logging.logInfo("Cleanup", "Build server workspace")
        cleanWs(
            cleanWhenSuccess: true,
            cleanWhenFailure: true,
            cleanWhenAborted: true,
            cleanWhenUnstable: true,
            deleteDirs: true,
            disableDeferredWipeout: true,
            notFailBuild: true,
        )
    }
    
    // Cleanup deploy server (if different)
    if (env.BUILD_AGENT_LABEL != env.DEPLOY_AGENT_LABEL) {
        node(env.DEPLOY_AGENT_LABEL) {
            logging.logInfo("Cleanup", "Deploy server workspace")
            cleanWs(
                cleanWhenSuccess: true,
                cleanWhenFailure: true,
                cleanWhenAborted: true,
                cleanWhenUnstable: true,
                deleteDirs: true,
                disableDeferredWipeout: true,
                notFailBuild: true,
            )
        }
    }
    
    logging.logSuccess("Workspace cleanup completed")
}

