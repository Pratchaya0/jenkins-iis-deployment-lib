/**
 * Deployment Utility Functions for Jenkins Pipelines
 * Common deployment operations for .NET and React applications
 * 
 * Usage:
 *   deployment.validateEnvironment()
 *   deployment.validateProjectConfig(project)
 *   deployment.transferArtifacts()
 *   deployment.manageIISService(project, 'stop')
 *   deployment.createBackup(project)
 *   deployment.cleanupBackups(project)
 * 
 * Or as a step:
 *   deployment(action: 'validateEnvironment')
 *   deployment(action: 'validateProjectConfig', project: project)
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("deployment: 'action' parameter is required")
    }
    
    switch(action) {
        case 'validateEnvironment':
            validateEnvironment()
            break
        case 'validateProjectConfig':
            if (!config.project) {
                error("deployment: 'project' parameter is required for validateProjectConfig")
            }
            validateProjectConfig(config.project)
            break
        case 'transferArtifacts':
            transferArtifacts()
            break
        case 'manageIISService':
            if (!config.project || !config.serviceAction) {
                error("deployment: 'project' and 'serviceAction' parameters are required for manageIISService")
            }
            manageIISService(config.project, config.serviceAction)
            break
        case 'createBackup':
            if (!config.project) {
                error("deployment: 'project' parameter is required for createBackup")
            }
            createBackup(config.project)
            break
        case 'cleanupBackups':
            if (!config.project) {
                error("deployment: 'project' parameter is required for cleanupBackups")
            }
            cleanupBackups(config.project)
            break
        default:
            error("deployment: Unknown action '${action}'. Valid actions: validateEnvironment, validateProjectConfig, transferArtifacts, manageIISService, createBackup, cleanupBackups")
    }
}

def validateEnvironment() {
    def requiredSettings = [
        "PROJECT_NAME", "BUILD_AGENT_LABEL", "DEPLOY_AGENT_LABEL",
        "BASE_WEBSITE_PATH", "BASE_BACKUPS_PATH", "CONFIGURATION", "PUBLISH_PATH",
        "GITHUB_BRANCH_NAME", "GITHUB_TOKEN_CREDENTIAL_ID", "CONFIG_FILE_ID",
        "ENVIRONMENT", "DEPLOYMENT_ENVIRONMENT", "ENVIRONMENT_URL", "ARTIFACT_NAME"
    ]

    powershell """
        \$RequiredSettings = @(${requiredSettings.collect { "\"${it}\"" }.join(', ')})

        \$MissingSettings = @()
        foreach (\$EnvVar in \$RequiredSettings) {
            \$EnvVarValue = [System.Environment]::GetEnvironmentVariable(\$EnvVar)
            if ([string]::IsNullOrEmpty(\$EnvVarValue)) {
                \$MissingSettings += \$EnvVar
            }
        }

        if (\$MissingSettings.Count -gt 0) {
            Write-Host "[ERROR] Missing required environment variables:" -ForegroundColor Red
            \$MissingSettings | ForEach-Object { Write-Host "  - \$_" -ForegroundColor Red }
            exit 1
        } else {
            Write-Host "[SUCCESS] All environment variables validated" -ForegroundColor Green
        }
    """
}

def validateProjectConfig(def project) {
    logging.logSubSection("Project Configuration Validation")
    logging.logInfo("Processing project", env.PROJECT_NAME)
    
    // Detect project type based on available attributes
    def isDotNetProject = project.dotnet_version != null
    def isReactProject = project.node_version != null
    
    def requiredAttributes = []
    
    if (isDotNetProject) {
        requiredAttributes = [
            "project_name", "folder_website_name", "dotnet_version",
            "is_run_build", "is_run_test", "start_iis", "stop_iis",
            "start_app_pool", "stop_app_pool", "is_cleanup"
        ]
        logging.logInfo("Project type", ".NET Application")
    } else if (isReactProject) {
        requiredAttributes = [
            "node_version", "iis_website_name", "folder_website_name",
            "start_iis", "stop_iis", "start_app_pool", "stop_app_pool", "is_cleanup"
        ]
        logging.logInfo("Project type", "React Application")
    } else {
        // Fallback to common attributes
        requiredAttributes = [
            "folder_website_name", "start_iis", "stop_iis",
            "start_app_pool", "stop_app_pool", "is_cleanup"
        ]
        logging.logInfo("Project type", "Generic Web Application")
    }

    def validationErrors = []
    requiredAttributes.each { attribute ->
        def value = project."${attribute}"
        if (value == null || value.toString().trim().isEmpty()) {
            validationErrors.add(attribute)
        }
    }

    if (validationErrors.size() > 0) {
        logging.logError("Missing required attributes: ${validationErrors.join(', ')}")
        github.updateGitHubStatus('error', 'Configuration Error', "Missing required attributes for project")
        error("Missing required attributes for project: ${validationErrors.join(', ')}")
    }

    // Validate IIS configuration
    if ((project.start_app_pool || project.stop_app_pool) && 
        (project.app_pool_name == null || project.app_pool_name.toString().trim().isEmpty())) {
        logging.logError("App pool management enabled but app_pool_name is missing")
        github.updateGitHubStatus('error', 'Configuration Error', "Missing app_pool_name configuration")
        error("App pool management enabled but app_pool_name is not defined")
    }

    if ((project.start_iis || project.stop_iis) && 
        (project.iis_website_name == null || project.iis_website_name.toString().trim().isEmpty())) {
        logging.logError("IIS management enabled but iis_website_name is missing")
        github.updateGitHubStatus('error', 'Configuration Error', "Missing iis_website_name configuration")
        error("IIS management enabled but iis_website_name is not defined")
    }
    
    // Validate cleanup configuration
    if (project.is_cleanup) {
        def retention = project.backup_file_retention_months
        def deleteCount = project.amount_files_delete
        if ((retention == null || retention.toString().trim().isEmpty()) && 
            (deleteCount == null || deleteCount.toString().trim().isEmpty())) {
            logging.logError("Cleanup enabled but retention configuration is missing")
            github.updateGitHubStatus('error', 'Configuration Error', "Missing cleanup configuration")
            error("Cleanup enabled but retention configuration is missing")
        }
    }
    
    logging.logSuccess("Project configuration validated successfully")
}

def transferArtifacts() {
    def artifactsTransferred = false
    
    try {
        logging.logInfo("Transfer method", "copyArtifacts")
        copyArtifacts(
            projectName: env.JOB_NAME,
            selector: specific(env.BUILD_NUMBER),
            filter: "${env.PUBLISH_PATH}/**/*",
            target: ".",
            flatten: false,
            fingerprintArtifacts: true
        )
        artifactsTransferred = true
        logging.logSuccess("Artifacts transferred successfully via copyArtifacts")
        
    } catch (Exception e) {
        logging.logWarning("copyArtifacts failed, trying fallback method")
        
        try {
            // Try common stash names
            def stashNames = [
                'multi-server-build-artifacts',      // .NET projects
                'multi-server-react-build-artifacts' // React projects
            ]
            
            def stashFound = false
            for (stashName in stashNames) {
                try {
                    unstash stashName
                    stashFound = true
                    logging.logSuccess("Artifacts transferred successfully via unstash: ${stashName}")
                    break
                } catch (Exception unstashError) {
                    // Continue trying other stash names
                }
            }
            
            if (!stashFound) {
                throw new Exception("No valid stash found")
            }
            
            artifactsTransferred = true
            
        } catch (Exception e2) {
            logging.logError("Both transfer methods failed")
            github.updateGitHubStatus('failure', 'Transfer Failed', 'Failed to transfer build artifacts')
            error("Failed to transfer build artifacts from build server")
        }
    }
    
    if (artifactsTransferred) {
        logging.logSubSection("Artifact Verification")
        powershell """
            if (Test-Path "${env.PUBLISH_PATH}") {
                \$files = Get-ChildItem -Path "${env.PUBLISH_PATH}" -File -Recurse
                Write-Host "[SUCCESS] Verified \$(\$files.Count) transferred files" -ForegroundColor Green
            } else {
                Write-Host "[ERROR] Artifact directory not found!" -ForegroundColor Red
                exit 1
            }
        """
    }
    
    return artifactsTransferred
}

