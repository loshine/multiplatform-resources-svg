# Multiplatform Resources SVG

[![Maven Central](https://img.shields.io/maven-central/v/io.github.loshine/multiplatform-resources-svg.svg)](https://central.sonatype.com/artifact/io.github.loshine/multiplatform-resources-svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blueviolet?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

[English](./README.md)

一个 Kotlin Multiplatform 库，为 SVG 资源渲染提供统一的 API，并针对特定平台进行了优化。

## 特性

- **Android**: 基于 [AndroidSVG](https://bigbadaboom.github.io/androidsvg/) 实现与 Compose Multiplatform Skiko `SvgPainter` 对齐的按需光栅化 `Painter`，内置有界 LRU 文档缓存并支持 Android Studio Preview。
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
import io.github.loshine.mrsvg.rememberSvgPainter
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
import io.github.loshine.mrsvg.SvgImage
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

## 使用说明与限制

- **简单图标建议**：对于简单的单色或扁平 UI 图标，建议优先转为 Android Vector Drawable（`.xml`）并直接使用 Compose Multiplatform 内置的 `painterResource`，可在全平台原生以 `ImageVector` 渲染且零额外依赖。
- **跨平台渲染引擎差异**：Android 端使用 **AndroidSVG 1.4**（支持 SVG 1.1 子集，**不支持 `<filter>` 滤镜**），其他平台使用 **Skia**（`SVGDOM`）。Android 端按 SVG 1.1 规范保留 SVG 自身声明或默认的 `preserveAspectRatio`（`xMidYMid meet`），而 Skia `SvgPainter` 会强制拉伸为 `none`；部分 SVG 2 特性或细微 CSS/文字排版在 Android 与 Skia 间也可能存在差异。
- **Android 缓存尺寸上限**：为防止超大绘制尺寸引发 OOM 或超出 GPU 纹理上限，Android 端每个 `Painter` 实例的 ARGB_8888 后备位图限制单边最大 4096 px 且总像素不超过约 16 MiB，超出时保持逻辑坐标系并按比例缩放离屏画布降采样（多个同时存在的 `Painter` 及重分配峰值仍会累加）。

## 致谢

感谢以下优秀的开源项目，为本库提供了核心支持：

- [AndroidSVG](https://bigbadaboom.github.io/androidsvg/): 为 Android 平台提供了强大的 SVG 渲染能力。

## 许可证

[Apache License 2.0](LICENSE)
