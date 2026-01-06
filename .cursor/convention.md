Here’s a **clear, practical explanation of a Jenkins Shared Library folder structure**, which is what most people mean by “Jenkins library”.

---

## 1. What is a Jenkins Shared Library?

A Jenkins Shared Library is a **Git repository** that contains reusable pipeline code (Groovy) so you don’t copy/paste Jenkinsfiles across projects.

It is loaded in Jenkins with:

```groovy
@Library('my-shared-lib') _
```

---

## 2. Standard Jenkins Shared Library Structure

```text
(my-shared-lib)
├── vars/
│   ├── build.groovy
│   ├── deploy.groovy
│   └── notify.groovy
│
├── src/
│   └── org/
│       └── company/
│           └── pipeline/
│               ├── DockerUtils.groovy
│               ├── GitUtils.groovy
│               └── SlackNotifier.groovy
│
├── resources/
│   ├── templates/
│   │   ├── deployment.yaml
│   │   └── Dockerfile.tpl
│   └── scripts/
│       └── cleanup.sh
│
├── Jenkinsfile   (optional, for testing the library itself)
└── README.md
```

---

## 3. Folder-by-Folder Explanation

### 📁 `vars/` (Global Pipeline Steps)

* **Most commonly used**
* Each `.groovy` file becomes a **global function**
* Filename = function name

Example:

```text
vars/build.groovy
```

```groovy
def call(Map config = [:]) {
    echo "Building ${config.appName}"
}
```

Usage in Jenkinsfile:

```groovy
build(appName: "my-service")
```

✅ Best for **simple, reusable pipeline steps**

---

### 📁 `src/` (Reusable Classes)

* For **complex logic**
* Standard Groovy/Java package structure
* Must be imported explicitly

Example:

```groovy
import org.company.pipeline.DockerUtils
```

```groovy
class DockerUtils {
    static void buildImage(script, String tag) {
        script.sh "docker build -t ${tag} ."
    }
}
```

Usage:

```groovy
DockerUtils.buildImage(this, "myapp:latest")
```

✅ Best for **business logic, utilities, helpers**

---

### 📁 `resources/` (Static Files)

* YAML, JSON, shell scripts, templates
* Loaded at runtime

Example:

```groovy
def tpl = libraryResource 'templates/deployment.yaml'
writeFile file: 'deployment.yaml', text: tpl
```

✅ Best for **K8s manifests, Helm templates, shell scripts**

---

### 📁 `Jenkinsfile` (Optional)

* Used to **test the shared library itself**
* Not required in production usage

---

### 📄 `README.md`

* Explain:

  * Available steps
  * Examples
  * Parameters
  * Versioning

---

## 4. Minimal Example (Good for Starting)

```text
jenkins-shared-lib/
├── vars/
│   └── pipeline.groovy
└── README.md
```

```groovy
// vars/pipeline.groovy
def call() {
    pipeline {
        agent any
        stages {
            stage('Hello') {
                steps {
                    echo 'Hello from shared library'
                }
            }
        }
    }
}
```

Usage:

```groovy
@Library('jenkins-shared-lib') _
pipeline()
```

---

## 5. Best Practices

✅ Keep **pipeline syntax in `vars/`**
✅ Keep **logic in `src/`**
✅ Pass `this` when calling utility classes
✅ Version your library (`main`, `v1`, `v2`)
✅ Keep steps small and composable

---

## 6. Common Mistakes ❌

* Putting pipeline code in `src/`
* Writing huge logic in `vars/`
* Forgetting to pass `this` to utility classes
* Not documenting parameters
