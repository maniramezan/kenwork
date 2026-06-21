package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A two-tier cache composing a fast [memory] layer over an optional [persistent] layer.
 *
 * Mirrors SwiftyNetwork's `LayeredCache`: reads consult memory first, fall back to the
 * persistent layer, and **promote** a persistent hit back into memory — preserving the original
 * timestamp when the memory layer is a [TimestampedCache]. Writes go through to both layers.
 *
 * [changes] reflects writes and removals against this layered view; read-time promotions are not
 * reported, since the logical value did not change.
 *
 * @param memory the fast, volatile layer.
 * @param persistent the durable layer, or `null` for a memory-only cache.
 */
public class LayeredCache<V : Any>(
    private val memory: Cache<V>,
    private val persistent: Cache<V>? = null,
) : Cache<V> {
    private val changeFlow =
        MutableSharedFlow<CacheChange>(
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun changes(): Flow<CacheChange> = changeFlow.asSharedFlow()

    override suspend fun value(key: CacheKey): V? = entry(key)?.value

    override suspend fun entry(key: CacheKey): CacheEntry<V>? {
        memory.entry(key)?.let { return it }
        val promoted = persistent?.entry(key)
        if (promoted != null) {
            val mem = memory
            if (mem is TimestampedCache) {
                mem.setValue(promoted.value, key, promoted.timestamp)
            } else {
                memory.setValue(promoted.value, key)
            }
        }
        return promoted
    }

    override suspend fun setValue(
        value: V,
        key: CacheKey,
    ) {
        memory.setValue(value, key)
        persistent?.setValue(value, key)
        changeFlow.tryEmit(CacheChange.Updated(key))
    }

    override suspend fun removeValue(key: CacheKey) {
        memory.removeValue(key)
        persistent?.removeValue(key)
        changeFlow.tryEmit(CacheChange.Removed(key))
    }

    override suspend fun removeAll() {
        memory.removeAll()
        persistent?.removeAll()
        changeFlow.tryEmit(CacheChange.Cleared)
    }

    override suspend fun timestamp(key: CacheKey): Long? = memory.timestamp(key) ?: persistent?.timestamp(key)

    private companion object {
        private const val CHANGE_BUFFER_CAPACITY = 64
    }
}
