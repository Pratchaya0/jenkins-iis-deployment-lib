/**
 * Notification Utility Functions for Jenkins Pipelines
 * Handles Discord notifications and build summary
 * 
 * Usage:
 *   notification()  // Send default Discord notification
 *   notification.sendDiscordNotification()  // Explicit call
 */

def call(Map config = [:]) {
    sendDiscordNotification()
}

def sendDiscordNotification() {
    try {
        // Calculate build duration
        def durationMs = currentBuild.duration ?: 0
        def durationSeconds = (durationMs / 1000).intValue()
        def durationMinutes = (durationSeconds / 60).intValue()
        def remainingSeconds = durationSeconds % 60
        
        // Get job information
        def jobName = env.JOB_NAME ?: 'Unknown Job'
        def buildNumber = env.BUILD_NUMBER ?: 'Unknown'
        def buildUrl = env.BUILD_URL ?: 'No URL'
        
        // Enhanced deployer detection
        def deployer = detectDeployer()
        def triggerType = detectTriggerType()
        def additionalInfo = ''
        
        // Add branch information
        if (env.GIT_BRANCH) {
            def branchName = env.GIT_BRANCH.replaceAll('^origin/', '')
            additionalInfo = additionalInfo ? "${additionalInfo} (${branchName})" : "(${branchName})"
        }
        
        // Format duration
        def durationText = durationMinutes > 0 ? 
            "${durationMinutes} นาที ${remainingSeconds} วินาที" : 
            "${remainingSeconds} วินาที"
        
        // Determine status and message
        def status = currentBuild.result ?: 'SUCCESS'
        def emoji = status == 'SUCCESS' ? '✅' : '❌'
        def statusText = status == 'SUCCESS' ? 'สำเร็จ' : 'ล้มเหลว'
        
        // Build message with all info
        def deployerInfo = additionalInfo ? "${deployer} ${additionalInfo}" : deployer
        def message = """${emoji} Deploy ${statusText}
แอปพลิเคชัน: ${jobName}
Build #${buildNumber}
ระยะเวลา: ${durationText}
ผู้ Deploy: ${deployerInfo}
ประเภท: ${triggerType}
URL: ${buildUrl}"""
        
        echo "=== Notification Message ==="
        echo message
        
        // Send Discord notification inline (no separate pipeline = no SPOF/deadlock)
        sendDiscordWebhook(message, status)
        
    } catch (Exception mainEx) {
        echo "ERROR in post-build notification: ${mainEx.getMessage()}"
        mainEx.printStackTrace()
    }
}

/**
 * Sends Discord notification via webhook directly (no separate pipeline).
 * Requires env.WEBHOOK_URL and env.AVATAR_URL to be set (e.g. in pipeline environment or Jenkins global).
 */
def sendDiscordWebhook(String message, String status) {
    def webhookUrl = env.WEBHOOK_URL
    def avatarUrl = env.AVATAR_URL ?: 'https://lineagentapi.uatsiamsmile.com/Resource/image317735_20251002_110824.png'
    def username = 'Jenkins'

    // Try to load from config
    if (env.CONFIG) {
        try {
            def config = readJSON text: env.CONFIG
            // config is a list, project is first element
             def project = config[0]
             if (project && project.environment_config) {
                 if (project.environment_config.webhook_url) webhookUrl = project.environment_config.webhook_url
                 if (project.environment_config.avatar_url) avatarUrl = project.environment_config.avatar_url
                 if (project.environment_config.username) username = project.environment_config.username
             }
        } catch (Exception e) {
            echo "Warning: Could not read config for notification settings: ${e.message}"
        }
    }

    if (!webhookUrl?.trim()) {
        echo "WEBHOOK_URL not set, skipping Discord notification"
        return
    }
    try {
        def color = 3447003   // blue
        def emoji = 'ℹ️'
        switch (status?.toUpperCase()) {
            case 'SUCCESS':
                color = 3066993
                emoji = '✅'
                break
            case 'FAILURE':
                color = 15158332
                emoji = '❌'
                break
            case 'WARNING':
                color = 16776960
                emoji = '⚠️'
                break
        }
        def statusTitle = "[${status ?: 'INFO'}]"
        def utcNow = new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone('UTC'))
        powershell(
            script: """
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                [Console]::InputEncoding = [System.Text.Encoding]::UTF8
                \$webhook = '${webhookUrl.replace("'", "''")}'
                \$avatar = '${avatarUrl.replace("'", "''")}'
                \$username = '${username.replace("'", "''")}'
                \$messageContent = @'
${(message ?: '').replace("'", "''")}
'@
                \$embed = @{
                    title = '${statusTitle.replace("'", "''")}'
                    description = \$messageContent
                    color = ${color}
                    timestamp = '${utcNow}'
                }
                \$body = @{
                    username = \$username
                    avatar_url = \$avatar
                    embeds = @(\$embed)
                }
                \$jsonString = \$body | ConvertTo-Json -Depth 4 -Compress
                \$jsonBytes = [System.Text.Encoding]::UTF8.GetBytes(\$jsonString)
                try {
                    Invoke-RestMethod -Uri \$webhook -Method Post -Body \$jsonBytes -ContentType 'application/json; charset=utf-8'
                    Write-Host '✓ Discord notification sent successfully!'
                } catch {
                    Write-Host "✗ Error: \$(\$_.Exception.Message)"
                    exit 1
                }
            """,
            returnStatus: true
        )
        echo "Discord notification sent successfully"
    } catch (Exception ex) {
        echo "Failed to send Discord notification: ${ex.getMessage()}"
    }
}