def manageIISService(def project, String action) {
    if (action == "stop") {
        if (project.stop_app_pool && project.app_pool_name) {
            logging.logSubSection("Stopping Application Pool")
            logging.logInfo("App Pool", project.app_pool_name)
            
            powershell """
                Import-Module WebAdministration -ErrorAction Stop
                
                try {
                    \$appPool = Get-WebAppPoolState -Name '${project.app_pool_name}' -ErrorAction SilentlyContinue
                    if (\$appPool -and \$appPool.Value -eq 'Started') {
                        Stop-WebAppPool -Name '${project.app_pool_name}'
                        
                        \$timeout = 0
                        do {
                            Start-Sleep -Seconds 2
                            \$state = Get-WebAppPoolState -Name '${project.app_pool_name}'
                            \$timeout += 2
                        } while (\$state.Value -ne 'Stopped' -and \$timeout -lt 30)
                        
                        Write-Host "[SUCCESS] App Pool stopped: ${project.app_pool_name}" -ForegroundColor Green
                    } else {
                        Write-Host "[INFO] App Pool already stopped: ${project.app_pool_name}" -ForegroundColor Cyan
                    }
                } catch {
                    Write-Host "[ERROR] Failed to stop App Pool: \$(\$_.Exception.Message)" -ForegroundColor Red
                    exit 1
                }
            """
        }
        
        if (project.stop_iis && project.iis_website_name) {
            logging.logSubSection("Stopping IIS Website")
            logging.logInfo("Website", project.iis_website_name)
            
            powershell """
                Import-Module WebAdministration -ErrorAction Stop
                
                try {
                    \$website = Get-WebsiteState -Name '${project.iis_website_name}' -ErrorAction SilentlyContinue
                    if (\$website -and \$website.Value -eq 'Started') {
                        Stop-Website -Name '${project.iis_website_name}'
                        
                        \$timeout = 0
                        do {
                            Start-Sleep -Seconds 2
                            \$state = Get-WebsiteState -Name '${project.iis_website_name}'
                            \$timeout += 2
                        } while (\$state.Value -ne 'Stopped' -and \$timeout -lt 30)
                        
                        Write-Host "[SUCCESS] Website stopped: ${project.iis_website_name}" -ForegroundColor Green
                    } else {
                        Write-Host "[INFO] Website already stopped: ${project.iis_website_name}" -ForegroundColor Cyan
                    }
                } catch {
                    Write-Host "[ERROR] Failed to stop Website: \$(\$_.Exception.Message)" -ForegroundColor Red
                    exit 1
                }
            """
        }
    } else if (action == "start") {
        if (project.start_app_pool && project.app_pool_name) {
            logging.logSubSection("Starting Application Pool")
            logging.logInfo("App Pool", project.app_pool_name)
            
            powershell """
                Import-Module WebAdministration -ErrorAction Stop
                
                try {
                    \$appPool = Get-WebAppPoolState -Name '${project.app_pool_name}' -ErrorAction SilentlyContinue
                    if (\$appPool -and \$appPool.Value -eq 'Stopped') {
                        Start-WebAppPool -Name '${project.app_pool_name}'
                        Write-Host "[SUCCESS] App Pool started: ${project.app_pool_name}" -ForegroundColor Green
                    } else {
                        Write-Host "[INFO] App Pool already running: ${project.app_pool_name}" -ForegroundColor Cyan
                    }
                } catch {
                    Write-Host "[ERROR] Failed to start App Pool: \$(\$_.Exception.Message)" -ForegroundColor Red
                    exit 1
                }
            """
        }
        
        if (project.start_iis && project.iis_website_name) {
            logging.logSubSection("Starting IIS Website")
            logging.logInfo("Website", project.iis_website_name)
            
            powershell """
                Import-Module WebAdministration -ErrorAction Stop
                
                try {
                    \$website = Get-WebsiteState -Name '${project.iis_website_name}' -ErrorAction SilentlyContinue
                    if (\$website -and \$website.Value -eq 'Stopped') {
                        Start-Website -Name '${project.iis_website_name}'
                        Write-Host "[SUCCESS] Website started: ${project.iis_website_name}" -ForegroundColor Green
                    } else {
                        Write-Host "[INFO] Website already running: ${project.iis_website_name}" -ForegroundColor Cyan
                    }
                } catch {
                    Write-Host "[ERROR] Failed to start Website: \$(\$_.Exception.Message)" -ForegroundColor Red
                    exit 1
                }
            """
        }
    }
}

