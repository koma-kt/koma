# Published-artifact consumer verification

This is an **independent Gradle build**, with its own settings and two projects. It is not
included by Actron's root settings. There are no `project(...)` dependencies on the library,
composite builds, dependency substitutions or `mavenLocal()` fallbacks. Fork coordinates are
restricted to an isolated Maven repository, so cached or remotely published releases cannot
silently substitute for the artifacts built by the check.

From the repository root:

```bash
scripts/verify-published-consumer.sh jvm
scripts/verify-published-consumer.sh android
scripts/verify-published-consumer.sh js
scripts/verify-published-consumer.sh wasm
scripts/verify-published-consumer.sh ios # requires macOS/Xcode
```

Each invocation publishes the **BOM and fourteen library modules**, including persistence, test and debug tooling,
with their root metadata and the selected platform's variants. Group/version are read from
`gradle.properties`; plugin/dependency versions are read from the producer's version catalog.
It creates and deletes a unique temporary Maven repository. It does not publish to Maven
Central or modify the user's MavenLocal repository. Credentials and signing are unnecessary.

The check validates BOM constraints and consumes modules without individual versions.
It also validates POM coordinates/dependencies, Gradle metadata redirects and dependency
coordinates, payload existence/size/SHA-256, and the published production dependency graph.
For JVM, variant metadata and actual class files must target Java 11, independently of the JDK
running Gradle. This catches a default-JVM-target problem that source-project tests can hide.

`app` consumes production modules only. Core, statechart and observability are intentionally
transitive dependencies; common code calls their APIs and compiles Compose ViewStore/Mailbox
usage and the checkpoint persistence boundary without debugger dependencies. The JVM smoke executes StateChart MachineStores, command-handler messages, the message bus
and a logging plugin. CI builds with JDK 17 and executes the JVM smoke with a real JDK 11, so
target-11 bytecode calling newer Java APIs is detected. Locally set
`ACTRON_CONSUMER_JAVA11_HOME=/path/to/jdk-11` to do the same; otherwise the local smoke uses the
Gradle runtime and reports that limitation. `tooling` separately compiles MachineTestDriver, actron-test helpers and
the Time Travel inspector. Graph checks require all fourteen modules to be consumed and reject
source-project substitution, mixed fork versions, upstream Koma dependencies anywhere in the
resolved graph, and test/debug modules in the app graph. Actron does not support source,
binary or runtime interoperability with upstream Koma 4.0.0.

The CI workflow runs JVM, Android, JS, Wasm and both iOS architectures independently. Web and
iOS checks compile consumer APIs; they do not run a browser UI or a physical-device app. JVM
executes the smoke test. This verifies packaging and consumption; it does not replace runtime
integration tests or a production pilot.

Adapter configuration contracts are checked against the **published API**, too. Positive common
fixtures compile safe MachineStore configuration and the Decompose machine adapter. On JVM,
a negative fixture rejects MachineStore `state`/`initialState`/`recover`, the removed `Store`
and `StateChartStore` factories, and use of `createRuntimeStore` without internal opt-in.
An unrelated compiler or dependency failure cannot satisfy the negative check; each operation
must produce its own diagnostic. The captured compiler log remains under `build/reports/contracts`.
