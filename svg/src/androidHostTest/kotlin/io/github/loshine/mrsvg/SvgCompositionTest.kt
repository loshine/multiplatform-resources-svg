package io.github.loshine.mrsvg

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceItem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgCompositionTest {

    @Test
    fun switchingCrossModuleResourcesWithIdenticalIdLoadsRealResourcesInRuntimeMode() {
        verifyCrossModuleSwitchAtSameCallSite(isInspectionMode = false)
    }

    @Test
    fun switchingCrossModuleResourcesWithIdenticalIdLoadsRealResourcesInPreviewMode() {
        verifyCrossModuleSwitchAtSameCallSite(isInspectionMode = true)
    }

    @OptIn(InternalResourceApi::class)
    @Test
    fun missingResourceFallsBackToOneByOnePlaceholderPainter() = runBlocking {
        svgDocumentCache.clear()
        try {
            val missingResource =
                DrawableResource(
                    id = "drawable:missing",
                    items =
                        setOf(
                            ResourceItem(
                                emptySet(),
                                "composeResources/missing/drawable/missing.svg",
                                -1,
                                -1,
                            ),
                        ),
                )
            val app = RuntimeEnvironment.getApplication()
            val frameClock =
                object : MonotonicFrameClock {
                    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0L)
                }
            val recomposer = Recomposer(coroutineContext + frameClock)
            val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
            var observed: Painter? = null

            val composition = Composition(NoOpApplier, recomposer)
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalContext provides app,
                        LocalConfiguration provides app.resources.configuration,
                        LocalDensity provides Density(1f),
                        LocalInspectionMode provides true,
                    ) {
                        observed = rememberSvgPainter(missingResource)
                    }
                }
                assertEquals(Size(1f, 1f), observed?.intrinsicSize)
            } finally {
                composition.dispose()
                recomposer.cancel()
                recomposerJob.cancel()
            }
        } finally {
            svgDocumentCache.clear()
        }
    }

    @OptIn(InternalResourceApi::class)
    private fun verifyCrossModuleSwitchAtSameCallSite(isInspectionMode: Boolean) = runBlocking {
        svgDocumentCache.clear()
        try {
            val moduleAResource =
                DrawableResource(
                    id = "drawable:icon",
                    items =
                        setOf(
                            ResourceItem(
                                emptySet(),
                                "composeResources/test_module_a/drawable/icon.svg",
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
                                "composeResources/test_module_b/drawable/icon.svg",
                                -1,
                                -1,
                            ),
                        ),
                )
            assertEquals(moduleAResource, moduleBResource)

            val app = RuntimeEnvironment.getApplication()
            val frameClock =
                object : MonotonicFrameClock {
                    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0L)
                }
            val recomposer = Recomposer(coroutineContext + frameClock)
            val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }

            var useModuleB by mutableStateOf(false)
            val latestPainter = AtomicReference<Painter?>(null)

            val composition = Composition(NoOpApplier, recomposer)
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalContext provides app,
                        LocalConfiguration provides app.resources.configuration,
                        LocalDensity provides Density(1f),
                        LocalInspectionMode provides isInspectionMode,
                    ) {
                        val resource = if (useModuleB) moduleBResource else moduleAResource
                        latestPainter.set(rememberSvgPainter(resource))
                    }
                }

                val painterA = awaitPainterWithIntrinsicSize(Size(20f, 20f)) { latestPainter.get() }

                // Switch at the exact same call site from Module A to Module B.
                useModuleB = true
                Snapshot.sendApplyNotifications()

                val painterB = awaitPainterWithIntrinsicSize(Size(30f, 30f)) { latestPainter.get() }
                assertNotSame(painterA, painterB)
            } finally {
                composition.dispose()
                recomposer.cancel()
                recomposerJob.cancel()
            }
        } finally {
            svgDocumentCache.clear()
        }
    }

    private suspend fun awaitPainterWithIntrinsicSize(
        expected: Size,
        provider: () -> Painter?,
    ): Painter =
        withTimeout(5_000L) {
            while (true) {
                Snapshot.sendApplyNotifications()
                val current = provider()
                if (current != null && current.intrinsicSize == expected) {
                    return@withTimeout current
                }
                withContext(Dispatchers.Default) { delay(10L) }
            }
            error("Unreachable")
        }

    private object NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }
}
