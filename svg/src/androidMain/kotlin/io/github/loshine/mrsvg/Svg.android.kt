package io.github.loshine.mrsvg

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.PreviewContextConfigurationEffect
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.rememberResourceEnvironment

private const val TAG = "MultiplatformSvg"

/**
 * Zero-allocation placeholder painter shown while an SVG resource is loading or fails to decode.
 *
 * Uses a `1×1` intrinsic size to match Compose Multiplatform's default `emptySvgPainter` so
 * unconstrained layouts do not expand to fill their parent during loading or failure.
 */
internal object EmptySvgPainter : Painter() {
    override val intrinsicSize: Size = Size(1f, 1f)

    // Claim alpha and colorFilter as handled so Compose's Painter base class does not allocate an
    // offscreen saveLayer (which could materialize solid pixels for alpha-offset ColorFilters).
    override fun applyAlpha(alpha: Float): Boolean = true

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean = true

    override fun DrawScope.onDraw() = Unit
}

/**
 * Cache key that distinguishes [DrawableResource] instances by reference identity (`===`) while
 * matching [ResourceEnvironment] by value.
 *
 * Compose Multiplatform's `DrawableResource.equals` compares only its short `id` (e.g.
 * `"drawable:icon"`), which can collide across separate Gradle modules. Generated `Res.drawable.*`
 * properties are `by lazy` singletons, so identity matching hits the cache reliably without
 * cross-module collisions.
 */
internal class ResourceIdentityKey(
    val resource: DrawableResource,
    val environment: ResourceEnvironment,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResourceIdentityKey) return false
        return resource === other.resource && environment == other.environment
    }

    override fun hashCode(): Int {
        return 31 * System.identityHashCode(resource) + environment.hashCode()
    }
}

/** Bounded LRU cache storing parsed, density-independent [SvgDocument] instances. */
internal val svgDocumentCache = AsyncCache<ResourceIdentityKey, SvgDocument>()

/**
 * Android implementation of [io.github.loshine.mrsvg.rememberSvgPainter].
 *
 * Loads and caches the parsed [SvgDocument] in a bounded LRU cache keyed by resource identity and
 * [ResourceEnvironment], then remembers a per-composable [AndroidSvgPainter] that rasterizes on
 * demand at the actual draw size.
 *
 * @param resource The SVG drawable resource
 * @return A [Painter] that renders the SVG content
 */
@Composable
public actual fun rememberSvgPainter(resource: DrawableResource): Painter {
    PreviewContextConfigurationEffect()
    val environment = rememberResourceEnvironment()
    val density = LocalDensity.current
    val isInspectionMode = LocalInspectionMode.current

    // Construct the identity key before passing it to remember/LaunchedEffect so Compose compares
    // DrawableResource by reference identity (===) rather than DrawableResource.equals (id only).
    val cacheKey = ResourceIdentityKey(resource = resource, environment = environment)

    val document: SvgDocument? =
        if (isInspectionMode) {
            remember(cacheKey) {
                svgDocumentCache.getIfPresent(cacheKey)
                    ?: try {
                        runBlocking { loadSvgDocument(cacheKey) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logSvgError("Failed to load SVG resource in preview", e)
                        null
                    }
            }
        } else {
            val cached = remember(cacheKey) { svgDocumentCache.getIfPresent(cacheKey) }
            val documentState = remember(cacheKey) { mutableStateOf(cached) }
            if (documentState.value == null) {
                LaunchedEffect(cacheKey) {
                    documentState.value =
                        try {
                            loadSvgDocument(cacheKey)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logSvgError("Failed to load SVG resource", e)
                            null
                        }
                }
            }
            documentState.value
        }

    return remember(document, density) {
        if (document != null) {
            AndroidSvgPainter(document = document, density = density)
        } else {
            EmptySvgPainter
        }
    }
}

private suspend fun loadSvgDocument(key: ResourceIdentityKey): SvgDocument {
    return svgDocumentCache.getOrLoad(key) {
        val bytes =
            withContext(Dispatchers.IO) { getDrawableResourceBytes(key.environment, key.resource) }
        withContext(Dispatchers.Default) { AndroidSvgDecoder.decode(bytes) }
    }
}

internal fun logSvgError(message: String, throwable: Throwable) {
    runCatching { Log.e(TAG, message, throwable) }
}
