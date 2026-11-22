package io.github.loshine.mrsvg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Bounded LRU asynchronous cache with deduplicated in-flight requests.
 *
 * Key guarantees:
 * - Bounded memory via an access-ordered LRU map ([maxEntries]).
 * - Fast synchronous lookup ([getIfPresent]) for already-decoded entries.
 * - Independent [SupervisorJob] scope with reference-counted cancellation: if one of multiple
 *   concurrent callers cancels, the shared load keeps running for the remaining callers; only when
 *   the last active listener cancels is the in-flight job cancelled.
 * - Results are published into the LRU only while the request is still the active entry in
 *   [inFlight], preventing late writes from cancelled or cleared loads from overwriting newer
 *   values.
 * - Failures are never stored in the LRU cache and are evicted from in-flight tracking so
 *   subsequent calls can retry.
 */
internal class AsyncCache<K : Any, V : Any>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be > 0, was $maxEntries" }
    }

    private val cacheScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val lru =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > maxEntries
            }
        }
    private val inFlight = mutableMapOf<K, SharedRequest<V>>()

    private class SharedRequest<V>(val deferred: Deferred<V>) {
        var listenersCount: Int = 0
    }

    fun getIfPresent(key: K): V? = synchronized(lock) { lru[key] }

    suspend fun getOrLoad(key: K, load: suspend () -> V): V {
        val request =
            synchronized(lock) {
                lru[key]?.let {
                    return it
                }

                var existing = inFlight[key]
                if (existing == null || existing.deferred.isCancelled) {
                    lateinit var created: SharedRequest<V>
                    val deferred =
                        cacheScope.async(start = CoroutineStart.LAZY) {
                            val value = load()
                            synchronized(lock) {
                                if (inFlight[key] === created) {
                                    lru[key] = value
                                    inFlight.remove(key)
                                }
                            }
                            value
                        }
                    created = SharedRequest(deferred)
                    inFlight[key] = created
                    existing = created
                }
                existing.listenersCount++
                existing
            }

        return try {
            request.deferred.await()
        } finally {
            val toCancel =
                synchronized(lock) {
                    request.listenersCount--
                    val isCurrent = inFlight[key] === request
                    if (request.listenersCount == 0 && request.deferred.isActive) {
                        if (isCurrent) {
                            inFlight.remove(key)
                        }
                        request.deferred
                    } else {
                        if (request.deferred.isCompleted && isCurrent) {
                            inFlight.remove(key)
                        }
                        null
                    }
                }
            toCancel?.cancel()
        }
    }

    fun clear() {
        val toCancel =
            synchronized(lock) {
                lru.clear()
                val pending = inFlight.values.map { it.deferred }
                inFlight.clear()
                pending
            }
        toCancel.forEach { it.cancel() }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 64
    }
}
