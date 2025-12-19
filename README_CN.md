# Multiplatform Resources SVG

[![Maven Central](https://img.shields.io/maven-central/v/io.github.loshine/multiplatform-resources-svg.svg)](https://central.sonatype.com/artifact/io.github.loshine/multiplatform-resources-svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blueviolet?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

[English](./README.md)

一个 Kotlin Multiplatform 库，为 SVG 资源渲染提供统一的 API，并针对特定平台进行了优化。

## 特性

- **Android**: 利用 [AndroidSVG](https://bigbadaboom.github.io/androidsvg/) 库实现强大的 SVG 渲染，并内置异步缓存以提升性能。
- **其他平台 (iOS, JVM, JS, Wasm)**: 使用标准的 [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) `painterResource`，实现无缝集成。
- **统一 API**: 提供简单的 `rememberSvgPainter` Composable 函数，适用于所有支持的目标平台。

## 安装

将依赖项添加到您的 `build.gradle.kts` (commonMain) 中：

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // 请替换为最新版本
            implementation("io.github.loshine:multiplatform-resources-svg:{latest_version}")
        }
    }
}
```

## 使用方法

使用 `rememberSvgPainter` 来加载和渲染您的 SVG 资源。在处理 SVG 时，此函数可作为 `painterResource` 的直接替代品，确保在 Android 平台上获得最佳处理效果。

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
        contentDescription = "我的图标"
    )
}
```

### SvgImage

或者，您可以直接使用 `SvgImage` composable，它封装了 `Image` 和 `rememberSvgPainter`，更加方便：

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
        contentDescription = "我的图标",
        // 可选：应用修饰符、缩放等
        modifier = Modifier.size(24.dp)
    )
}
```

## 支持的平台

- **Android**
- **iOS**
- **JVM (Desktop)**
- **JS**
- **Wasm (JS)**

## 致谢

感谢以下优秀的开源项目，为本库提供了核心支持：

- [AndroidSVG](https://bigbadaboom.github.io/androidsvg/): 为 Android 平台提供了强大的 SVG 渲染能力。

## 许可证

[Apache License 2.0](LICENSE)
