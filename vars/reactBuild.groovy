/**
 * React Build Utility Functions for Jenkins Pipelines
 * Handles React application building, dependency management, and artifact preparation
 * 
 * Usage:
 *   reactBuild.buildApplication()
 *   reactBuild.configureNPM()
 *   reactBuild.installDependencies()
 *   reactBuild.prepareArtifacts(buildPath)
 *   reactBuild.archiveArtifacts()
 *   reactBuild.cleanupBuildArtifacts()
 * 
 * Or as a step:
 *   reactBuild(action: 'buildApplication')
 *   reactBuild(action: 'configureNPM')
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("reactBuild: 'action' parameter is required")
    }
    
    switch(action) {
        case 'buildApplication':
            buildApplication()
            break
        case 'configureNPM':
            configureNPM()
            break
        case 'installDependencies':
            installDependencies()
            break
        case 'prepareArtifacts':
            if (!config.buildPath) {
                error("reactBuild: 'buildPath' parameter is required for prepareArtifacts")
            }
            prepareArtifacts(config.buildPath)
            break
        case 'archiveArtifacts':
            archiveArtifacts()
            break
        case 'cleanupBuildArtifacts':
            cleanupBuildArtifacts()
            break
        case 'cleanupBuildOutput':
            cleanupBuildOutput()
            break
        default:
            error("reactBuild: Unknown action '${action}'. Valid actions: buildApplication, configureNPM, installDependencies, prepareArtifacts, archiveArtifacts, cleanupBuildArtifacts, cleanupBuildOutput")
    }
}

def configureNPM() {
    logging.logSubSection("NPM Configuration")
    def cachePath = env.NPM_CACHE_PATH ?: "C:\\npm-cache"
    powershell """
        # Configure npm for performance
        npm config set audit false
        npm config set fund false
        npm config set prefer-offline true
        npm config set progress false
        npm config set loglevel warn
        
        \$cacheDir = "${cachePath.replace('\\', '\\\\')}"
        if (-not (Test-Path \$cacheDir)) {
            New-Item -ItemType Directory -Path \$cacheDir -Force | Out-Null
            Write-Host "[INFO] NPM cache directory created: \$cacheDir" -ForegroundColor Cyan
        }
        npm config set cache \$cacheDir
        
        Write-Host "[SUCCESS] NPM optimizations configured" -ForegroundColor Green
    """
}

def installDependencies() {
    logging.logSubSection("Dependency Management")
    def needsInstall = powershell(
        script: '''
            if (-not (Test-Path "node_modules")) {
                Write-Output "INSTALL_NEEDED"
                exit 0
            }
            
            if (-not (Test-Path "package-lock.json")) {
                Write-Output "INSTALL_NEEDED"
                exit 0
            }
            
            $lockModified = (Get-Item "package-lock.json").LastWriteTime
            $nodeModulesModified = (Get-Item "node_modules").LastWriteTime
            
            if ($lockModified -gt $nodeModulesModified) {
                Write-Output "INSTALL_NEEDED"
            } else {
                try {
                    $npmCheck = npm ls --depth=0 --silent 2>$null
                    if ($LASTEXITCODE -eq 0) {
                        Write-Output "INSTALL_SKIP"
                    } else {
                        Write-Output "INSTALL_NEEDED"
                    }
                } catch {
                    Write-Output "INSTALL_NEEDED"
                }
            }
        ''',
        returnStdout: true
    ).trim()

    if (needsInstall == "INSTALL_NEEDED") {
        def installStartTime = System.currentTimeMillis()
        
        logging.logInfo("Dependencies", "Installing with npm ci...")
        powershell '''
            npm ci --prefer-offline --no-audit --no-fund --silent
            
            if ($LASTEXITCODE -ne 0) {
                Write-Host "[WARNING] npm ci failed, falling back to npm install..." -ForegroundColor Yellow
                npm install --prefer-offline --no-audit --no-fund --silent
                
                if ($LASTEXITCODE -ne 0) {
                    Write-Host "[ERROR] npm install failed!" -ForegroundColor Red
                    exit 1
                }
            }
            
            Write-Host "[SUCCESS] Dependencies installed successfully" -ForegroundColor Green
        '''
        
        def installDuration = (System.currentTimeMillis() - installStartTime) / 1000
        logging.logSuccess("Dependencies installed in ${installDuration} seconds")
    } else {
        logging.logInfo("Dependencies", "Up to date, skipping installation")
    }
}

def buildApplication() {
    logging.logSubSection("Application Build")
    def buildStartTime = System.currentTimeMillis()
    
    logging.logInfo("Build command", env.BUILD_COMMAND)
    powershell "${env.BUILD_COMMAND}"
    
    def buildDuration = (System.currentTimeMillis() - buildStartTime) / 1000
    logging.logSuccess("Build completed in ${buildDuration} seconds")
}

def prepareArtifacts(def buildPath) {
    // Get the build output directory
    def builtFilePath = powershell(
        returnStdout: true,
        script: """
            if (-not (Test-Path "${env.BUILD_PATH}")) {
                Write-Host "[ERROR] Build path directory not found: ${env.BUILD_PATH}" -ForegroundColor Red
                exit 1
            }

            # Check if this is a React build root (look for index.html)
            if (Test-Path (Join-Path "${env.BUILD_PATH}" "index.html")) {
                Write-Output "${env.BUILD_PATH}"
                exit 0
            }
            
            \$buildDirs = Get-ChildItem -Path "${env.BUILD_PATH}" -Directory | Sort-Object CreationTime -Descending
            
            if (\$buildDirs.Count -eq 0) {
                # If no subdirectories, use the build path itself
                Write-Output "${env.BUILD_PATH}"
            } else {
                \$latestDir = \$buildDirs[0]
                \$builtPath = Join-Path "${env.BUILD_PATH}" \$latestDir.Name
                
                if (-not (Test-Path \$builtPath)) {
                    Write-Host "[ERROR] Latest directory path does not exist: \$builtPath" -ForegroundColor Red
                    exit 1
                }
                
                Write-Output \$builtPath
            }
        """
    ).trim()

    logging.logSubSection("Built path: ${env.BUILD_PATH}")

    // Artifact Preparation
    logging.logSubSection("Artifact Preparation")

    powershell """
        \$builtFilePath = "${builtFilePath}"
        \$buildPath = "${buildPath}"
        
        Write-Host "[INFO] Source path: \$builtFilePath" -ForegroundColor Cyan
        Write-Host "[INFO] Destination path: \$buildPath" -ForegroundColor Cyan
        
        if (Test-Path \$builtFilePath) {
            # Ensure destination directory exists
            if (-not (Test-Path \$buildPath)) {
                Write-Host "[INFO] Creating build directory: \$buildPath" -ForegroundColor Yellow
                New-Item -ItemType Directory -Path \$buildPath -Force | Out-Null
            }
            
            # Copy files
            try {
                Copy-Item -Path "\$builtFilePath\\*" -Destination \$buildPath -Recurse -Force -ErrorAction Stop
                
                # Count and report files
                \$buildFiles = Get-ChildItem -Path \$buildPath -File -Recurse
                Write-Host "[SUCCESS] Build artifacts prepared: \$(\$buildFiles.Count) files" -ForegroundColor Green
                Write-Host "[INFO] Artifact location: \$buildPath" -ForegroundColor Cyan
                
            } catch {
                Write-Host "[ERROR] Failed to copy files: \$(\$_.Exception.Message)" -ForegroundColor Red
                exit 1
            }
        } else {
            Write-Host "[ERROR] Build output not found at: \$builtFilePath" -ForegroundColor Red
            exit 1
        }
    """
}

def archiveArtifacts() {
    logging.logSubSection("Artifact Archiving")
    
    archiveArtifacts artifacts: "${env.PUBLISH_PATH}/**/*", fingerprint: true
    def stashName = env.ARTIFACT_NAME ?: "${env.PROJECT_NAME}-build-artifacts"
    stash name: stashName, includes: "${env.PUBLISH_PATH}/**/*"
    
    logging.logSuccess("Build artifacts archived and ready for transfer")
}

def cleanupBuildArtifacts() {
    logging.logError("Build stage failed - cleaning up partial builds")
}

def cleanupBuildOutput() {
    logging.logSubSection("Cleaning Build Output")
    powershell """
        # Clean build output directory
        if (Test-Path "${env.BUILD_PATH}") {
            Write-Host "[INFO] Cleaning build directory: ${env.BUILD_PATH}" -ForegroundColor Yellow
            Remove-Item -Path "${env.BUILD_PATH}" -Recurse -Force -ErrorAction SilentlyContinue
        }
        
        # Clean publish directory
        if (Test-Path "${env.PUBLISH_PATH}") {
            Write-Host "[INFO] Cleaning publish directory: ${env.PUBLISH_PATH}" -ForegroundColor Yellow
            Remove-Item -Path "${env.PUBLISH_PATH}" -Recurse -Force -ErrorAction SilentlyContinue
        }
        
        Write-Host "[SUCCESS] Build output cleanup completed" -ForegroundColor Green
    """
}

