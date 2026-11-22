package io.github.loshine.mrsvg

import android.graphics.Canvas
import androidx.compose.ui.geometry.Size
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Decoded SVG document abstraction that isolates the underlying Android SVG rendering engine. */
internal interface SvgDocument {
    /**
     * Unscaled document size in CSS pixels, [Size.Zero] for explicit zero-dimension SVGs, or
     * [Size.Unspecified] if neither positive document dimensions nor a positive `viewBox` are
     * present.
     */
    val defaultSizePx: Size

    /**
     * Whether this document can produce any visible output. Explicit zero-dimension SVGs and SVGs
     * with a zero/negative `viewBox` return `false` so painters can skip bitmap allocation and
     * compositing completely.
     */
    val isRenderable: Boolean
        get() = true

    /**
     * Renders the document into [canvas] filling the logical viewport `(0, 0, width, height)`.
     *
     * Implementations must be safe to call concurrently across threads and must not leak mutable
     * layout/bounding-box state across different viewport sizes.
     */
    fun renderToCanvas(canvas: Canvas, width: Float, height: Float)
}

/** Decodes raw SVG bytes into an [SvgDocument] backed by AndroidSVG. */
internal object AndroidSvgDecoder {
    fun decode(bytes: ByteArray): SvgDocument {
        val rawBytes = bytes.copyOf()
        val initialSvg = parseSvg(rawBytes)
        return AndroidSvgDocument.create(rawBytes = rawBytes, initialSvg = initialSvg)
    }

    internal fun parseSvg(bytes: ByteArray): SVG {
        return ByteArrayInputStream(bytes).use { stream -> SVG.getFromInputStream(stream) }
    }
}

private class AndroidSvgDocument
private constructor(
    private val rawBytes: ByteArray,
    initialSvg: SVG,
    override val defaultSizePx: Size,
    override val isRenderable: Boolean,
    private val shouldNormalizeToViewport: Boolean,
    private val synthesizedViewBoxSize: Size?,
) : SvgDocument {

    private val renderLock = Any()
    private var cachedSvg: SVG = initialSvg
    private var lastRenderedViewport: Size = Size.Unspecified

    override fun renderToCanvas(canvas: Canvas, width: Float, height: Float) {
        if (!isRenderable || !width.isPositiveFinite() || !height.isPositiveFinite()) {
            return
        }

        val viewport = Size(width, height)
        val options = RenderOptions().viewPort(0f, 0f, width, height)

        // AndroidSVG 1.4 mutates and retains computed element bounding boxes on its parsed DOM
        // tree during rendering. Re-parse from rawBytes when the logical viewport size changes so
        // percentage/objectBoundingBox geometry never reuses stale bounding boxes from a prior
        // size; identical viewport renders reuse the parsed DOM under renderLock.
        synchronized(renderLock) {
            if (lastRenderedViewport != Size.Unspecified && lastRenderedViewport != viewport) {
                cachedSvg =
                    prepareForRendering(
                        svg = AndroidSvgDecoder.parseSvg(rawBytes),
                        shouldNormalizeToViewport = shouldNormalizeToViewport,
                        synthesizedViewBoxSize = synthesizedViewBoxSize,
                    )
            }
            lastRenderedViewport = viewport
            cachedSvg.renderToCanvas(canvas, options)
        }
    }

    companion object {
        fun create(rawBytes: ByteArray, initialSvg: SVG): AndroidSvgDocument {
            val rawWidth = initialSvg.documentWidth
            val rawHeight = initialSvg.documentHeight
            val viewBox = initialSvg.documentViewBox

            val hasDocDimensions = rawWidth.isPositiveFinite() && rawHeight.isPositiveFinite()
            // AndroidSVG's documentWidth/Height returns -1 for missing, zero, and relative units.
            // Only scan the root <svg> tag when positive fixed dimensions were not reported.
            val hasExplicitZeroDimension =
                !hasDocDimensions && hasExplicitZeroRootDimension(rawBytes)
            val hasZeroOrNegativeViewBox =
                viewBox != null &&
                    (!viewBox.width().isPositiveFinite() || !viewBox.height().isPositiveFinite())
            val hasValidViewBox = viewBox != null && !hasZeroOrNegativeViewBox

            val isRenderable = !hasExplicitZeroDimension && !hasZeroOrNegativeViewBox

            val defaultSizePx =
                when {
                    hasExplicitZeroDimension -> Size.Zero
                    hasDocDimensions -> Size(rawWidth, rawHeight)
                    hasValidViewBox -> Size(viewBox.width(), viewBox.height())
                    else -> Size.Unspecified
                }

            val synthesizedViewBoxSize =
                if (isRenderable && viewBox == null && hasDocDimensions) {
                    Size(rawWidth, rawHeight)
                } else {
                    null
                }
            val shouldNormalizeToViewport = isRenderable && hasDocDimensions

            if (isRenderable) {
                prepareForRendering(
                    svg = initialSvg,
                    shouldNormalizeToViewport = shouldNormalizeToViewport,
                    synthesizedViewBoxSize = synthesizedViewBoxSize,
                )
            }

            return AndroidSvgDocument(
                rawBytes = rawBytes,
                initialSvg = initialSvg,
                defaultSizePx = defaultSizePx,
                isRenderable = isRenderable,
                shouldNormalizeToViewport = shouldNormalizeToViewport,
                synthesizedViewBoxSize = synthesizedViewBoxSize,
            )
        }

        private fun prepareForRendering(
            svg: SVG,
            shouldNormalizeToViewport: Boolean,
            synthesizedViewBoxSize: Size?,
        ): SVG {
            if (synthesizedViewBoxSize != null) {
                // Use unscaled document coordinates (NOT multiplied by density) so the SVG
                // scales properly onto any target viewport.
                svg.setDocumentViewBox(
                    0f,
                    0f,
                    synthesizedViewBoxSize.width,
                    synthesizedViewBoxSize.height,
                )
            }
            if (shouldNormalizeToViewport) {
                svg.setDocumentWidth("100%")
                svg.setDocumentHeight("100%")
            }
            return svg
        }

        /**
         * Detects explicit zero root `width`/`height` attributes (e.g. `width="0"`, `height="0PX"`,
         * `width="0%"`), including gzip-compressed SVGs.
         */
        private fun hasExplicitZeroRootDimension(bytes: ByteArray): Boolean {
            return try {
                val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
                val parser = factory.newPullParser()
                openSvgInputStream(bytes).use { stream ->
                    parser.setInput(stream, null)
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            val widthAttr = parser.getAttributeValue(null, "width")
                            val heightAttr = parser.getAttributeValue(null, "height")
                            return isZeroSvgLength(widthAttr) || isZeroSvgLength(heightAttr)
                        }
                        event = parser.next()
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        private fun openSvgInputStream(bytes: ByteArray): InputStream {
            val raw = ByteArrayInputStream(bytes)
            val isGzip =
                bytes.size >= 2 &&
                    ((bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)) ==
                        GZIPInputStream.GZIP_MAGIC
            return if (isGzip) GZIPInputStream(raw) else raw
        }

        private fun isZeroSvgLength(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return false
            val trimmed = raw.trim()
            val match = SVG_LENGTH_REGEX.matchEntire(trimmed) ?: return false
            val value = match.groupValues[1].toFloatOrNull() ?: return false
            return value == 0f
        }

        private val SVG_LENGTH_REGEX =
            Regex(
                """^([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*(?:px|pt|pc|mm|cm|in|em|ex|%)?$""",
                RegexOption.IGNORE_CASE,
            )

        private fun Float.isPositiveFinite(): Boolean = isFinite() && this > 0f
    }
}
