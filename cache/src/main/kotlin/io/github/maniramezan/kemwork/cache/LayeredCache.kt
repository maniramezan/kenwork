package io.github.maniramezan.kemwork.cache

/**
 * A two-tier cache composing a fast [memory] layer over an optional [persistent] layer.
 *
 * Mirrors SwiftyNetwork's `LayeredCache`: reads consult memory first, fall back to the
 * persistent layer, and **promote** a persistent hit back into memory — preserving the original
 * timestamp when the memory layer is a [TimestampedCache]. Writes go through to both layers.
 *
 * @param memory the fast, volatile layer.
 * @param persistent the durable layer, or `null` for a memory-only cache.
 */
public class LayeredCache<V : Any>(
    private val memory: Cache<V>,
    private val persistent: Cache<V>? = null,
) : Cache<V> {
    override suspend fun value(key: CacheKey): V? {
        memory.value(key)?.let { return it }
        val promoted = persistent?.value(key)
        if (promoted != null) {
            val timestamp = persistent.timestamp(key)
            val mem = memory
            if (mem is TimestampedCache && timestamp != null) {
                mem.setValue(promoted, key, timestamp)
            } else {
                memory.setValue(promoted, key)
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
    }

    override suspend fun removeValue(key: CacheKey) {
        memory.removeValue(key)
        persistent?.removeValue(key)
    }

    override suspend fun removeAll() {
        memory.removeAll()
        persistent?.removeAll()
    }

    override suspend fun timestamp(key: CacheKey): Long? = memory.timestamp(key) ?: persistent?.timestamp(key)
}
