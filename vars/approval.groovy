/**
 * Approval Utility Functions for Jenkins Pipelines
 * Handles deployment approvals from different roles
 * 
 * Usage:
 *   approval.requestApproval(message: 'Deploy to Production?', approverGroup: 'group:Jenkins_Admin')
 *   approval.requestManagerApproval(environment: 'Production')
 *   approval.requestDBAApproval(environment: 'Production')
 * 
 * Or as a step:
 *   approval(action: 'requestApproval', message: 'Deploy?', approverGroup: 'group:Admin')
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("approval: 'action' parameter is required")
    }
    
    switch(action) {
        case 'requestApproval':
            if (!config.message || !config.approverGroup) {
                error("approval: 'message' and 'approverGroup' parameters are required for requestApproval")
            }
            requestApproval(config.message, config.approverGroup, config.okText ?: 'Approve')
            break
        case 'requestManagerApproval':
            if (!config.environment) {
                error("approval: 'environment' parameter is required for requestManagerApproval")
            }
            requestManagerApproval(config.environment)
            break
        case 'requestDBAApproval':
            if (!config.environment) {
                error("approval: 'environment' parameter is required for requestDBAApproval")
            }
            requestDBAApproval(config.environment)
            break
        default:
            error("approval: Unknown action '${action}'. Valid actions: requestApproval, requestManagerApproval, requestDBAApproval")
    }
}

def requestApproval(String message, String approverGroup, String okText = 'Approve') {
    logging.logSubSection("Deployment Approval Request")
    logging.logInfo("Message", message)
    logging.logInfo("Approver Group", approverGroup)
    
    def approval = input(
        message: message,
        ok: okText,
        submitter: approverGroup
    )

    def approver = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')?.first()?.userId ?: 'unknown'
    logging.logSuccess("Deployment approved by: ${approver}")
    return approver
}

def requestManagerApproval(String environment) {
    logging.logSection("MANAGER APPROVAL REQUIRED")
    logging.logInfo("Environment", environment)
    logging.logInfo("Approval Type", "Manager Approval")
    
    def message = """
⚠️ MANAGER APPROVAL REQUIRED ⚠️

Deployment to: ${environment}
Project: ${env.PROJECT_NAME}
Build Number: ${env.BUILD_NUMBER}

Please review and approve this deployment.
"""
    
    def approver = requestApproval(
        message,
        'group:Jenkins_Manager',
        'Approve Deployment'
    )
    
    logging.logSuccess("Manager approval granted by: ${approver}")
    return approver
}

def requestDBAApproval(String environment) {
    logging.logSection("DBA APPROVAL REQUIRED")
    logging.logInfo("Environment", environment)
    logging.logInfo("Approval Type", "DBA Approval")
    
    def message = """
⚠️ DBA APPROVAL REQUIRED ⚠️

Deployment to: ${environment}
Project: ${env.PROJECT_NAME}
Build Number: ${env.BUILD_NUMBER}

This deployment may affect database operations.
Please review and approve this deployment.
"""
    
    def approver = requestApproval(
        message,
        'group:Jenkins_DBA',
        'Approve Deployment'
    )
    
    logging.logSuccess("DBA approval granted by: ${approver}")
    return approver
}

