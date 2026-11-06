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
        namespace = "actron.example.weather.debug"
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
            baseName = "Shared"
            export(project(":weather:shared"))
            transitiveExport = true
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
            api(project(":weather:shared"))
            implementation(project(":actron-timetravel-compose"))
            implementation(libs.decompose.core)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.coroutines.core)
            implementation("org.jetbrains.kotlinx:atomicfu:0.28.0")
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":actron-timetravel-test"))
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

compose.resources {
    packageOfResClass = "actron.example.weather.debug.resources"
}

// Assertions use a reproducible baseline; locale integration tests override and restore it.
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}