def createBackup(def project) {
    def websitePath = "${env.BASE_WEBSITE_PATH}\\${project.folder_website_name}"
    def backupsPath = "${env.BASE_BACKUPS_PATH}\\${project.folder_website_name}"
    def backupPath = "${backupsPath}\\${env.BUILD_NUMBER}_${project.iis_website_name}_${env.ENVIRONMENT}_${new Date().format('yyyy-MM-dd_HHmmss')}"
    
    logging.logSubSection("Creating Backup")
    logging.logInfo("Source", websitePath)
    logging.logInfo("Backup", backupPath)
    
    powershell """
        # Create backup directory if it doesn't exist
        if (-not (Test-Path '${backupsPath}')) {
            New-Item -ItemType Directory -Path '${backupsPath}' -Force | Out-Null
        }
        
        \$Files = Get-ChildItem -Path '${websitePath}' -File -Recurse -ErrorAction SilentlyContinue
        
        if (\$Files.Count -gt 0) {
            New-Item -ItemType Directory -Path '${backupPath}' -Force | Out-Null
            Copy-Item -Path '${websitePath}\\*' -Destination '${backupPath}' -Recurse -Force
            Write-Host "[SUCCESS] Backup created with \$(\$Files.Count) files" -ForegroundColor Green
            Write-Host "[INFO] Backup location: ${backupPath}" -ForegroundColor Cyan
        } else {
            Write-Host "[INFO] No existing files to backup" -ForegroundColor Cyan
        }
    """
    
    return backupPath
}

