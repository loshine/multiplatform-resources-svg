@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.loshine.mrsvg

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class AsyncCacheTest {

    @Test
    fun getOrLoadCachesValueAndServesSynchronously() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, String>(maxEntries = 4, dispatcher = dispatcher)
        val loads = AtomicInteger(0)

        assertNull(cache.getIfPresent("a"))
        val first =
            cache.getOrLoad("a") {
                loads.incrementAndGet()
                "value-a"
            }
        val second =
            cache.getOrLoad("a") {
                loads.incrementAndGet()
                "should-not-run"
            }

        assertEquals("value-a", first)
        assertEquals("value-a", second)
        assertEquals("value-a", cache.getIfPresent("a"))
        assertEquals(1, loads.get())
    }

    @Test
    fun lruEvictsLeastRecentlyUsedEntry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, Int>(maxEntries = 2, dispatcher = dispatcher)

        cache.getOrLoad("a") { 1 }
        cache.getOrLoad("b") { 2 }
        // Touch "a" so "b" becomes least recently used.
        assertEquals(1, cache.getIfPresent("a"))
        cache.getOrLoad("c") { 3 }

        assertEquals(1, cache.getIfPresent("a"))
        assertNull(cache.getIfPresent("b"))
        assertEquals(3, cache.getIfPresent("c"))
    }

    @Test
    fun concurrentCallersShareSingleInFlightLoad() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, String>(dispatcher = dispatcher)
        val gate = CompletableDeferred<String>()
        val loads = AtomicInteger(0)

        val d1 = async {
            cache.getOrLoad("shared") {
                loads.incrementAndGet()
                gate.await()
            }
        }
        val d2 = async {
            cache.getOrLoad("shared") {
                loads.incrementAndGet()
                gate.await()
            }
        }
        advanceUntilIdle()

        assertEquals(1, loads.get())
        gate.complete("ok")
        assertEquals("ok", d1.await())
        assertEquals("ok", d2.await())
        assertEquals(1, loads.get())
    }

    @Test
    fun cancellingFirstCallerDoesNotCancelSharedLoadForRemainingCaller() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, String>(dispatcher = dispatcher)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val loads = AtomicInteger(0)

        val caller1 = launch {
            cache.getOrLoad("k") {
                loads.incrementAndGet()
                started.complete(Unit)
                gate.await()
            }
        }
        val caller2 = async {
            cache.getOrLoad("k") {
                loads.incrementAndGet()
                gate.await()
            }
        }

        started.await()
        advanceUntilIdle()
        // Cancel the first caller while the shared load is still suspended.
        caller1.cancel()
        advanceUntilIdle()

        gate.complete("survived")
        assertEquals("survived", caller2.await())
        assertEquals("survived", cache.getIfPresent("k"))
        assertEquals(1, loads.get())
    }

    @Test
    fun cancellingAllCallersCancelsInFlightLoadAndAllowsFreshRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, String>(dispatcher = dispatcher)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val loads = AtomicInteger(0)

        val caller = launch {
            cache.getOrLoad("k") {
                loads.incrementAndGet()
                started.complete(Unit)
                try {
                    gate.await()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }

        started.await()
        caller.cancel()
        advanceUntilIdle()
        cancelled.await()
        assertNull(cache.getIfPresent("k"))

        val retryResult =
            cache.getOrLoad("k") {
                loads.incrementAndGet()
                "retried"
            }
        assertEquals("retried", retryResult)
        assertEquals(2, loads.get())
    }

    @Test
    fun failedLoadIsNotCachedAndSubsequentCallRetries() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, String>(dispatcher = dispatcher)
        val attempts = AtomicInteger(0)

        assertFailsWith<IllegalStateException> {
            cache.getOrLoad("flaky") {
                attempts.incrementAndGet()
                error("parse failed")
            }
        }
        assertNull(cache.getIfPresent("flaky"))

        val recovered =
            cache.getOrLoad("flaky") {
                attempts.incrementAndGet()
                "recovered"
            }
        assertEquals("recovered", recovered)
        assertEquals("recovered", cache.getIfPresent("flaky"))
        assertEquals(2, attempts.get())
    }

    @Test
    fun cancelledNonCancellableLoadDoesNotOverwriteNewerCachedValue() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, String>(dispatcher = dispatcher)
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<String>()

        val firstCaller = launch {
            cache.getOrLoad("k") {
                withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    oldRelease.await()
                }
            }
        }

        oldStarted.await()
        firstCaller.cancel()
        advanceUntilIdle()

        val fresh = cache.getOrLoad("k") { "new" }
        assertEquals("new", fresh)

        // Allow the cancelled load's non-cancellable section to finish and return "old".
        oldRelease.complete("old")
        advanceUntilIdle()

        assertEquals("new", cache.getIfPresent("k"))
    }

    @Test
    fun clearInvalidatesInFlightNonCancellableLoadPreventingLateWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = AsyncCache<String, String>(dispatcher = dispatcher)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()

        val caller = launch {
            cache.getOrLoad("k") {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                }
            }
        }

        started.await()
        cache.clear()
        release.complete("stale")
        advanceUntilIdle()
        caller.cancel()

        assertNull(cache.getIfPresent("k"))
    }
}
