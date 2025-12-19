package io.github.loshine.svg

import android.graphics.Canvas
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.rememberResourceEnvironment

/** Empty image bitmap used as a placeholder during loading */
private val emptyImageBitmap: ImageBitmap by lazy { ImageBitmap(1, 1) }

/** Empty SVG painter used as the default state during loading */
private val emptySvgPainter: Painter by lazy { BitmapPainter(emptyImageBitmap) }

/** Image cache to avoid re-parsing the same SVG resource */
private val imageCache = AsyncCache<String, SvgCache>()

/**
 * Cache wrapper for SVG painters.
 *
 * @property painter The cached [Painter] instance
 */
private class SvgCache(val painter: Painter)

/**
 * Asynchronous cache implementation similar to Compose Multiplatform Resources.
 *
 * This cache ensures that the same resource is only loaded once, even if multiple coroutines
 * request it simultaneously. Uses a mutex to synchronize access and deferred values to share the
 * loading result.
 *
 * @param K The type of cache keys
 * @param V The type of cached values
 */
internal class AsyncCache<K, V> {
    private val mutex = Mutex()
    private val cache = mutableMapOf<K, Deferred<V>>()

    /**
     * Gets a cached value or loads it if not present.
     *
     * @param key The cache key
     * @param load Suspending function to load the value if not cached
     * @return The cached or newly loaded value
     */
    suspend fun getOrLoad(key: K, load: suspend () -> V): V = coroutineScope {
        val deferred =
            mutex.withLock {
                var cached = cache[key]
                if (cached == null || cached.isCancelled) {
                    // LAZY - to free the mutex lock as fast as possible
                    cached = async(start = CoroutineStart.LAZY) { load() }
                    cache[key] = cached
                }
                cached
            }
        deferred.await()
    }

    /**
     * Clears all cached entries.
     *
     * Note: This method is intended for testing purposes only.
     */
    // @TestOnly
    fun clear() {
        cache.clear()
    }
}

/**
 * Android implementation of [rememberSvgPainter].
 *
 * Uses AndroidSVG library to parse and render SVG resources. The implementation:
 * - Loads SVG bytes from the drawable resource
 * - Parses the SVG using AndroidSVG library
 * - Renders it to a bitmap with proper size handling
 * - Caches the result to avoid re-parsing
 *
 * @param resource The SVG drawable resource
 * @return A [Painter] that renders the SVG content
 */
@Composable
actual fun rememberSvgPainter(resource: DrawableResource): Painter {
    val density = LocalDensity.current
    val svgPainter by
        rememberResourceState(resource, density, { emptySvgPainter }) { environment ->
            val path = getDrawableResourceBytes(environment, resource).toPath()
            // Default Dispatcher decode svg
            val cached =
                withContext(Dispatchers.Default) {
                    imageCache.getOrLoad(path) {
                        SvgCache(BitmapPainter(buildSvgImageBitmap(path, density.density)))
                    }
                }
            cached.painter
        }
    return svgPainter
}

/**
 * Remembers a resource state that loads asynchronously.
 *
 * @param key1 First key for remembering state
 * @param key2 Second key for remembering state
 * @param getDefault Function that provides a default value while loading
 * @param block Suspending function that loads the actual value
 * @return A [State] containing the loaded value
 */
@Composable
private fun <T> rememberResourceState(
    key1: Any,
    key2: Any,
    getDefault: () -> T,
    block: suspend (ResourceEnvironment) -> T,
): State<T> {
    val environment = rememberResourceEnvironment()
    val scope = rememberCoroutineScope()
    return remember(key1, key2, environment) {
        val mutableState = mutableStateOf(getDefault())
        scope.launch { mutableState.value = block(environment) }
        mutableState
    }
}

/**
 * Converts byte array to a string path representation.
 *
 * @return The string representation of the byte array
 */
private fun ByteArray.toPath(): String = String(this)

/**
 * Builds an [ImageBitmap] from SVG content.
 *
 * This function:
 * - Parses the SVG string using AndroidSVG
 * - Extracts or calculates the appropriate size from viewBox or document dimensions
 * - Creates a bitmap with the calculated size
 * - Renders the SVG to the bitmap canvas
 *
 * @param path The SVG content as a string
 * @return An [ImageBitmap] containing the rendered SVG
 */
private fun buildSvgImageBitmap(path: String, density: Float): ImageBitmap {
    val svg = SVG.getFromString(path)
    val viewBox = svg.documentViewBox
    //    val imageSize =
    //        if (viewBox != null) {
    //            Size(viewBox.width() * density, viewBox.height() * density)
    //        } else {
    //            Size(svg.documentWidth * density, svg.documentHeight * density)
    //        }
    val imageSize = Size(svg.documentWidth * density, svg.documentHeight * density)
    println("imageSize: ${svg.documentWidth}, ${svg.documentHeight}")

    if (viewBox == null && !imageSize.isEmpty()) {
        svg.setDocumentViewBox(0f, 0f, imageSize.width, imageSize.height)
    }

    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")

    val width = imageSize.width.toInt().coerceAtLeast(1)
    val height = imageSize.height.toInt().coerceAtLeast(1)

    val bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createBitmap(width, height, hasAlpha = true)
        } else {
            createBitmap(width, height)
        }

    val canvas = Canvas(bitmap)
    svg.renderToCanvas(canvas, RenderOptions())

    return bitmap.asImageBitmap()
}
