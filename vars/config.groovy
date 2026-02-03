/**
 * Configuration Management Functions for Jenkins Pipelines
 * Handles configuration loading and environment setup
 * 
 * Usage:
 *   config.loadConfiguration()
 *   config.setupEnvironment()
 * 
 * Or as a step:
 *   config(action: 'loadConfiguration')
 *   config(action: 'setupEnvironment')
 */

def call(Map config = [:]) {
    def action = config.action
    if (!action) {
        error("config: 'action' parameter is required")
    }
    
    switch(action) {
        case 'loadConfiguration':
            loadConfiguration()
            break
        case 'setupEnvironment':
            setupEnvironment()
            break
        default:
            error("config: Unknown action '${action}'. Valid actions: loadConfiguration, setupEnvironment")
    }
}

def loadConfiguration() {
    logging.logSubSection("Configuration Loading & Environment Setup")
    
    configFileProvider([configFile(fileId: "${env.CONFIG_FILE_ID}", variable: 'configFile')]) {
        def configJson = readFile(file: configFile)

        if (configJson.trim().length() == 0) {
            logging.logError("Configuration file is empty")
            error("Config file is empty: ${env.CONFIG_FILE_ID}")
        }

        try {
            env.CONFIG = configJson
            def config = readJSON text: configJson
            
            if (config instanceof List) {
                env.PROJECT_STRUCTURE_TYPE = "1"
                logging.logSuccess("Configuration loaded successfully (Array format)")
                logging.logInfo("Projects found", "${config.size()} project configuration(s)")
            } else if (config instanceof Map) {
                env.PROJECT_STRUCTURE_TYPE = "2"
                env.CONFIG = "[${configJson}]"
                logging.logSuccess("Configuration loaded successfully (Single object format)")
            } else {
                logging.logError("Unsupported configuration format")
                error("Unsupported config format: ${env.CONFIG_FILE_ID}")
            }
            
            env.SELECTED_PROJECT_INDEX = "0"
            
        } catch (Exception e) {
            logging.logError("Invalid JSON format in configuration file")
            error("Invalid JSON format in config file: ${env.CONFIG_FILE_ID}\nError: ${e.message}")
        }
    }
}

