/**
 * .NET Build Utility Functions for Jenkins Pipelines
 * Handles .NET application building, testing, and artifact preparation
 * 
 * Usage:
 *   dotnetBuild.buildApplication(projectName)
 *   dotnetBuild.runUnitTests(testProjectName)
 *   dotnetBuild.archiveArtifacts()
 * 
 * Or as a step:
 *   dotnetBuild(action: 'buildApplication', projectName: 'MyApp')
 *   dotnetBuild(action: 'runUnitTests', testProjectName: 'MyApp.Tests')
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("dotnetBuild: 'action' parameter is required")
    }
    
    switch(action) {
        case 'buildApplication':
            if (!config.projectName) {
                error("dotnetBuild: 'projectName' parameter is required for buildApplication")
            }
            buildApplication(config.projectName)
            break
        case 'runUnitTests':
            if (!config.testProjectName) {
                error("dotnetBuild: 'testProjectName' parameter is required for runUnitTests")
            }
            runUnitTests(config.testProjectName)
            break
        case 'archiveArtifacts':
            archiveArtifacts()
            break
        case 'cleanupBuildOutput':
            cleanupBuildOutput()
            break
        default:
            error("dotnetBuild: Unknown action '${action}'. Valid actions: buildApplication, runUnitTests, archiveArtifacts")
    }
}

def buildApplication(String projectName) {
    logging.logSection("APPLICATION BUILD PROCESS")
    
    logging.logInfo("Building project", projectName)
    logging.logInfo(".NET version", env.DOTNET_VERSION)
    logging.logInfo("Configuration", env.CONFIGURATION)
    
    try {
        def buildContext = env.DOTNET_BUILD_CONTEXT ?: projectName
        def buildOutputSubpath = env.DOTNET_BUILD_OUTPUT_SUBPATH ?: "${env.PUBLISH_PATH}\\${env.PROJECT_NAME}"
        def buildPath = "${env.WORKSPACE}\\${buildOutputSubpath}"

        dir(buildContext) {
        
            withDotNet(sdk: env.DOTNET_VERSION) {
                logging.logSubSection("Dependency Restoration")
                logging.logInfo("Restore command", "dotnet restore")
                bat "dotnet restore"
                logging.logSuccess("Dependencies restored successfully")
                
                logging.logSubSection("Application Compilation")
                logging.logInfo("Build command", "dotnet build --configuration ${env.CONFIGURATION} --no-restore")
                bat "dotnet build --configuration ${env.CONFIGURATION} --no-restore"
                logging.logSuccess("Application compiled successfully")
                
                logging.logSubSection("Publication for Deployment")
                logging.logInfo("Publish command", "dotnet publish --configuration ${env.CONFIGURATION} --no-build")
                logging.logInfo("Output directory", buildPath)
                bat "dotnet publish --configuration ${env.CONFIGURATION} --no-build --output \"${buildPath}\""
                logging.logSuccess("Application published successfully")
            }
            
            // Store build path for artifact archiving
            env."BUILD_PATH_${env.PROJECT_NAME}" = buildPath
            logging.logSuccess("Build completed for ${env.PROJECT_NAME}")
            logging.logInfo("Artifacts location", buildPath)
        }
    } catch (Exception e) {
        logging.logError("Build failed: ${e.message}")
        github.updateGitHubStatus('error', 'Build Failed', "Build failed for project: ${env.PROJECT_NAME}")
        throw e
    }
}

def runUnitTests(String testProjectName) {
    logging.logSection("UNIT TESTING")
    logging.logInfo("Test project", testProjectName)
    logging.logInfo(".NET version", env.DOTNET_VERSION)
    
    try {
        def testContext = env.DOTNET_TEST_CONTEXT ?: testProjectName
        dir(testContext) {
            withDotNet(sdk: env.DOTNET_VERSION) {
                logging.logSubSection("Test Execution")
                logging.logInfo("Test command", "dotnet test --no-build --verbosity normal")
                bat "dotnet test --no-build --verbosity normal"
                logging.logSuccess("All tests passed successfully")
            }
        }
    } catch (Exception e) {
        logging.logError("Tests failed: ${e.message}")
        github.updateGitHubStatus('failure', 'Tests Failed', "Tests failed for project: ${env.PROJECT_NAME}")
        throw e
    }
}

def archiveArtifacts() {
    logging.logSubSection("Artifact Archiving")
    
    def stashName = (env.ARTIFACT_NAME?.trim()) ? env.ARTIFACT_NAME.trim() : 'multi-server-build-artifacts'
    archiveArtifacts artifacts: "${env.PUBLISH_PATH}/**/*", fingerprint: true
    stash name: stashName, includes: "${env.PUBLISH_PATH}/**/*"
    
    logging.logSuccess("Build artifacts archived and ready for transfer")
}

