# build-logic

An included Gradle build for repository conventions, not a runtime Actron library. Its
[convention project](convention/README.md) supplies the `actron.publish` plugin used by the
published modules. The runnable Time Travel example does not apply this plugin.

Root `settings.gradle.kts` loads this build through `pluginManagement.includeBuild`.
The shared version catalog is imported by this build's [settings](settings.gradle.kts).
Publishing rules keep all library modules on the fork's group/version and supply their
artifact ids and POM metadata; see the [installation guide](../README.md#installation).

```sh
# From the repository root:
./gradlew -p build-logic :convention:validatePlugins
```

Adding a library module means defining its targets/dependencies, applying the convention,
declaring `publishConvention.artifactId`, including it in root settings and documenting its
API. A debug application can be included without publishing; it must be explicitly classified
by `checkDebugGraph` and excluded from library API dumps.

## Release publishing

Publishing a GitHub release (stable or prerelease) starts [Publish](../.github/workflows/publish.yml).
Use tag `v<VERSION>` or `<VERSION>`, matching `actron.fork.version` in `gradle.properties`
and `versions.actron` in `gradle/libs.versions.toml`. The workflow rejects mismatches and
SNAPSHOT versions before running builds. It does not change the version for you.

With no credentials, leave `MAVEN_CENTRAL_PUBLISH_ENABLED` unset. Release checks and all
publication tasks still run, and Actions stores an `unsigned-maven-repository` artifact.
It contains every module/target plus the BOM and can be inspected without uploading to Central.
It is an unsigned verification artifact, not a signed Central Portal upload bundle.

To enable automatic uploads later:

1. Register with [Central Portal](https://central.sonatype.org/publish/publish-portal-guide/)
   and verify the `io.github.roman-n1` namespace.
2. Create a Central Portal **user token** (its generated username/password are different from
   the account login) and a GPG signing key; distribute its public key as required by Central.
3. Add repository secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` and
   `MAVEN_GPG_KEY_CONTENTS` (the ASCII-armored private signing key). Add `MAVEN_SIGNING_KEY_ID`
   if selecting a signing subkey and `MAVEN_SIGNING_PASSWORD` if the key has a passphrase.
   Keep these values in GitHub Secrets, never in repository files.
4. Set repository variable `MAVEN_CENTRAL_PUBLISH_ENABLED=true`.
5. Publish the next GitHub release. After the checks pass, `publishAndReleaseToMavenCentral`
   signs and uploads **all** library publications, including `actron-bom`, then waits for
   Central's `PUBLISHED` state. No manual Portal release button is required.

Central versions are immutable: rerun a failed upload only after checking its deployment in
Portal; a successful version must never be republished. Releases created before enabling
publication are not backfilled automatically. A full workflow rerun on an unpublished release
can publish it after setup. There is no automatic snapshot publication.

See the plugin's [Central configuration](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

## Version alignment (BOM)

`io.github.roman-n1:actron-bom:<VERSION>` is a Maven BOM / Gradle platform. It derives constraints
from the shared convention's publications, including target artifacts such as `actron-core-jvm`.
It does not pull in any modules or add debug tooling to production dependencies.

```kotlin
dependencies {
    implementation(platform("io.github.roman-n1:actron-bom:<VERSION>"))
    implementation("io.github.roman-n1:actron-statechart")
    implementation("io.github.roman-n1:actron-compose")
}
```

In KMP, use `implementation(project.dependencies.platform("...:actron-bom:<VERSION>"))`
in `commonMain.dependencies`, followed by the versionless module dependencies.
Maven consumers can import the BOM in `dependencyManagement` with type `pom` and scope `import`.
An ordinary platform supplies version constraints; an explicitly higher dependency version can
win Gradle conflict resolution. Use one BOM version and omit individual Actron versions.

The external [published consumer](../verification/published-consumer/README.md) verifies
versionless consumption against an isolated Maven repository on each supported target.

## CI cost controls

All PR workflows cancel superseded runs using distinct concurrency groups. Main and release
checks are not interrupted by this policy. Only the JVM/quality matrix job fetches full Git
history for baseline comparisons. Native compilation jobs share a Kotlin/Native cache
key; Maven consumer and release jobs restore it too. The generic iOS test job excludes the
resource-soak suite because the dedicated resource-budget workflow runs and validates a measured
soak on every PR. Platform coverage and lint/API/policy checks remain enabled.

These changes reduce redundant work; elapsed-time savings require measurement in GitHub Actions.
