package io.github.loshine.mrsvg

import android.graphics.Picture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil
import kotlin.math.sqrt

internal const val DEFAULT_MAX_BITMAP_EDGE: Int = 4096
internal const val DEFAULT_MAX_BITMAP_PIXELS: Long = 2048L * 2048L

/**
 * Dynamic SVG [Painter] aligned with Compose Multiplatform's Skiko `SvgPainter`.
 *
 * Rasterizes the [document] on demand at the actual [DrawScope.size] into a per-painter
 * [SvgDrawCache], capping maximum bitmap dimensions to prevent OOM or GPU texture overflows while
 * preserving logical SVG geometry, exact float clipping, and [applyAlpha]/[applyColorFilter].
 */
internal class AndroidSvgPainter(
    private val document: SvgDocument,
    private val density: Density,
    private val maxBitmapEdge: Int = DEFAULT_MAX_BITMAP_EDGE,
    private val maxBitmapPixels: Long = DEFAULT_MAX_BITMAP_PIXELS,
) : Painter() {

    override val intrinsicSize: Size
        get() {
            val defaultSize = document.defaultSizePx
            return if (defaultSize.isSpecified) {
                defaultSize * density.density
            } else {
                Size.Unspecified
            }
        }

    private var alpha: Float = DefaultAlpha
    private var colorFilter: ColorFilter? = null

    private var previousDrawSize: Size = Size.Unspecified
    private var cachedIsCapped: Boolean = false
    private var hasRenderedContent: Boolean = false
    internal val drawCache = SvgDrawCache()

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        if (!document.isRenderable) {
            return
        }

        val logicalSize = size
        if (
            !logicalSize.width.isFinite() ||
                !logicalSize.height.isFinite() ||
                logicalSize.width <= 0f ||
                logicalSize.height <= 0f
        ) {
            return
        }

        val dstSize =
            IntSize(
                width = ceil(logicalSize.width).toInt().coerceAtLeast(1),
                height = ceil(logicalSize.height).toInt().coerceAtLeast(1),
            )

        if (previousDrawSize != logicalSize) {
            val bitmapSize =
                computeCappedBitmapSize(
                    targetWidth = dstSize.width,
                    targetHeight = dstSize.height,
                    maxEdge = maxBitmapEdge,
                    maxPixels = maxBitmapPixels,
                )
            val isCapped = bitmapSize != dstSize
            val scaleX = bitmapSize.width.toFloat() / logicalSize.width
            val scaleY = bitmapSize.height.toFloat() / logicalSize.height
            var renderSucceeded = true

            drawCache.drawCachedImage(
                size = bitmapSize,
                density = this,
                layoutDirection = layoutDirection,
            ) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val saveCount = native.save()
                    try {
                        if (isCapped) {
                            // Record at 1:1 logical coordinates into a Picture first, then replay
                            // scaled into the capped bitmap. This preserves logical geometry and
                            // prevents AndroidSVG's non-scaling-stroke (which resets Canvas matrix)
                            // from magnifying strokes during downsampling.
                            val picture = Picture()
                            val recordingCanvas =
                                picture.beginRecording(dstSize.width, dstSize.height)
                            val recordingSaveCount = recordingCanvas.save()
                            try {
                                recordingCanvas.clipRect(
                                    0f,
                                    0f,
                                    logicalSize.width,
                                    logicalSize.height,
                                )
                                document.renderToCanvas(
                                    canvas = recordingCanvas,
                                    width = logicalSize.width,
                                    height = logicalSize.height,
                                )
                            } finally {
                                recordingCanvas.restoreToCount(recordingSaveCount)
                                picture.endRecording()
                            }
                            native.scale(scaleX, scaleY)
                            native.clipRect(0f, 0f, logicalSize.width, logicalSize.height)
                            picture.draw(native)
                        } else {
                            native.clipRect(0f, 0f, logicalSize.width, logicalSize.height)
                            document.renderToCanvas(
                                canvas = native,
                                width = logicalSize.width,
                                height = logicalSize.height,
                            )
                        }
                    } catch (e: RuntimeException) {
                        renderSucceeded = false
                        logSvgError("Failed to render SVG document", e)
                    } finally {
                        native.restoreToCount(saveCount)
                    }
                }
            }
            previousDrawSize = logicalSize
            cachedIsCapped = isCapped
            hasRenderedContent = renderSucceeded
        }

        if (!hasRenderedContent) {
            return
        }

        drawCache.drawInto(
            target = this,
            logicalSize = logicalSize,
            isCapped = cachedIsCapped,
            alpha = alpha,
            colorFilter = colorFilter,
        )
    }
}

