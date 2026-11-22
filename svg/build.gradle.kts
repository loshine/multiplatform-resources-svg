@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinMultiplatformAndroidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    android {
        namespace = "io.github.loshine.svg.mrsvg"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTest { isIncludeAndroidResources = true }

        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }

        packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    js { browser() }
    wasmJs { browser() }

    applyDefaultHierarchyTemplate {
        common {
            group("nonAndroid") {
                withIos()
                withJvm()
                withJs()
                withWasmJs()
            }
        }
    }

    sourceSets {
        androidMain.dependencies { implementation(libs.android.svg) }
        commonMain.dependencies {
            implementation(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.components.resources)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks
    .withType<Test>()
    .matching { it.name.contains("AndroidHostTest", ignoreCase = true) }
    .configureEach {
        jvmArgs(
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        )
    }

compose.resources { generateResClass = never }

ktfmt { kotlinLangStyle() }

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    if (!providers.gradleProperty("skipSigning").map(String::toBoolean).getOrElse(false)) {
        signAllPublications()
    }

    coordinates("io.github.loshine", "multiplatform-resources-svg", version.toString())

    pom {
        name.set("multiplatform-resources-svg")
        description.set("Provides a unified SVG loading API for Kotlin Multiplatform Resources.")
        inceptionYear.set("2025")
        url.set("https://github.com/loshine/multiplatform-resources-svg")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("loshine")
                name.set("Loshine")
                url.set("https://github.com/loshine/")
            }
        }
        scm {
            url.set("https://github.com/loshine/multiplatform-resources-svg/")
            connection.set("scm:git:git@github.com:loshine/multiplatform-resources-svg.git")
            developerConnection.set(
                "scm:git:git@github.com:loshine/multiplatform-resources-svg.git",
            )
        }
    }
}