def detectDeployer() {
    def deployer = 'Unknown'
    
    // FIRST: Try to get manual build user from environment (requires Build User Vars plugin)
    if (env.BUILD_USER_ID) {
        deployer = env.BUILD_USER_ID
        echo "Found BUILD_USER_ID: ${deployer}"
    } else if (env.BUILD_USER) {
        deployer = env.BUILD_USER
        echo "Found BUILD_USER: ${deployer}"
    } else {
        // If no env vars, check build causes
        def causes = currentBuild.getBuildCauses()
        
        // Process causes to determine deployer
        for (cause in causes) {
            def causeClass = cause.getClass().getName()
            
            // Handle JSONObject causes (from getBuildCauses())
            if (causeClass.contains('JSONObject')) {
                try {
                    def causeClassName = cause.get('_class') ?: ''
                    
                    if (causeClassName.contains('UserIdCause')) {
                        deployer = cause.get('userName') ?: cause.get('userId') ?: 'Manual User'
                        break
                    } else if (causeClassName.contains('GitHubPullRequestCause')) {
                        deployer = cause.get('pullRequestAuthor') ?: cause.get('author') ?: 'GitHub PR User'
                        break
                    } else if (causeClassName.contains('GitHubPushCause')) {
                        deployer = cause.get('pushedBy') ?: cause.get('commitAuthor') ?: 'GitHub Push User'
                        break
                    } else if (causeClassName.contains('BranchEventCause')) {
                        deployer = cause.get('author') ?: 'Branch Event'
                        break
                    }
                } catch (Exception jsonEx) {
                    echo "Error reading JSON cause: ${jsonEx.getMessage()}"
                }
            }
        }
    }
    
    // Fallback to environment variables if still unknown
    if (deployer == 'Unknown' || deployer in ['GitHub PR User', 'GitHub Push User', 'Branch Event']) {
        if (env.BUILD_USER_ID) {
            deployer = env.BUILD_USER_ID
        } else if (env.BUILD_USER) {
            deployer = env.BUILD_USER
        } else if (env.CHANGE_AUTHOR) {
            deployer = env.CHANGE_AUTHOR
        } else if (env.GIT_AUTHOR_NAME) {
            deployer = env.GIT_AUTHOR_NAME
        } else if (env.GIT_COMMITTER_NAME) {
            deployer = env.GIT_COMMITTER_NAME
        } else {
            // Final fallback - use current user or system
            def jenkinsUser = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
            if (jenkinsUser && jenkinsUser.size() > 0) {
                deployer = jenkinsUser[0].userId ?: jenkinsUser[0].userName ?: 'Jenkins User'
            } else {
                // If we still can't find anything, it's likely automated
                deployer = 'System'
            }
        }
    }
    
    return deployer
}

def detectTriggerType() {
    def triggerType = 'Unknown'
    
    // Check build causes
    def causes = currentBuild.getBuildCauses()
    
    // Process causes to determine trigger type
    for (cause in causes) {
        def causeClass = cause.getClass().getName()
        
        // Handle JSONObject causes (from getBuildCauses())
        if (causeClass.contains('JSONObject')) {
            try {
                def causeClassName = cause.get('_class') ?: ''
                
                if (causeClassName.contains('UserIdCause')) {
                    triggerType = 'Manual'
                    break
                } else if (causeClassName.contains('GitHubPullRequestCause')) {
                    triggerType = 'GitHub PR'
                    break
                } else if (causeClassName.contains('GitHubPushCause')) {
                    triggerType = 'GitHub Push'
                    break
                } else if (causeClassName.contains('BranchEventCause')) {
                    triggerType = 'Branch Merge'
                    break
                } else if (causeClassName.contains('SCMTrigger')) {
                    triggerType = 'SCM Polling'
                    break
                } else if (causeClassName.contains('TimerTrigger')) {
                    triggerType = 'Timer'
                    break
                } else if (causeClassName.contains('RemoteCause')) {
                    triggerType = 'Remote'
                    break
                } else if (causeClassName.contains('UpstreamCause')) {
                    triggerType = 'Upstream'
                    break
                }
            } catch (Exception jsonEx) {
                echo "Error reading JSON cause: ${jsonEx.getMessage()}"
            }
        }
    }
    
    // Fallback based on environment variables
    if (triggerType == 'Unknown') {
        if (env.BUILD_USER_ID || env.BUILD_USER) {
            triggerType = 'Manual'
        } else if (env.CHANGE_AUTHOR) {
            triggerType = 'Git Change'
        } else if (env.GIT_AUTHOR_NAME || env.GIT_COMMITTER_NAME) {
            triggerType = 'Git Commit'
        } else {
            triggerType = 'Automated'
        }
    }
    
    return triggerType
}