# Multiplatform Resources SVG

[![Maven Central](https://img.shields.io/maven-central/v/io.github.loshine/multiplatform-resources-svg.svg)](https://central.sonatype.com/artifact/io.github.loshine/multiplatform-resources-svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blueviolet?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

[中文](./README_CN.md)

A Kotlin Multiplatform library that provides a unified API for rendering SVG resources, with platform-specific optimizations.

## Features

- **Android**: Leverages the [AndroidSVG](https://bigbadaboom.github.io/androidsvg/) library for robust SVG rendering and implements an asynchronous cache to improve performance.
- **Other Platforms (iOS, JVM, JS, Wasm)**: Utilizes the standard [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) `painterResource` for seamless integration.
- **Unified API**: Exposes a simple `rememberSvgPainter` Composable that works across all supported targets.

## Installation

Add the dependency to your `build.gradle.kts` (commonMain):

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // Replace with latest version
            implementation("io.github.loshine:multiplatform-resources-svg:{latest_version}")
        }
    }
}
```

## Usage

Use `rememberSvgPainter` to load and render your SVG resources. This function acts as a drop-in replacement for `painterResource` when working with SVGs, ensuring optimal handling on Android.

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import io.github.loshine.svg.rememberSvgPainter
import kotlinproject.composeapp.generated.resources.Res
import kotlinproject.composeapp.generated.resources.my_icon

@Composable
fun MyIcon() {
    Image(
        painter = rememberSvgPainter(Res.drawable.my_icon),
        contentDescription = "My Icon"
    )
}
```

### SvgImage

Alternatively, you can use the `SvgImage` composable directly, which wraps `Image` and `rememberSvgPainter` for convenience:

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.loshine.svg.SvgImage
import kotlinproject.composeapp.generated.resources.Res
import kotlinproject.composeapp.generated.resources.my_icon

@Composable
fun MyIcon() {
    SvgImage(
        resource = Res.drawable.my_icon,
        contentDescription = "My Icon",
        // Optional: Apply modifiers, scaling, etc.
        modifier = Modifier.size(24.dp)
    )
}
```

## Supported Targets

- **Android**
- **iOS**
- **JVM (Desktop)**
- **JS**
- **Wasm (JS)**

## Acknowledgments

This library wouldn't be possible without the following amazing open-source project:

- [AndroidSVG](https://bigbadaboom.github.io/androidsvg/): A powerful SVG rendering library for Android.

## License

[Apache License 2.0](LICENSE)
