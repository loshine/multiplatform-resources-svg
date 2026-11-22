package io.github.loshine.mrsvg

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceItem
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidSvgPainterTest {

    @Test
    fun viewBoxOnlySvgResolvesIntrinsicSizeAndDrawsFullBounds() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <rect x="0" y="0" width="12" height="24" fill="#FF0000"/>
                <rect x="12" y="0" width="12" height="24" fill="#0000FF"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))

        assertEquals(Size(24f, 24f), doc.defaultSizePx)

        val painter1x = AndroidSvgPainter(doc, Density(1f))
        val painter3x = AndroidSvgPainter(doc, Density(3f))
        assertEquals(Size(24f, 24f), painter1x.intrinsicSize)
        assertEquals(Size(72f, 72f), painter3x.intrinsicSize)

        val rendered = renderPainterToBitmap(painter3x, width = 96, height = 96, density = 3f)
        assertEquals(AndroidColor.RED, rendered.getPixel(24, 48))
        assertEquals(AndroidColor.BLUE, rendered.getPixel(72, 48))
    }

    @Test
    fun noViewBoxSvgDoesNotShrinkOnHighDensityScreen() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="50">
                <rect x="0" y="0" width="50" height="50" fill="#00FF00"/>
                <rect x="50" y="0" width="50" height="50" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))

        assertEquals(Size(100f, 50f), doc.defaultSizePx)

        val painter3x = AndroidSvgPainter(doc, Density(3f))
        assertEquals(Size(300f, 150f), painter3x.intrinsicSize)

        // Previously, setDocumentViewBox used density-multiplied dimensions, shrinking the SVG
        // to the top-left 1/3 (100x50) and leaving (280, 140) transparent.
        val rendered = renderPainterToBitmap(painter3x, width = 300, height = 150, density = 3f)
        assertEquals(AndroidColor.GREEN, rendered.getPixel(10, 10))
        assertEquals(AndroidColor.RED, rendered.getPixel(280, 140))
    }

    @Test
    fun explicitLargeSvgRasterizesAtActualDrawSizeAndResizesAndShrinks() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="450" height="450" viewBox="0 0 64 64">
                <rect x="0" y="0" width="64" height="64" fill="#0000FF"/>
            </svg>
            """
                .trimIndent()
        val rawDoc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))
        val renderCount = AtomicInteger(0)
        val countingDoc =
            object : SvgDocument by rawDoc {
                override fun renderToCanvas(
                    canvas: android.graphics.Canvas,
                    width: Float,
                    height: Float,
                ) {
                    renderCount.incrementAndGet()
                    rawDoc.renderToCanvas(canvas, width, height)
                }
            }

        val painter = AndroidSvgPainter(countingDoc, Density(3f))
        assertEquals(Size(1350f, 1350f), painter.intrinsicSize)

        // Draw at 24dp on 3x screen (72x72 px): must NOT allocate 1350x1350 bitmap.
        val small = renderPainterToBitmap(painter, width = 72, height = 72, density = 3f)
        assertEquals(AndroidColor.BLUE, small.getPixel(36, 36))
        assertEquals(IntSize(72, 72), painter.drawCache.cachedSize)
        assertEquals(72, painter.drawCache.cachedImage?.width)
        assertEquals(72, painter.drawCache.cachedImage?.height)
        assertEquals(1, renderCount.get())

        // Redraw at identical size: must hit DrawCache without invoking renderToCanvas again.
        renderPainterToBitmap(painter, width = 72, height = 72, density = 3f)
        assertEquals(1, renderCount.get())

        // Scale up to 200x200: must re-rasterize at 200x200.
        val large = renderPainterToBitmap(painter, width = 200, height = 200, density = 3f)
        assertEquals(AndroidColor.BLUE, large.getPixel(190, 190))
        assertEquals(IntSize(200, 200), painter.drawCache.cachedSize)
        assertEquals(200, painter.drawCache.cachedImage?.width)
        assertEquals(2, renderCount.get())

        // Grow past shrink threshold (400x400) and then shrink drastically to 40x40: backing bitmap
        // should downsize to avoid retaining oversized memory.
        renderPainterToBitmap(painter, width = 400, height = 400, density = 3f)
        assertEquals(400, painter.drawCache.cachedImage?.width)
        renderPainterToBitmap(painter, width = 40, height = 40, density = 3f)
        assertEquals(40, painter.drawCache.cachedImage?.width)
        assertEquals(40, painter.drawCache.cachedImage?.height)
    }

    @Test
    fun sharedDocumentDoesNotLeakStaleBoundingBoxAcrossViewportResize() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%">
                <defs>
                    <linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stop-color="#FF0000"/>
                        <stop offset="100%" stop-color="#0000FF"/>
                    </linearGradient>
                </defs>
                <rect width="100%" height="100%" fill="url(#g)"/>
            </svg>
            """
                .trimIndent()
        val bytes = svgXml.toByteArray(StandardCharsets.UTF_8)
        val sharedDoc = AndroidSvgDecoder.decode(bytes)
        val freshDoc = AndroidSvgDecoder.decode(bytes)

        val sharedPainter = AndroidSvgPainter(sharedDoc, Density(1f))
        // First render at 20x20 populates AndroidSVG's internal boundingBox on DOM nodes.
        renderPainterToBitmap(sharedPainter, width = 20, height = 20, density = 1f)

        // Next render at 100x20 must recompute gradient geometry for 100x20, matching a fresh doc.
        val resized = renderPainterToBitmap(sharedPainter, width = 100, height = 20, density = 1f)
        val expected =
            renderPainterToBitmap(
                AndroidSvgPainter(freshDoc, Density(1f)),
                width = 100,
                height = 20,
                density = 1f,
            )

        val actualPixel = resized.getPixel(75, 10)
        val expectedPixel = expected.getPixel(75, 10)
        assertTrue(
            AndroidColor.red(actualPixel) > 40,
            "Expected gradient with red component at (75, 10), got #${Integer.toHexString(actualPixel)}",
        )
        assertTrue(
            abs(AndroidColor.red(actualPixel) - AndroidColor.red(expectedPixel)) <= 2 &&
                abs(AndroidColor.blue(actualPixel) - AndroidColor.blue(expectedPixel)) <= 2,
            "Resized gradient pixel #${Integer.toHexString(actualPixel)} did not match fresh doc #${Integer.toHexString(expectedPixel)}",
        )
    }

    @Test
    fun preserveAspectRatioRespectsDefaultMeetSliceAndNone() {
        fun svgWithPar(parAttr: String?): ByteArray {
            val par = if (parAttr != null) """ preserveAspectRatio="$parAttr"""" else ""
            return """
                <svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 100 100"$par>
                    <rect x="0" y="0" width="100" height="50" fill="#FF0000"/>
                    <rect x="0" y="50" width="100" height="50" fill="#0000FF"/>
                </svg>
            """
                .trimIndent()
                .toByteArray(StandardCharsets.UTF_8)
        }

        // Default (xMidYMid meet): 100x100 centered in 200x100 -> left/right margins transparent.
        val defaultBmp =
            renderPainterToBitmap(
                AndroidSvgPainter(AndroidSvgDecoder.decode(svgWithPar(null)), Density(1f)),
                200,
                100,
                1f,
            )
        assertEquals(0, AndroidColor.alpha(defaultBmp.getPixel(10, 50)))
        assertEquals(0, AndroidColor.alpha(defaultBmp.getPixel(190, 50)))
        assertEquals(AndroidColor.RED, defaultBmp.getPixel(100, 25))
        assertEquals(AndroidColor.BLUE, defaultBmp.getPixel(100, 75))

        // Explicit xMinYMin meet: aligned to left, right half transparent.
        val minMeetBmp =
            renderPainterToBitmap(
                AndroidSvgPainter(
                    AndroidSvgDecoder.decode(svgWithPar("xMinYMin meet")),
                    Density(1f),
                ),
                200,
                100,
                1f,
            )
        assertEquals(AndroidColor.RED, minMeetBmp.getPixel(10, 25))
        assertEquals(0, AndroidColor.alpha(minMeetBmp.getPixel(190, 50)))

        // Explicit none: stretched to fill full 200x100 width.
        val noneBmp =
            renderPainterToBitmap(
                AndroidSvgPainter(AndroidSvgDecoder.decode(svgWithPar("none")), Density(1f)),
                200,
                100,
                1f,
            )
        assertEquals(AndroidColor.RED, noneBmp.getPixel(10, 25))
        assertEquals(AndroidColor.BLUE, noneBmp.getPixel(190, 75))

        // Explicit xMidYMid slice: scaled to 200x200 centered vertically -> fills width 0..200.
        val sliceBmp =
            renderPainterToBitmap(
                AndroidSvgPainter(
                    AndroidSvgDecoder.decode(svgWithPar("xMidYMid slice")),
                    Density(1f),
                ),
                200,
                100,
                1f,
            )
        assertEquals(AndroidColor.RED, sliceBmp.getPixel(10, 25))
        assertEquals(AndroidColor.BLUE, sliceBmp.getPixel(190, 75))
    }

    @Test
    fun explicitZeroDimensionsAndZeroViewBoxRenderBlank() {
        val zeroWidthXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="0" height="20" viewBox="0 0 20 20">
                <rect width="20" height="20" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val zeroHeightXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="0px" viewBox="0 0 20 20">
                <rect width="20" height="20" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val zeroViewBoxXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 0 20">
                <rect width="20" height="20" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()

        val zeroWidthDoc =
            AndroidSvgDecoder.decode(zeroWidthXml.toByteArray(StandardCharsets.UTF_8))
        assertEquals(Size.Zero, zeroWidthDoc.defaultSizePx)
        assertEquals(false, zeroWidthDoc.isRenderable)
        val alphaOffsetMatrix =
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0f,
                        0f,
                        0f,
                        0f,
                        255f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        255f,
                    ),
                ),
            )
        val zeroWidthPainter = AndroidSvgPainter(zeroWidthDoc, Density(1f))
        val zeroWidthBmp =
            renderPainterToBitmap(zeroWidthPainter, 20, 20, 1f, colorFilter = alphaOffsetMatrix)
        assertNull(zeroWidthPainter.drawCache.cachedImage)
        assertEquals(0, AndroidColor.alpha(zeroWidthBmp.getPixel(10, 10)))

        val zeroHeightDoc =
            AndroidSvgDecoder.decode(zeroHeightXml.toByteArray(StandardCharsets.UTF_8))
        assertEquals(Size.Zero, zeroHeightDoc.defaultSizePx)
        assertEquals(false, zeroHeightDoc.isRenderable)
        val zeroHeightPainter = AndroidSvgPainter(zeroHeightDoc, Density(1f))
        val zeroHeightBmp =
            renderPainterToBitmap(zeroHeightPainter, 20, 20, 1f, colorFilter = alphaOffsetMatrix)
        assertNull(zeroHeightPainter.drawCache.cachedImage)
        assertEquals(0, AndroidColor.alpha(zeroHeightBmp.getPixel(10, 10)))

        val zeroViewBoxDoc =
            AndroidSvgDecoder.decode(zeroViewBoxXml.toByteArray(StandardCharsets.UTF_8))
        assertEquals(false, zeroViewBoxDoc.isRenderable)
        val zeroViewBoxPainter = AndroidSvgPainter(zeroViewBoxDoc, Density(1f))
        val zeroViewBoxBmp =
            renderPainterToBitmap(zeroViewBoxPainter, 20, 20, 1f, colorFilter = alphaOffsetMatrix)
        assertNull(zeroViewBoxPainter.drawCache.cachedImage)
        assertEquals(0, AndroidColor.alpha(zeroViewBoxBmp.getPixel(10, 10)))

        // Uppercase unit (width="0PX") must also be detected as explicit zero.
        val uppercaseUnitXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="0PX" height="20" viewBox="0 0 20 20">
                <rect width="20" height="20" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val uppercaseDoc =
            AndroidSvgDecoder.decode(uppercaseUnitXml.toByteArray(StandardCharsets.UTF_8))
        assertEquals(Size.Zero, uppercaseDoc.defaultSizePx)
        assertEquals(false, uppercaseDoc.isRenderable)
        val uppercasePainter = AndroidSvgPainter(uppercaseDoc, Density(1f))
        val uppercaseBmp =
            renderPainterToBitmap(uppercasePainter, 20, 20, 1f, colorFilter = alphaOffsetMatrix)
        assertNull(uppercasePainter.drawCache.cachedImage)
        assertEquals(0, AndroidColor.alpha(uppercaseBmp.getPixel(10, 10)))

        // Gzip-compressed SVG with width="0" must also be detected as explicit zero.
        val gzipBytes =
            ByteArrayOutputStream().use { out ->
                GZIPOutputStream(out).use { gzip ->
                    gzip.write(zeroWidthXml.toByteArray(StandardCharsets.UTF_8))
                }
                out.toByteArray()
            }
        val gzipDoc = AndroidSvgDecoder.decode(gzipBytes)
        assertEquals(Size.Zero, gzipDoc.defaultSizePx)
        assertEquals(false, gzipDoc.isRenderable)
        val gzipPainter = AndroidSvgPainter(gzipDoc, Density(1f))
        val gzipBmp =
            renderPainterToBitmap(gzipPainter, 20, 20, 1f, colorFilter = alphaOffsetMatrix)
        assertNull(gzipPainter.drawCache.cachedImage)
        assertEquals(0, AndroidColor.alpha(gzipBmp.getPixel(10, 10)))
    }

    @Test
    fun drawTimeRuntimeExceptionDegradesToBlankAndRestoresCanvasSaveDepth() {
        // AndroidSVG 1.4 parses stop-color="currentColor" successfully but throws
        // ClassCastException during renderToCanvas.
        val currentColorGradientSvg =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" color="#FF0000">
                <defs>
                    <linearGradient id="g">
                        <stop offset="0%" stop-color="currentColor"/>
                        <stop offset="100%" stop-color="#0000FF"/>
                    </linearGradient>
                </defs>
                <rect width="20" height="20" fill="url(#g)"/>
            </svg>
            """
                .trimIndent()
        val doc =
            AndroidSvgDecoder.decode(currentColorGradientSvg.toByteArray(StandardCharsets.UTF_8))
        val painter = AndroidSvgPainter(doc, Density(1f))

        val rendered = renderPainterToBitmap(painter, width = 20, height = 20, density = 1f)
        assertEquals(0, AndroidColor.alpha(rendered.getPixel(10, 10)))

        // Also verify unbalanced save() depth before a throw is restored cleanly.
        var saveDepthBeforeThrow = -1
        var saveDepthAfterSecondDraw = -1
        var shouldThrow = true
        val throwingDoc =
            object : SvgDocument {
                override val defaultSizePx: Size = Size(20f, 20f)

                override fun renderToCanvas(
                    canvas: android.graphics.Canvas,
                    width: Float,
                    height: Float,
                ) {
                    if (shouldThrow) {
                        saveDepthBeforeThrow = canvas.saveCount
                        canvas.save()
                        canvas.save()
                        throw IllegalStateException("simulated AndroidSVG draw failure")
                    } else {
                        saveDepthAfterSecondDraw = canvas.saveCount
                    }
                }
            }
        val throwingPainter = AndroidSvgPainter(throwingDoc, Density(1f))
        renderPainterToBitmap(throwingPainter, width = 20, height = 20, density = 1f)
        shouldThrow = false
        // Resize to trigger a fresh render onto the same cached offscreen Canvas.
        renderPainterToBitmap(throwingPainter, width = 16, height = 16, density = 1f)
        assertTrue(saveDepthBeforeThrow > 0)
        assertEquals(saveDepthBeforeThrow, saveDepthAfterSecondDraw)
    }

    @Test
    fun cappedBitmapDownsamplingPreservesNonScalingStrokeWidth() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
                <line x1="0" y1="32" x2="64" y2="32" stroke="#FF0000" stroke-width="4" vector-effect="non-scaling-stroke"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))
        val painter =
            AndroidSvgPainter(
                document = doc,
                density = Density(1f),
                maxBitmapEdge = 32,
                maxBitmapPixels = 32L * 32L,
            )

        val rendered = renderPainterToBitmap(painter, width = 64, height = 64, density = 1f)
        assertEquals(IntSize(32, 32), painter.drawCache.cachedSize)
        // Center of 4px stroke at y=32 must be red; pixels at y=28 and y=36 (which would be red
        // if stroke magnified to 8px) must remain transparent.
        assertEquals(AndroidColor.RED, rendered.getPixel(32, 32))
        assertEquals(0, AndroidColor.alpha(rendered.getPixel(32, 27)))
        assertEquals(0, AndroidColor.alpha(rendered.getPixel(32, 37)))
        assertTrue(
            AndroidColor.alpha(rendered.getPixel(32, 28)) < 32,
            "Stroke magnified beyond 4px at y=28, alpha=${AndroidColor.alpha(rendered.getPixel(32, 28))}",
        )
        assertTrue(
            AndroidColor.alpha(rendered.getPixel(32, 36)) < 32,
            "Stroke magnified beyond 4px at y=36, alpha=${AndroidColor.alpha(rendered.getPixel(32, 36))}",
        )
    }

    @Test
    fun emptySvgPainterUsesOneByOnePlaceholderIntrinsicSizeAndStaysBlankWithFilters() {
        assertEquals(Size(1f, 1f), EmptySvgPainter.intrinsicSize)

        val alphaOffsetMatrix =
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0f,
                        0f,
                        0f,
                        0f,
                        255f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        255f,
                    ),
                ),
            )
        val target = ImageBitmap(16, 16)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(16f, 16f)) {
            with(EmptySvgPainter) {
                draw(size = Size(16f, 16f), alpha = 0.5f, colorFilter = alphaOffsetMatrix)
            }
        }
        assertEquals(0, AndroidColor.alpha(target.asAndroidBitmap().getPixel(8, 8)))
    }

    @Test
    fun cappedBitmapDownsamplingPreservesLogicalSvgGeometryWithoutViewBox() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg">
                <rect x="0" y="0" width="10" height="10" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))
        val painter =
            AndroidSvgPainter(
                document = doc,
                density = Density(1f),
                maxBitmapEdge = 32,
                maxBitmapPixels = 32L * 32L,
            )

        val rendered = renderPainterToBitmap(painter, width = 64, height = 64, density = 1f)
        assertEquals(IntSize(32, 32), painter.drawCache.cachedSize)
        // Logical 10x10 rect must stay within (0..10, 0..10), NOT double to 20x20.
        assertEquals(AndroidColor.RED, rendered.getPixel(5, 5))
        assertEquals(0, AndroidColor.alpha(rendered.getPixel(15, 15)))
    }

    @Test
    fun subpixelDrawBoundsAreClippedOnNormalAndCappedPaths() {
        val overflowXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" overflow="visible">
                <rect x="0" y="0" width="80" height="64" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(overflowXml.toByteArray(StandardCharsets.UTF_8))

        // Uncapped path: draw logical 10.125 x 10 onto a 20 x 10 canvas.
        val normalPainter = AndroidSvgPainter(doc, Density(1f))
        val normalTarget = ImageBitmap(20, 10)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(normalTarget),
            Size(20f, 10f),
        ) {
            with(normalPainter) { draw(Size(10.125f, 10f)) }
        }
        val normalBmp = normalTarget.asAndroidBitmap()
        assertEquals(AndroidColor.RED, normalBmp.getPixel(9, 5))
        assertTrue(
            AndroidColor.alpha(normalBmp.getPixel(10, 5)) < 64,
            "11th column should be clipped at 10.125px, got alpha=${AndroidColor.alpha(normalBmp.getPixel(10, 5))}",
        )
        assertEquals(0, AndroidColor.alpha(normalBmp.getPixel(11, 5)))

        // Capped path: draw logical 40.125 x 40 onto a 64 x 64 canvas with 20px cap.
        val cappedPainter =
            AndroidSvgPainter(
                document = doc,
                density = Density(1f),
                maxBitmapEdge = 20,
                maxBitmapPixels = 20L * 20L,
            )
        val cappedTarget = ImageBitmap(64, 64)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(cappedTarget),
            Size(64f, 64f),
        ) {
            with(cappedPainter) { draw(Size(40.125f, 40f)) }
        }
        val cappedBmp = cappedTarget.asAndroidBitmap()
        assertEquals(AndroidColor.RED, cappedBmp.getPixel(20, 20))
        assertTrue(
            AndroidColor.alpha(cappedBmp.getPixel(40, 20)) < 64,
            "41st column should be clipped at 40.125px, got alpha=${AndroidColor.alpha(cappedBmp.getPixel(40, 20))}",
        )
        assertEquals(0, AndroidColor.alpha(cappedBmp.getPixel(41, 20)))
    }

    @Test
    fun unspecifiedDimensionsSvgHasUnspecifiedIntrinsicSizeAndStillRenders() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg">
                <rect x="0" y="0" width="40" height="40" fill="#FF00FF"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))

        assertEquals(Size.Unspecified, doc.defaultSizePx)

        val painter = AndroidSvgPainter(doc, Density(2f))
        assertEquals(Size.Unspecified, painter.intrinsicSize)

        val rendered = renderPainterToBitmap(painter, width = 40, height = 40, density = 2f)
        assertEquals(AndroidColor.MAGENTA, rendered.getPixel(20, 20))
    }

    @Test
    fun utf16EncodedSvgDecodesViaInputStream() {
        val svgXml =
            """
            <?xml version="1.0" encoding="UTF-16"?>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                <rect width="16" height="16" fill="#00FFFF"/>
            </svg>
            """
                .trimIndent()
        val utf16Bytes = svgXml.toByteArray(StandardCharsets.UTF_16)
        val doc = AndroidSvgDecoder.decode(utf16Bytes)

        assertEquals(Size(16f, 16f), doc.defaultSizePx)
        val painter = AndroidSvgPainter(doc, Density(1f))
        val rendered = renderPainterToBitmap(painter, width = 16, height = 16, density = 1f)
        assertEquals(AndroidColor.CYAN, rendered.getPixel(8, 8))
    }

    @Test
    fun alphaAndColorFilterAreAppliedAndIsolatedPerPainter() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20">
                <rect width="20" height="20" fill="#FFFFFF"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))

        val tintedPainter = AndroidSvgPainter(doc, Density(1f))
        val plainPainter = AndroidSvgPainter(doc, Density(1f))

        val tinted =
            renderPainterToBitmap(
                painter = tintedPainter,
                width = 20,
                height = 20,
                density = 1f,
                alpha = 0.5f,
                colorFilter = ColorFilter.tint(Color.Blue),
            )
        val tintedPixel = tinted.getPixel(10, 10)
        assertTrue(
            AndroidColor.alpha(tintedPixel) in 120..136,
            "Expected ~50% alpha, got ${AndroidColor.alpha(tintedPixel)}",
        )
        assertTrue(AndroidColor.blue(tintedPixel) > 200, "Expected blue tint, got $tintedPixel")
        assertTrue(AndroidColor.red(tintedPixel) < 10, "Expected red channel ~0, got $tintedPixel")

        val plain = renderPainterToBitmap(plainPainter, width = 20, height = 20, density = 1f)
        assertEquals(AndroidColor.WHITE, plain.getPixel(10, 10))
    }

    @Test
    fun computeCappedBitmapSizeBoundsEdgeAndPixelBudget() {
        // Normal phone full-screen passes through 1:1.
        assertEquals(IntSize(1080, 2400), computeCappedBitmapSize(1080, 2400))

        // Huge square is capped by pixel budget (2048x2048).
        assertEquals(IntSize(2048, 2048), computeCappedBitmapSize(8000, 8000))

        // Wide banner is capped by max edge (4096).
        val cappedWide = computeCappedBitmapSize(10000, 100)
        assertEquals(4096, cappedWide.width)
        assertTrue(cappedWide.height in 40..41)

        // Extreme aspect ratio respects both maxEdge and maxPixels even when short edge clamps to
        // 1.
        assertEquals(IntSize(4096, 1), computeCappedBitmapSize(1_000_000, 1))
        assertEquals(
            IntSize(16, 1),
            computeCappedBitmapSize(10_000, 1, maxEdge = 4096, maxPixels = 16),
        )
        assertEquals(
            IntSize(1, 16),
            computeCappedBitmapSize(1, 10_000, maxEdge = 4096, maxPixels = 16),
        )
    }

    @Test
    fun painterCapsAllocatedCacheBitmapForOversizedViewBoxSvg() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
                <rect width="10" height="10" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))
        val painter =
            AndroidSvgPainter(
                document = doc,
                density = Density(1f),
                maxBitmapEdge = 32,
                maxBitmapPixels = 32L * 32L,
            )

        val rendered = renderPainterToBitmap(painter, width = 64, height = 64, density = 1f)
        assertEquals(IntSize(32, 32), painter.drawCache.cachedSize)
        assertNotNull(painter.drawCache.cachedImage)
        assertEquals(32, painter.drawCache.cachedImage?.width)
        assertEquals(32, painter.drawCache.cachedImage?.height)
        assertEquals(AndroidColor.RED, rendered.getPixel(60, 60))
    }

    @Test
    fun nonPositiveDrawSizeIsIgnoredSafely() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
                <rect width="10" height="10" fill="#FF0000"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))
        val painter = AndroidSvgPainter(doc, Density(1f))

        val target = ImageBitmap(10, 10)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(0f, 10f)) {
            with(painter) { draw(Size(0f, 10f)) }
        }
        assertNull(painter.drawCache.cachedImage)
    }

    @Test
    fun concurrentRenderToCanvasAcrossDifferentSizesPreservesGradientGeometry() {
        val svgXml =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%">
                <defs>
                    <linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stop-color="#FF0000"/>
                        <stop offset="100%" stop-color="#0000FF"/>
                    </linearGradient>
                </defs>
                <rect width="100%" height="100%" fill="url(#g)"/>
            </svg>
            """
                .trimIndent()
        val doc = AndroidSvgDecoder.decode(svgXml.toByteArray(StandardCharsets.UTF_8))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                (1..16).map { idx ->
                    Callable {
                        val width = if (idx % 2 == 0) 100 else 20
                        val height = 20
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        doc.renderToCanvas(canvas, width.toFloat(), height.toFloat())
                        val quarterFromRight = bmp.getPixel((width * 3) / 4, height / 2)
                        assertTrue(
                            AndroidColor.red(quarterFromRight) > 40 &&
                                AndroidColor.blue(quarterFromRight) > 150,
                            "Expected gradient blend at 75% width=$width, got #${Integer.toHexString(quarterFromRight)}",
                        )
                        quarterFromRight
                    }
                }
            val results = executor.invokeAll(tasks).map { it.get() }
            assertEquals(16, results.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @OptIn(InternalResourceApi::class)
    @Test
    fun resourceIdentityKeyPreventsCrossModuleIdCollision() = runTest {
        val env = getSystemResourceEnvironment()
        val moduleAResource =
            DrawableResource(
                id = "drawable:icon",
                items =
                    setOf(
                        ResourceItem(
                            emptySet(),
                            "composeResources/moduleA/drawable/icon.svg",
                            -1,
                            -1,
                        ),
                    ),
            )
        val moduleBResource =
            DrawableResource(
                id = "drawable:icon",
                items =
                    setOf(
                        ResourceItem(
                            emptySet(),
                            "composeResources/moduleB/drawable/icon.svg",
                            -1,
                            -1,
                        ),
                    ),
            )

        assertEquals(moduleAResource, moduleBResource)

        val keyA1 = ResourceIdentityKey(moduleAResource, env)
        val keyA2 = ResourceIdentityKey(moduleAResource, env)
        val keyB = ResourceIdentityKey(moduleBResource, env)

        assertEquals(keyA1, keyA2)
        assertEquals(keyA1.hashCode(), keyA2.hashCode())
        assertNotEquals(keyA1, keyB)

        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<ResourceIdentityKey, String>(dispatcher = dispatcher)
        val loadedA = cache.getOrLoad(keyA1) { "moduleA" }
        val loadedB = cache.getOrLoad(keyB) { "moduleB" }
        val cachedA = cache.getOrLoad(keyA2) { "unexpected" }

        assertEquals("moduleA", loadedA)
        assertEquals("moduleB", loadedB)
        assertSame(loadedA, cachedA)
    }

    private fun renderPainterToBitmap(
        painter: AndroidSvgPainter,
        width: Int,
        height: Int,
        density: Float,
        alpha: Float = 1f,
        colorFilter: ColorFilter? = null,
    ): Bitmap {
        val imageBitmap = ImageBitmap(width, height)
        val drawScope = CanvasDrawScope()
        drawScope.draw(
            density = Density(density),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(imageBitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            with(painter) {
                draw(
                    size = Size(width.toFloat(), height.toFloat()),
                    alpha = alpha,
                    colorFilter = colorFilter,
                )
            }
        }
        return imageBitmap.asAndroidBitmap()
    }
}
