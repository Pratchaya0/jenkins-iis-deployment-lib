/**
 * Wraps GitHub deployment tracking so pipelines can use trackDeployment(environment) { steps }
 * Delegates to github.trackDeployment()
 *
 * Usage:
 *   trackDeployment(env.DEPLOYMENT_ENVIRONMENT) {
 *       // deployment steps
 *   }
 */
def call(String environment, Closure deploymentSteps) {
    github.trackDeployment(environment, deploymentSteps)
}
