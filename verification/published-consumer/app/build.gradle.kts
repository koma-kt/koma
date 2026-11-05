import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
if (providers.gradleProperty("consumer.target").get() == "android") {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
}
kotlin {
    when (providers.gradleProperty("consumer.target").get()) {
        "jvm" -> {
            jvm { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
        }

        "android" -> {
            targets.named<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = "consumer.app"
                compileSdk = providers.gradleProperty("consumer.compileSdk").get().toInt()
                minSdk = 24
            }
        }

        "js" -> {
            js(IR) { browser() }
        }

        "wasm" -> {
            @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
            wasmJs { browser() }
        }

        "ios" -> {
            iosArm64()
            iosSimulatorArm64()
        }

        else -> {
            error("Unsupported consumer target")
        }
    }
    sourceSets {
        commonMain {
            providers.gradleProperty("consumer.contractSources").orNull?.let { kotlin.srcDir(it) }
        }
        commonMain.dependencies {
            val coordinate = providers.gradleProperty("consumer.group").get()
            val version = providers.gradleProperty("consumer.version").get()
            implementation(project.dependencies.platform("$coordinate:actron-bom:$version"))
            // core/statechart/observability are intentionally only transitive dependencies.
            listOf("actron-decompose", "actron-compose", "actron-message", "actron-logging", "actron-statechart-compose", "actron-persistence").forEach { implementation("$coordinate:$it") }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${providers.gradleProperty("consumer.coroutines").get()}")
        }
    }
}

// Build with the same modern JDK as the producer, execute the published-artifact smoke on
// Java 11 in CI. Bytecode target alone cannot catch calls to APIs absent on that runtime.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("consumer.java11.home").orNull?.let { java11Home ->
        val java11 = file("$java11Home/bin/java")
        check(java11.isFile) { "Java 11 runtime not found: $java11" }
        executable = java11.absolutePath
        systemProperty("consumer.expected.java.feature", "11")
    }
}