def setupEnvironment() {
    def config = readJSON text: env.CONFIG
    def project = config[0]
    
    logging.logSubSection("Setting Up Environment Variables")
    
    // Detect project type
    def isDotNetProject = project.dotnet_version != null
    def isReactProject = project.node_version != null
    
    // Project basics (common)
    env.PROJECT_NAME = project.project_name ?: project.iis_website_name
    env.IIS_WEBSITE_NAME = project.iis_website_name
    
    // Project-specific basics
    if (isDotNetProject) {
        env.PROJECT_TEST_NAME = project.project_test_name ?: ""
        env.DOTNET_VERSION = project.dotnet_version
        env.IS_RUN_BUILD = project.is_run_build.toString()
        env.IS_RUN_BUILD = project.is_run_build.toString()
        env.IS_RUN_TEST = project.is_run_test.toString()
        // Allow overriding build context (directory) and output subpath
        env.DOTNET_BUILD_CONTEXT = project.dotnet_build_context ?: ""
        env.DOTNET_TEST_CONTEXT = project.dotnet_test_context ?: ""
        env.DOTNET_BUILD_OUTPUT_SUBPATH = project.dotnet_build_output_subpath ?: ""
        logging.logInfo("Project type", ".NET Application")
    } else if (isReactProject) {
        env.NODE_VERSION = project.node_version
        logging.logInfo("Project type", "React Application")
    }
    
    // Server Configuration
    env.BUILD_AGENT_LABEL = project.environment_config?.build_agent_label ?: 'built-in'
    env.DEPLOY_AGENT_LABEL = project.environment_config?.deploy_agent_label ?: 'built-in'
    
    // Deployment Paths
    env.BASE_WEBSITE_PATH = project.environment_config?.base_website_path ?: "D:\\Publish\\${env.PROJECT_NAME}"
    env.BASE_BACKUPS_PATH = project.environment_config?.base_backups_path ?: "D:\\Publish\\${env.PROJECT_NAME}\\Backup"

    // Maintenance Mode (React only typically)
    env.IS_MA = project.environment_config?.is_ma ?: false
    env.MA_PATH = project.environment_config?.ma_path ?: "D:\\Maintenance"
    
    // Build Configuration
    env.ENVIRONMENT = project.environment_config?.environment ?: 'UAT'
    env.PUBLISH_PATH = project.environment_config?.publish_path ?: 'publish'
    
    // Build-specific configuration
    if (isDotNetProject) {
        env.CONFIGURATION = project.environment_config?.configuration ?: 'Release'
    } else if (isReactProject) {
        env.BUILD_PATH = project.environment_config?.build_path ?: env.ENVIRONMENT.toLowerCase()
        env.BUILD_COMMAND = project.environment_config?.build_command ?: "npm run build:${env.ENVIRONMENT.toLowerCase()}"
        // Directory to run npm/build in. Optional: set in environment_config.react_build_context; default: subfolder when multi-project (type "1") and project_name set, else "."
        def explicitContext = project.environment_config?.react_build_context?.toString()?.trim()
        env.REACT_BUILD_CONTEXT = (explicitContext != null && !explicitContext.isEmpty()) ? explicitContext : ((env.PROJECT_STRUCTURE_TYPE == "1" && project.project_name) ? project.project_name : ".")
        // Relative path under workspace where build artifacts are placed (buildPath = WORKSPACE + this). Optional: set in environment_config.react_build_output_subpath; default: publish_path + project name.
        def explicitSubpath = project.environment_config?.react_build_output_subpath?.toString()?.trim()
        env.REACT_BUILD_OUTPUT_SUBPATH = (explicitSubpath != null && !explicitSubpath.isEmpty()) ? explicitSubpath : "${env.PUBLISH_PATH}\\${env.PROJECT_NAME}"
        // Node.js tool name in Jenkins (optional). Default: "node ${NODE_VERSION}" e.g. "node 16.15.0"
        env.NODE_INSTALLATION_NAME = project.environment_config?.node_installation_name ?: "node ${env.NODE_VERSION}"
        // NPM cache directory on build agent (optional). Default: C:\npm-cache
        env.NPM_CACHE_PATH = project.environment_config?.npm_cache_path ?: "C:\\npm-cache"
    }
    
    // GitHub Configuration
    env.GITHUB_STATUS_UPDATE = project.environment_config?.github_status_update?.toString() ?: "false"
    env.GITHUB_DEPLOYMENTS_UPDATE = project.environment_config?.github_deployments_update?.toString() ?: "false"
    env.GITHUB_REPO_OWNER = project.environment_config?.github_repo_owner ?: 'YourOrgName'
    env.GITHUB_REPO_NAME = project.environment_config?.github_repo_name ?: (isDotNetProject ? env.PROJECT_NAME.toLowerCase() : env.PROJECT_NAME)
    env.GITHUB_BRANCH_NAME = project.environment_config?.github_branch_name ?: 'develop'
    env.GITHUB_STATUS_CONTEXT = project.environment_config?.github_status_context ?: "jenkins/deployment-ci/${env.ENVIRONMENT.toLowerCase()}"
    env.GITHUB_TOKEN_CREDENTIAL_ID = project.environment_config?.github_token_credential_id ?: 'github-token'
    
    // Deployment Tracking
    env.DEPLOYMENT_ENVIRONMENT = project.environment_config?.deployment_environment ?: env.ENVIRONMENT
    
    // Smart URL generation
    if (project.environment_config?.environment_url) {
        env.ENVIRONMENT_URL = project.environment_config.environment_url
    } else {
        def envLower = env.ENVIRONMENT.toLowerCase()
        def projectLower = env.PROJECT_NAME.toLowerCase()
        if (envLower == 'production' || envLower == 'prod') {
            env.ENVIRONMENT_URL = "https://${projectLower}.yourdomain.com"
        } else {
            env.ENVIRONMENT_URL = "https://${projectLower}.${envLower}yourdomain.com"
        }
    }
    
    // Artifact Configuration
    env.ARTIFACT_NAME = project.environment_config?.artifact_name ?: "${env.PROJECT_NAME}-build-artifacts"
    
    logging.logSuccess("Environment configuration completed")
    logging.logInfo("Project Name", env.PROJECT_NAME)
    logging.logInfo("Environment", env.ENVIRONMENT)
    logging.logInfo("Build Agent", env.BUILD_AGENT_LABEL)
    logging.logInfo("Deploy Agent", env.DEPLOY_AGENT_LABEL)
    if (isDotNetProject) {
        logging.logInfo(".NET Version", env.DOTNET_VERSION)
        logging.logInfo("Configuration", env.CONFIGURATION)
    } else if (isReactProject) {
        logging.logInfo("Build Command", env.BUILD_COMMAND)
    }
    logging.logInfo("Environment URL", env.ENVIRONMENT_URL)
}

