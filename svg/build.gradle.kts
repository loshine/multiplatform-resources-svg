@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinMultiplatformAndroidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidLibrary {
        namespace = "io.github.loshine.svg"
        compileSdk = libs.versions.android.compileSdk.get().toInt()

        packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    jvm()

    js { browser() }
    wasmJs { browser() }

    applyDefaultHierarchyTemplate {
        group("nonAndroid") {
            withIos()
            withJvm()
            withJs()
            withWasmJs()
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.android.svg)
            implementation(libs.androidx.core.ktx)
        }
        val commonMain by getting {
            dependencies {
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.components.resources)
            }
        }
        val commonTest by getting { dependencies { implementation(libs.kotlin.test) } }
        val nonAndroidMain by getting { dependsOn(commonMain) }
        val nonAndroidTest by getting { dependsOn(commonTest) }
    }
}
