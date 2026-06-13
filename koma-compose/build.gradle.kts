import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.koma.publish)
}

group = "io.github.koma-kt"
version = libs.versions.koma.get()

kotlin {
    androidLibrary {
        namespace = "koma.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(
                    JvmTarget.JVM_11,
                )
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    jvm()
    js(IR) {
        browser()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // Compose Multiplatform's Wasm bundle (Skiko + Compose runtime) takes
            // long enough to load that Karma's default no-activity timeout (30s)
            // fires before the test runner connects. Until we can override Karma's
            // timeouts reliably under KGP 2.2, keep wasm test execution off.
            // compileKotlinWasmJs / compileTestKotlinWasmJs still run, so wasm
            // compilation is verified in CI.
            testTask {
                enabled = false
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":koma-core"))
            implementation(compose.runtime)
            implementation(libs.rin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

publishConvention {
    artifactId = "koma-compose"
}
