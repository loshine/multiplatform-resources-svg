package io.github.loshine.mrsvg

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Non-Android implementation of [rememberSvgPainter].
 *
 * Uses Compose Multiplatform's built-in [org.jetbrains.compose.resources.painterResource] function
 * to handle SVG resources. On non-Android platforms, the framework's default SVG support is
 * sufficient.
 *
 * @param resource The SVG drawable resource
 * @return A [Painter] that renders the SVG content
 */
@Composable
actual fun rememberSvgPainter(resource: DrawableResource): Painter = painterResource(resource)
