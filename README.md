# Multiplatform Resources SVG

[![Maven Central](https://img.shields.io/maven-central/v/io.github.loshine/multiplatform-resources-svg.svg)](https://central.sonatype.com/artifact/io.github.loshine/multiplatform-resources-svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blueviolet?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

[中文](./README_CN.md)

A Kotlin Multiplatform library that provides a unified API for rendering SVG resources, with platform-specific optimizations.

## Features

- **Android**: Leverages [AndroidSVG](https://bigbadaboom.github.io/androidsvg/) with an on-demand rasterizing `Painter` aligned with Compose Multiplatform's Skiko `SvgPainter`, a bounded LRU document cache, and Android Studio Preview support.
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
import io.github.loshine.mrsvg.rememberSvgPainter
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
import io.github.loshine.mrsvg.SvgImage
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

## Notes & Limitations

- **Simple icons**: For simple monochrome or flat UI icons, prefer Android Vector Drawable (`.xml`) with Compose Multiplatform's built-in `painterResource`—it renders natively as Compose `ImageVector` across all platforms with zero extra runtime dependencies.
- **Cross-platform engine differences**: Android renders SVGs with **AndroidSVG 1.4** (SVG 1.1 subset; `<filter>` elements are not supported), while non-Android targets render with **Skia** (`SVGDOM`). Android preserves the SVG's declared or default `preserveAspectRatio` (`xMidYMid meet`) per SVG 1.1, whereas Skia's `SvgPainter` forces `none`. Advanced SVG 2 features or subtle CSS/text layout details may also differ between Android and Skia targets.
- **Android bitmap cap**: To prevent `OutOfMemoryError` and GPU texture limit crashes on extreme draw sizes, each Android painter caps its own ARGB_8888 backing bitmap at 4096 px per edge and ~16 MiB total pixels, downsampling proportionally via canvas scale when exceeded (multiple active painters and reallocation spikes still accumulate).

## Acknowledgments

This library wouldn't be possible without the following amazing open-source project:

- [AndroidSVG](https://bigbadaboom.github.io/androidsvg/): A powerful SVG rendering library for Android.

## License

[Apache License 2.0](LICENSE)
