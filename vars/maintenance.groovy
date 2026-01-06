/**
 * Maintenance Mode Utility Functions for Jenkins Pipelines
 * Handles IIS maintenance mode operations directly (no pipeline-to-pipeline calls)
 * 
 * Usage:
 *   maintenance.validateSite(siteName)
 *   maintenance.setupEnvironment(sitePath, maintenancePath)
 *   maintenance.enable(siteName, sitePath, maintenancePath)
 *   maintenance.disable(siteName, sitePath, maintenancePath)
 *   maintenance.checkStatus(siteName, sitePath, maintenancePath)
 * 
 * Or as a step:
 *   maintenance(action: 'enable', siteName: 'mysite', sitePath: 'D:\\App', maintenancePath: 'D:\\Maintenance')
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("maintenance: 'action' parameter is required")
    }
    
    switch(action) {
        case 'validateSite':
            if (!config.siteName) {
                error("maintenance: 'siteName' parameter is required for validateSite")
            }
            validateSite(config.siteName)
            break
        case 'setupEnvironment':
            if (!config.sitePath || !config.maintenancePath) {
                error("maintenance: 'sitePath' and 'maintenancePath' parameters are required for setupEnvironment")
            }
            setupEnvironment(config.sitePath, config.maintenancePath)
            break
        case 'enable':
            if (!config.siteName || !config.sitePath || !config.maintenancePath) {
                error("maintenance: 'siteName', 'sitePath', and 'maintenancePath' parameters are required for enable")
            }
            enable(config.siteName, config.sitePath, config.maintenancePath)
            break
        case 'disable':
            if (!config.siteName || !config.sitePath || !config.maintenancePath) {
                error("maintenance: 'siteName', 'sitePath', and 'maintenancePath' parameters are required for disable")
            }
            disable(config.siteName, config.sitePath, config.maintenancePath)
            break
        case 'checkStatus':
            if (!config.siteName || !config.sitePath || !config.maintenancePath) {
                error("maintenance: 'siteName', 'sitePath', and 'maintenancePath' parameters are required for checkStatus")
            }
            checkStatus(config.siteName, config.sitePath, config.maintenancePath)
            break
        default:
            error("maintenance: Unknown action '${action}'. Valid actions: validateSite, setupEnvironment, enable, disable, checkStatus")
    }
}

def validateSite(String siteName) {
    if (isUnix()) {
        logging.logWarning("Running on Unix/Linux agent - IIS operations may not be available")
        return
    }
    
    logging.logSubSection("Validating IIS Site")
    logging.logInfo("Site Name", siteName)
    
    def siteCheck = powershell(
        script: """
            Import-Module WebAdministration -ErrorAction SilentlyContinue
            try {
                \$site = Get-Website -Name "${siteName}" -ErrorAction Stop
                Write-Output "SITE_EXISTS"
            } catch {
                Write-Output "SITE_NOT_FOUND"
            }
        """,
        returnStdout: true
    ).trim()
    
    if (siteCheck.contains('SITE_NOT_FOUND')) {
        logging.logError("IIS site '${siteName}' not found!")
        error("IIS site '${siteName}' not found!")
    }
    
    logging.logSuccess("Site validation passed")
}

def setupEnvironment(String sitePath, String maintenancePath) {
    if (isUnix()) {
        logging.logWarning("Skipping Windows IIS setup on Unix/Linux agent")
        return
    }
    
    logging.logSubSection("Setting Up Maintenance Environment")
    logging.logInfo("Site Path", sitePath)
    logging.logInfo("Maintenance Path", maintenancePath)
    
    powershell """
        # Create maintenance directory if it doesn't exist
        if (!(Test-Path "${maintenancePath}")) {
            New-Item -ItemType Directory -Path "${maintenancePath}" -Force | Out-Null
            Write-Host "[SUCCESS] Created maintenance directory" -ForegroundColor Green
        }
        
        # Create maintenance page if it doesn't exist
        \$indexPath = "${maintenancePath}\\index.html"
        if (!(Test-Path \$indexPath)) {
            Write-Host "[INFO] Creating maintenance page..." -ForegroundColor Cyan
            
            \$maintenanceContent = @"
<!DOCTYPE html>
<html lang="th">

<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Maintenance</title>
    <!-- Bootstrap CSS from the provided URL -->
    <link rel="stylesheet" href="https://oauthlogin.siamsmile.co.th/dist/css/bundle.min.css">
    <!-- Google Fonts - Sarabun -->
    <link href="https://fonts.googleapis.com/css2?family=Sarabun:wght@400;700&display=swap" rel="stylesheet">
    <!-- Custom styles for responsive behavior and CI colors -->
    <style>
        html,
        body {
            height: 100%;
            margin: 0;
            font-family: 'Sarabun', sans-serif;
            color: #000000;
        }

        body {
            background-image: url('https://oauthlogin.siamsmile.co.th/images/login_background.jpg');
            background-size: cover;
            background-position: center;
            background-repeat: no-repeat;
            display: flex;
            align-items: center;
            justify-content: center;
            background-color: #f8f9fa;
        }

        .content-box {
            background-color: transparent;
            padding: 1.5rem;
            border-radius: 0.5rem;
            text-align: center;
        }

        @media (min-width: 992px) {
            .content-box {
                max-width: 450px;
                margin-left: auto;
                margin-right: 5%;
            }
        }

        @media (max-width: 767.98px) {
            body {
                background-image: none;
                background-color: #f8f9fa;
            }

            .content-box {
                background-color: transparent;
                color: #000000;
                width: 90%;
                max-width: 400px;
                margin-left: auto;
                margin-right: auto;
            }
        }

        .img-max-h-40 {
            max-height: 160px;
        }

        .content-box h2 {
            color: #007AC1;
        }
    </style>
</head>

<body>
    <div class="container">
        <div class="row justify-content-center">
            <div class="col-12 col-md-8 col-lg-5 ml-lg-auto content-box">
                <img src="https://oauthlogin.siamsmile.co.th/images/logo.png" alt=""
                    class="img-fluid mx-auto mb-4 img-max-h-40 rounded">

                <h2 class="font-weight-bold mb-3">
                    ขออภัยในความไม่สะดวก
                </h2>

                <p class="lead mb-4">
                    เว็บไซต์ของเรากำลังอยู่ระหว่างการปรับปรุงและบำรุงรักษาเพื่อประสิทธิภาพที่ดีขึ้น
                    เราจะกลับมาให้บริการอีกครั้งในไม่ช้า
                </p>

                <p>
                    โปรดลองใหม่อีกครั้งในภายหลัง
                </p>
            </div>
        </div>
    </div>
</body>

</html>
"@
            \$maintenanceContent | Out-File -FilePath \$indexPath -Encoding UTF8 -Force
            Write-Host "[SUCCESS] Maintenance page created" -ForegroundColor Green
        }
        
        # Copy web.config from app to maintenance folder if needed
        \$appWebConfig = "${sitePath}\\web.config"
        \$maintenanceWebConfig = "${maintenancePath}\\web.config"
        
        if ((Test-Path \$appWebConfig) -and !(Test-Path \$maintenanceWebConfig)) {
            Copy-Item \$appWebConfig \$maintenanceWebConfig -Force
            Write-Host "[SUCCESS] Copied web.config to maintenance folder" -ForegroundColor Green
        }
        
        Write-Host "[SUCCESS] Maintenance environment ready" -ForegroundColor Green
    """
    
    logging.logSuccess("Maintenance environment setup completed")
}

def enable(String siteName, String sitePath, String maintenancePath) {
    if (isUnix()) {
        logging.logWarning("Cannot enable maintenance mode on Unix/Linux agent - IIS not available")
        return
    }
    
    logging.logSubSection("Enabling Maintenance Mode")
    logging.logInfo("Site Name", siteName)
    logging.logInfo("Switching to", maintenancePath)
    
    // First setup environment if needed
    setupEnvironment(sitePath, maintenancePath)
    
    powershell """
        Import-Module WebAdministration -ErrorAction Stop
        
        try {
            # Switch IIS site to maintenance folder
            Write-Host "[INFO] Switching site to maintenance path: ${maintenancePath}" -ForegroundColor Cyan
            Set-ItemProperty -Path "IIS:\\Sites\\${siteName}" -Name "PhysicalPath" -Value "${maintenancePath}"
            
            # Get application pool name
            \$appPool = (Get-Website -Name "${siteName}").ApplicationPool
            Write-Host "[INFO] Recycling application pool: \$appPool" -ForegroundColor Cyan
            
            # Recycle application pool
            Restart-WebAppPool -Name \$appPool -ErrorAction SilentlyContinue
            
            # Wait for changes to take effect
            Start-Sleep -Seconds 3
            
            Write-Host "[SUCCESS] MAINTENANCE MODE ENABLED" -ForegroundColor Green
            Write-Host "[INFO] Site '${siteName}' now serves maintenance page from: ${maintenancePath}" -ForegroundColor Cyan
            
        } catch {
            Write-Host "[ERROR] Failed to enable maintenance mode: \$(\$_.Exception.Message)" -ForegroundColor Red
            exit 1
        }
    """
    
    logging.logSuccess("Maintenance mode enabled successfully")
}

def disable(String siteName, String sitePath, String maintenancePath) {
    if (isUnix()) {
        logging.logWarning("Cannot disable maintenance mode on Unix/Linux agent - IIS not available")
        return
    }
    
    logging.logSubSection("Disabling Maintenance Mode")
    logging.logInfo("Site Name", siteName)
    logging.logInfo("Switching to", sitePath)
    
    powershell """
        Import-Module WebAdministration -ErrorAction Stop
        
        try {
            # Switch IIS site back to application folder
            Write-Host "[INFO] Switching site back to application path: ${sitePath}" -ForegroundColor Cyan
            Set-ItemProperty -Path "IIS:\\Sites\\${siteName}" -Name "PhysicalPath" -Value "${sitePath}"
            
            # Get application pool name
            \$appPool = (Get-Website -Name "${siteName}").ApplicationPool
            Write-Host "[INFO] Recycling application pool: \$appPool" -ForegroundColor Cyan
            
            # Recycle application pool
            Restart-WebAppPool -Name \$appPool -ErrorAction SilentlyContinue
            
            # Wait for changes to take effect
            Start-Sleep -Seconds 3
            
            Write-Host "[SUCCESS] MAINTENANCE MODE DISABLED" -ForegroundColor Green
            Write-Host "[INFO] Site '${siteName}' now serves application from: ${sitePath}" -ForegroundColor Cyan
            
        } catch {
            Write-Host "[ERROR] Failed to disable maintenance mode: \$(\$_.Exception.Message)" -ForegroundColor Red
            exit 1
        }
    """
    
    logging.logSuccess("Maintenance mode disabled successfully")
}

def checkStatus(String siteName, String sitePath, String maintenancePath) {
    if (isUnix()) {
        logging.logWarning("Status check limited on Unix/Linux agent - IIS not available")
        return
    }
    
    logging.logSubSection("Checking Maintenance Status")
    
    powershell """
        Import-Module WebAdministration -ErrorAction SilentlyContinue
        
        try {
            # Get current physical path
            \$site = Get-Website -Name "${siteName}" -ErrorAction Stop
            \$currentPath = \$site.PhysicalPath
            \$siteState = \$site.State
            \$appPool = \$site.ApplicationPool
            \$appPoolState = (Get-WebAppPoolState -Name \$appPool).Value
            
            Write-Host "=== MAINTENANCE STATUS ===" -ForegroundColor Cyan
            Write-Host "Site Name: ${siteName}" -ForegroundColor White
            Write-Host "Site State: \$siteState" -ForegroundColor White
            Write-Host "App Pool: \$appPool (\$appPoolState)" -ForegroundColor White
            Write-Host "Current Path: \$currentPath" -ForegroundColor White
            Write-Host "App Path: ${sitePath}" -ForegroundColor White
            Write-Host "Maintenance Path: ${maintenancePath}" -ForegroundColor White
            Write-Host ""
            
            if (\$currentPath -eq "${maintenancePath}") {
                Write-Host "🟡 MAINTENANCE MODE IS ENABLED" -ForegroundColor Yellow
                Write-Host "✓ Site is currently showing maintenance page" -ForegroundColor Green
            } elseif (\$currentPath -eq "${sitePath}") {
                Write-Host "🟢 MAINTENANCE MODE IS DISABLED" -ForegroundColor Green
                Write-Host "✓ Site is currently showing normal application" -ForegroundColor Green
            } else {
                Write-Host "🟠 UNKNOWN STATE" -ForegroundColor Yellow
                Write-Host "⚠ Site is pointing to unexpected path: \$currentPath" -ForegroundColor Yellow
            }
            
            Write-Host ""
            Write-Host "=== FOLDER STATUS ===" -ForegroundColor Cyan
            Write-Host "✓ Application folder exists: \$(Test-Path '${sitePath}')" -ForegroundColor White
            Write-Host "✓ Maintenance folder exists: \$(Test-Path '${maintenancePath}')" -ForegroundColor White
            Write-Host "✓ Maintenance page exists: \$(Test-Path '${maintenancePath}\\index.html')" -ForegroundColor White
            
        } catch {
            Write-Host "[ERROR] Failed to check status: \$(\$_.Exception.Message)" -ForegroundColor Red
        }
    """
}

