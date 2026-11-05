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
                namespace = "consumer.tooling"
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
    sourceSets.commonMain.dependencies {
        val coordinate = providers.gradleProperty("consumer.group").get()
        val version = providers.gradleProperty("consumer.version").get()
        implementation(project.dependencies.platform("$coordinate:actron-bom:$version"))
        implementation("$coordinate:actron-timetravel-compose")
        implementation("$coordinate:actron-statechart-test")
        implementation("$coordinate:actron-timetravel-test")
        // Test tooling stays in its own consumer; it cannot mask missing production dependencies.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${providers.gradleProperty("consumer.coroutines").get()}")
    }
}
