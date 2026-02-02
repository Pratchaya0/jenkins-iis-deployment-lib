### How `@Library("jenkins-lib@...")` works

The syntax is:

```
@Library('<library-name>@<version>') _
```

Where `<version>` can be:

* ✅ **Git branch** → `main`, `develop`, `release/1.2`
* ✅ **Git tag** → `v1.0.0`, `v2.3.1`
* ✅ **Commit SHA** → `a1b2c3d4`

### Examples

**Using a tag (recommended for stability):**

```groovy
@Library('jenkins-lib@v1.2.0') _
```

**Using a branch (fast-moving):**

```groovy
@Library('jenkins-lib@main') _
```

**Using a specific commit (pin-point reproducibility):**

```groovy
@Library('jenkins-lib@a1b2c3d4') _
```

### Best practice (real talk 🧠)

| Use case             | What to use                 |
| -------------------- | --------------------------- |
| Production pipelines | ✅ **Tags**                  |
| Shared team dev      | Branch (`main` / `develop`) |
| Debugging / hotfix   | Commit SHA                  |

👉 **Tags are ideal** because:

* Immutable
* Reproducible builds
* No surprise breaking changes

### One important Jenkins gotcha ⚠️

Your **Global Pipeline Library** config must allow versioned loading:

* In Jenkins → **Manage Jenkins**
* → **Configure System**
* → **Global Pipeline Libraries**
* ✔️ *Allow default version to be overridden*

Otherwise Jenkins will silently ignore the `@v1.2.0` part 😬

### Tag naming tip

Use **SemVer**:

```
v1.0.0
v1.1.0
v2.0.0
```