/**
 * Computes the rasterization bitmap size for a target draw size while respecting both maximum edge
 * length and maximum total pixel budget.
 */
internal fun computeCappedBitmapSize(
    targetWidth: Int,
    targetHeight: Int,
    maxEdge: Int = DEFAULT_MAX_BITMAP_EDGE,
    maxPixels: Long = DEFAULT_MAX_BITMAP_PIXELS,
): IntSize {
    require(maxEdge >= 1) { "maxEdge must be >= 1, was $maxEdge" }
    require(maxPixels >= 1L) { "maxPixels must be >= 1, was $maxPixels" }

    val safeW = targetWidth.coerceAtLeast(1)
    val safeH = targetHeight.coerceAtLeast(1)

    var scale = 1.0
    val longestEdge = maxOf(safeW, safeH)
    if (longestEdge > maxEdge) {
        scale = minOf(scale, maxEdge.toDouble() / longestEdge.toDouble())
    }
    val totalPixels = safeW.toLong() * safeH.toLong()
    if (totalPixels > maxPixels) {
        scale = minOf(scale, sqrt(maxPixels.toDouble() / totalPixels.toDouble()))
    }

    if (scale >= 1.0) {
        return IntSize(safeW, safeH)
    }

    var cappedW = (safeW * scale).toInt().coerceIn(1, maxEdge)
    var cappedH = (safeH * scale).toInt().coerceIn(1, maxEdge)
    if (cappedW.toLong() * cappedH.toLong() > maxPixels) {
        if (cappedW >= cappedH) {
            cappedW = (maxPixels / cappedH.toLong()).toInt().coerceIn(1, maxEdge)
        } else {
            cappedH = (maxPixels / cappedW.toLong()).toInt().coerceIn(1, maxEdge)
        }
    }
    return IntSize(cappedW, cappedH)
}

/**
 * Per-painter offscreen bitmap cache mirroring Compose's internal `DrawCache`, with automatic
 * downsizing when an oversized backing bitmap shrinks drastically.
 */
internal class SvgDrawCache {
    internal var cachedImage: ImageBitmap? = null
        private set

    private var cachedCanvas: Canvas? = null
    internal var cachedSize: IntSize = IntSize.Zero
        private set

    private val cacheScope = CanvasDrawScope()

    fun drawCachedImage(
        size: IntSize,
        density: Density,
        layoutDirection: LayoutDirection,
        block: DrawScope.() -> Unit,
    ) {
        var targetImage = cachedImage
        var targetCanvas = cachedCanvas

        val needsReallocate =
            targetImage == null ||
                targetCanvas == null ||
                size.width > targetImage.width ||
                size.height > targetImage.height ||
                shouldShrink(targetImage, size)

        if (needsReallocate) {
            targetImage = ImageBitmap(size.width, size.height, config = ImageBitmapConfig.Argb8888)
            targetCanvas = Canvas(targetImage)
            cachedImage = targetImage
            cachedCanvas = targetCanvas
        }

        cachedSize = size
        cacheScope.draw(density, layoutDirection, targetCanvas, size.toSize()) {
            drawRect(color = Color.Black, blendMode = BlendMode.Clear)
            block()
        }
        targetImage.prepareToDraw()
    }

    fun drawInto(
        target: DrawScope,
        logicalSize: Size,
        isCapped: Boolean,
        alpha: Float = DefaultAlpha,
        colorFilter: ColorFilter? = null,
    ) {
        val image = cachedImage ?: return
        target.clipRect(
            left = 0f,
            top = 0f,
            right = logicalSize.width,
            bottom = logicalSize.height,
        ) {
            if (isCapped) {
                scale(
                    scaleX = logicalSize.width / cachedSize.width.toFloat(),
                    scaleY = logicalSize.height / cachedSize.height.toFloat(),
                    pivot = Offset.Zero,
                ) {
                    drawImage(
                        image = image,
                        srcSize = cachedSize,
                        dstSize = cachedSize,
                        alpha = alpha,
                        colorFilter = colorFilter,
                    )
                }
            } else {
                drawImage(
                    image = image,
                    srcSize = cachedSize,
                    dstSize = cachedSize,
                    alpha = alpha,
                    colorFilter = colorFilter,
                )
            }
        }
    }

    private fun shouldShrink(existing: ImageBitmap, requested: IntSize): Boolean {
        val existingPixels = existing.width.toLong() * existing.height.toLong()
        val requestedPixels = requested.width.toLong() * requested.height.toLong()
        return existingPixels > SHRINK_THRESHOLD_PIXELS && existingPixels > requestedPixels * 4L
    }

    private companion object {
        const val SHRINK_THRESHOLD_PIXELS: Long = 256L * 256L
    }
}