def cleanupBackups(def project) {
    if (!project.is_cleanup) {
        logging.logInfo("Cleanup", "Skipped (not enabled for this project)")
        return
    }
    
    logging.logSubSection("Backup Cleanup")
    logging.logInfo("Project", env.PROJECT_NAME)
    
    def deletePath = "${env.BASE_BACKUPS_PATH}\\${project.folder_website_name}"
    def retentionMonths = project.backup_file_retention_months ?: 0
    def maxFilesToKeep = project.amount_files_delete ?: 3  // Default to keep 3 latest builds
    
    // Always use "keep last N builds" strategy for better control
    logging.logInfo("Cleanup strategy", "Keep newest ${maxFilesToKeep} backup(s)")
    powershell """
        if (Test-Path '${deletePath}') {
            \$folders = Get-ChildItem -Path '${deletePath}' -Directory | 
                        Sort-Object CreationTime -Descending
            
            if (\$folders.Count -gt ${maxFilesToKeep}) {
                \$foldersToDelete = \$folders | Select-Object -Skip ${maxFilesToKeep}
                Write-Host "[INFO] Found \$(\$folders.Count) backups, keeping newest ${maxFilesToKeep}, removing \$(\$foldersToDelete.Count)" -ForegroundColor Cyan
                
                foreach (\$folder in \$foldersToDelete) {
                    \$backupDate = \$folder.CreationTime.ToString('yyyy-MM-dd HH:mm')
                    Write-Host "[INFO] Removing old backup: \$(\$folder.Name) (created: \$backupDate)" -ForegroundColor Yellow
                    Remove-Item -Path \$folder.FullName -Recurse -Force
                }
                Write-Host "[SUCCESS] Cleaned up \$(\$foldersToDelete.Count) old backup(s)" -ForegroundColor Green
            } else {
                Write-Host "[INFO] No cleanup needed (\$(\$folders.Count) backups found, keeping all)" -ForegroundColor Cyan
            }
        } else {
            Write-Host "[INFO] No backup directory found: ${deletePath}" -ForegroundColor Cyan
        }
    """
    logging.logSuccess("Backup cleanup completed")
}
