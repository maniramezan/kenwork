package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A two-tier cache composing a fast [memory] layer over an optional [persistent] layer.
 *
 * Mirrors SwiftyNetwork's `LayeredCache`: reads consult memory first, fall back to the
 * persistent layer, and **promote** a persistent hit back into memory — preserving the original
 * timestamp when the memory layer is a [TimestampedCache]. Writes go through to both layers.
 *
 * [changes] reflects writes and removals against this layered view; read-time promotions are not
 * reported, since the logical value did not change. A write can also evict other keys from the
 * backing caches, so observers must re-read their keys on every notification from this view.
 *
 * Operations on the same key through this instance are serialized across both tiers, so a slow
 * disk read cannot promote stale data over a newer write or resurrect a removed entry. Locks are
 * striped by key, so a slow disk read for one key does not hold up a memory hit for most other
 * keys; [removeAll] takes every stripe. Access the backing caches through this instance to retain
 * that guarantee; independently mutating them bypasses its locks.
 *
 * @param memory the fast, volatile layer.
 * @param persistent the durable layer, or `null` for a memory-only cache.
 */
public class LayeredCache<V : Any>(
    private val memory: Cache<V>,
    private val persistent: Cache<V>? = null,
) : Cache<V> {
    private val stripes = Array(LOCK_STRIPES) { Mutex() }
    private val changeFlow =
        MutableSharedFlow<CacheChange>(
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun changes(): Flow<CacheChange> = changeFlow.asSharedFlow()

    override suspend fun value(key: CacheKey): V? = entry(key)?.value

    override suspend fun entry(key: CacheKey): CacheEntry<V>? =
        lockFor(key).withLock {
            memory.entry(key)?.let { return@withLock it }
            val promoted = persistent?.entry(key)
            if (promoted != null) {
                val mem = memory
                if (mem is TimestampedCache) {
                    mem.setValue(promoted.value, key, promoted.timestamp)
                } else {
                    memory.setValue(promoted.value, key)
                }
            }
            promoted
        }

    override suspend fun setValue(
        value: V,
        key: CacheKey,
    ): Unit =
        lockFor(key).withLock {
            // Durable layer first: if it throws (disk full, IO error), memory is left untouched rather
            // than diverging ahead of a write that never actually persisted.
            persistent?.setValue(value, key)
            memory.setValue(value, key)
            changeFlow.tryEmit(CacheChange.Updated(key))
        }

    override suspend fun removeValue(key: CacheKey): Unit =
        lockFor(key).withLock {
            persistent?.removeValue(key)
            memory.removeValue(key)
            changeFlow.tryEmit(CacheChange.Removed(key))
        }

    override suspend fun removeAll(): Unit =
        withAllStripes {
            persistent?.removeAll()
            memory.removeAll()
            changeFlow.tryEmit(CacheChange.Cleared)
        }

    override suspend fun timestamp(key: CacheKey): Long? = lockFor(key).withLock { memory.timestamp(key) ?: persistent?.timestamp(key) }

    private fun lockFor(key: CacheKey): Mutex = stripes[Math.floorMod(key.hashCode(), LOCK_STRIPES)]

    /** Runs [block] holding every stripe, acquired in index order so two callers cannot deadlock. */
    private suspend fun withAllStripes(block: suspend () -> Unit) {
        val acquired = ArrayList<Mutex>(LOCK_STRIPES)
        try {
            for (stripe in stripes) {
                stripe.lock()
                acquired += stripe
            }
            block()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    private companion object {
        private const val CHANGE_BUFFER_CAPACITY = 64
        private const val LOCK_STRIPES = 16
    }
}
