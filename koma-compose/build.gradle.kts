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
    android {
        namespace = "koma.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()
    // Web targets (js / wasmJs) are browser-only here — intentionally no nodejs(). This module
    // depends on Compose Multiplatform, whose rendering layer (Skiko) ships a
    // browser-only web runtime: the generated skiko.mjs has its Node loader
    // compiled out, so running on Node aborts with
    // "both async and sync fetching of the wasm failed". The pure-logic modules
    // (koma-core etc.) have no Skiko dependency and therefore also add nodejs().
    js(IR) {
        browser()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        // Karma's browser timeouts are raised in karma.config.d/timeout.js so the
        // large Skiko/Compose Wasm test bundle has time to load on slower (CI)
        // machines before the no-activity watchdog fires.
        browser()
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

// rin 0.4.0 pulls org.jetbrains.compose.ui:* transitively at 1.6.10, which never
// gets upgraded to the plugin version (1.11.1) because the ui artifacts are only
// runtime/transitive here. The 1.6.10 ui klibs ship an older androidx.collection
// representation that collides with the 1.11.x one, so the JS/Wasm IR linker fails
// with "androidx.collection/EmptyFloatArray is already bound". Force the whole
// compose.ui group to the plugin version so a single collection klib is on the
// classpath.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.compose.ui") {
            useVersion(libs.versions.compose.multiplatform.get())
        }
    }
}

publishConvention {
    artifactId = "koma-compose"
}
