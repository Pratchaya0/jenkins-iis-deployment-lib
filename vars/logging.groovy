/**
 * Professional Logging Functions for Jenkins Pipelines
 * Reduces pipeline size by extracting common logging functionality
 * 
 * Usage:
 *   logging.logSection("Build Stage")
 *   logging.logInfo("Version", "1.0.0")
 *   logging.logSuccess("Build completed")
 * 
 * Or as a step:
 *   logging(title: "Build Stage")
 */

def call(Map config = [:]) {
    if (config.title) {
        logSection(config.title)
    } else if (config.subTitle) {
        logSubSection(config.subTitle)
    } else if (config.info) {
        logInfo(config.label ?: '', config.info)
    } else if (config.success) {
        logSuccess(config.success)
    } else if (config.warning) {
        logWarning(config.warning)
    } else if (config.error) {
        logError(config.error)
    } else {
        error("logging: Invalid parameters. Use title, subTitle, info, success, warning, or error")
    }
}

def logSection(String title) {
    def separator = "=" * 80
    def padding = " " * ((80 - title.length() - 2) / 2)
    
    echo ""
    echo separator
    echo "${padding} ${title} ${padding}"
    echo separator
    echo ""
}

def logSubSection(String title) {
    def separator = "-" * 60
    echo ""
    echo separator
    echo " ${title}"
    echo separator
}

def logInfo(String label, String value) {
    def paddedLabel = label.padRight(20)
    echo "[INFO] ${paddedLabel}: ${value}"
}

def logSuccess(String message) {
    echo "[SUCCESS] ${message}"
}

def logWarning(String message) {
    echo "[WARNING] ${message}"
}

def logError(String message) {
    echo "[ERROR] ${message}"
}