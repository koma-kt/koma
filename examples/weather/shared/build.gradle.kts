plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        // Compose Resources must be packaged as Android assets for this KMP target.
        androidResources.enable = true
        namespace = "actron.example.weather.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTest {}
        compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "WeatherApp"
            isStatic = true
        }
    }
    jvm()
    js(IR) {
        binaries.executable()
        browser()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
            api(project(":actron-decompose"))
            implementation(project(":actron-persistence"))
            implementation(project(":actron-compose"))
            implementation("com.arkivanov.decompose:decompose:3.5.0")
            implementation("com.arkivanov.decompose:extensions-compose:3.5.0")
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.coroutines.core)
            implementation("org.jetbrains.kotlinx:atomicfu:0.28.0")
            implementation(libs.serialization.json)
            implementation("io.ktor:ktor-client-core:3.5.0")
        }
        commonTest.dependencies {
            implementation(project(":actron-statechart-test"))
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation("io.ktor:ktor-client-mock:3.5.0")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.5.0")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("io.ktor:ktor-client-okhttp:3.5.0")
        }
        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.5.0")
        }
        wasmJsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.5.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.5.0")
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

dependencies {
    constraints {
        add("wasmJsMainImplementation", libs.kotlinx.io.core) {
            // Ktor pulls 0.9.0, whose Node loader leaves raw import.meta in browser test bundles.
            because("kotlinx-io 0.9.1 fixes Wasm module loading for Kotlin 2.4's webpack")
        }
    }
}

compose.resources {
    packageOfResClass = "actron.example.weather.resources"
}
