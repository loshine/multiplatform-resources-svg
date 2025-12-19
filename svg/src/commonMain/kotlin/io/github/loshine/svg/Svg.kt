package io.github.loshine.svg

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource

/**
 * Remembers and returns a [Painter] for rendering SVG resources.
 *
 * This is an expect function with platform-specific implementations:
 * - Android: Uses AndroidSVG library to parse and render SVG with caching support for better
 *   performance
 * - Other platforms: Uses Compose Multiplatform's default painterResource implementation
 *
 * @param resource The [DrawableResource] containing SVG content
 * @return A [Painter] that can render the SVG resource
 * @see Painter
 * @see DrawableResource
 */
@Composable expect fun rememberSvgPainter(resource: DrawableResource): Painter
