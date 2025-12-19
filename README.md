# Multiplatform Resources SVG

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
            implementation("io.github.loshine.svg:svg:{latest_version}") // Replace with latest version
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

## License

[MIT License](LICENSE)
