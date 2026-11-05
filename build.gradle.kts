import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.actron.publish) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.bcv)
}

// Public API and ABI on BCV-supported targets, JVM and klib alike, are dumped under each
// module's api/ directory; `apiCheck` (run by CI) fails when the code and the dumps differ, so a
// change of the public surface is a deliberate `apiDump` in the same change.
@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
apiValidation {
    ignoredProjects += "actron-bom" // dependency platform, no Kotlin API
    ignoredProjects += "behaviour-review" // internal CI artifact exporter
    ignoredProjects += "resource-soak" // device verification harness, not a published API
    ignoredProjects += listOf("weather", "shared", "debugTools", "androidApp", "desktopApp", "webApp") // weather example modules
    ignoredProjects += "nullability-guard" // internal Kotlin syntax policy checker
    klib {
        enabled = true
    }
}

tasks.register("checkNullability") {
    group = "verification"
    description = "Rejects new nullable constructs and optional containers in all authored Kotlin sources."
    dependsOn(":nullability-guard:checkPolicy")
}

val nullabilityPluginJar = layout.projectDirectory.file("verification/nullability-guard/build/libs/nullability-guard.jar")
val nullabilityStrictMarker = layout.projectDirectory.file(".actron/nullability/strict")
val finishingNullabilityMigration = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "finishMigration" }
subprojects {
    if (name != "nullability-guard") {
        tasks.withType<KotlinCompilationTask<*>>().configureEach {
            dependsOn(":nullability-guard:jar")
            inputs.files(nullabilityPluginJar, nullabilityStrictMarker)
            compilerOptions.freeCompilerArgs.addAll(
                "-Xplugin=${nullabilityPluginJar.asFile.absolutePath}",
                "-P",
                "plugin:actron-nullability:root=${rootProject.projectDir.absolutePath}",
                "-P",
                "plugin:actron-nullability:enforce=$finishingNullabilityMigration",
            )
        }
    }
}
gradle.projectsEvaluated {
    val compilations =
        subprojects.filter { it.name != "nullability-guard" }.flatMap {
            // IR executable linking introduces no additional authored declarations.
            it.tasks.withType<KotlinCompilationTask<*>>().filter { task -> !task.name.contains("Executable") }
        }
    tasks.named("checkNullability") { dependsOn(compilations) }
    project(":nullability-guard").tasks.named("finishMigration") { dependsOn(compilations) }
}

// The time-travel modules are debug tooling (handoff §11, §12): no production module may depend
// on them, so an app that keeps them in a debug source set keeps them out of its release graph.
// The time-travel and weather examples intentionally demonstrate this tooling on every target.
// `checkDebugGraph` fails on a dependency in the wrong direction; CI runs it next to `apiCheck`.
val debugModules = setOf(":actron-timetravel", ":actron-timetravel-compose", ":actron-timetravel-test", ":weather", ":weather:debugTools", ":weather:androidApp", ":weather:desktopApp", ":weather:webApp")
// Collected once every project is evaluated, at configuration time, so the task's action holds
// plain strings and the configuration cache can keep it.
val debugGraphOffenders = mutableListOf<String>()
gradle.projectsEvaluated {
    for (project in subprojects.filter { it.path !in debugModules }) {
        for (configuration in project.configurations) {
            for (dependency in configuration.dependencies.withType<ProjectDependency>()) {
                if (dependency.path in debugModules) debugGraphOffenders += "${project.path} -> ${dependency.path} (${configuration.name})"
            }
        }
    }
}
val checkDebugGraph by tasks.registering {
    group = "verification"
    description = "Fails when a production module depends on the debug (time-travel) modules."
    val offenders = debugGraphOffenders
    val watched = debugModules.joinToString()
    doLast {
        check(offenders.isEmpty()) { "Debug modules in a production module's graph:\n" + offenders.joinToString("\n") }
        logger.lifecycle("No production module depends on $watched")
    }
}

// A failed test's assertion message and stack trace reach the console (and so CI's log), not
// only its class and line.
subprojects {
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
        }
    }
}

apply(from = "gradle/ktlint.gradle.kts")

apply(from = "gradle/detekt.gradle.kts")

// Shared Compose policy, also run by Detekt and CI.
tasks.register("checkComposeNoBooleanLogic") {
    group = "verification"
    dependsOn(":nullability-guard:checkComposeNoBooleanLogic")
}
