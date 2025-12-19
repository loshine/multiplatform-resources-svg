package io.github.loshine.svg

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.DrawableResource

/**
 * A composable that displays an SVG image from a [DrawableResource].
 *
 * This function is a wrapper around the [Image] composable that automatically creates
 * an SVG painter using [rememberSvgPainter]. It simplifies the process of displaying SVG resources
 * across different platforms.
 *
 * @param resource The [DrawableResource] to be displayed.
 * @param contentDescription Text used by accessibility services to describe what this image represents.
 * This should always be provided unless this image is used for decorative purposes, and does not represent
 * a meaningful action that a user can take.
 * @param modifier Modifier used to adjust the layout algorithm or draw decoration content.
 * @param alignment Optional alignment parameter used to place the [DrawableResource] in the given
 * bounds defined by the width and height.
 * @param contentScale Optional scale parameter used to determine the aspect ratio scaling to be used
 * if the bounds are a different size from the intrinsic size of the [DrawableResource].
 * @param alpha Optional opacity to be applied to the [DrawableResource] when it is rendered onscreen.
 * @param colorFilter Optional colorFilter to apply for the [DrawableResource] when it is rendered
 * onscreen.
 */
@Composable
fun SvgImage(
    resource: DrawableResource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
) {
    Image(
        painter = rememberSvgPainter(resource),
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
    )
}
