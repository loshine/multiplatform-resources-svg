package io.github.loshine.mrsvg

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource

/**
 * Remembers and returns a [androidx.compose.ui.graphics.painter.Painter] for rendering SVG
 * resources.
 *
 * This is an expect function with platform-specific implementations:
 * - Android: Uses AndroidSVG library to parse and render SVG with caching support for better
 *   performance
 * - Other platforms: Uses Compose Multiplatform's default painterResource implementation
 *
 * @param resource The [org.jetbrains.compose.resources.DrawableResource] containing SVG content
 * @return A [androidx.compose.ui.graphics.painter.Painter] that can render the SVG resource
 * @see androidx.compose.ui.graphics.painter.Painter
 * @see org.jetbrains.compose.resources.DrawableResource
 */
@Composable expect fun rememberSvgPainter(resource: DrawableResource): Painter
