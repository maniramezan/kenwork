package io.github.maniramezan.kemwork.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory [TimestampedCache] with optional LRU eviction.
 *
 * Mirrors SwiftyNetwork's `InMemoryCache`. State is guarded by a [Mutex] (the Kotlin analog of
 * the Swift actor), and storage uses an access-ordered [LinkedHashMap] so reads promote entries
 * to most-recently-used. When [maxSize] is set, the least-recently-used entries are evicted once
 * the limit is exceeded.
 *
 * @param maxSize maximum number of entries to retain, or `null` for unbounded.
 * @param currentTimeMillis time source, injectable for deterministic tests.
 */
public class InMemoryCache<V : Any>(
    private val maxSize: Int? = null,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : TimestampedCache<V> {
    private data class Entry<V>(
        val value: V,
        val timestamp: Long,
    )

    private val mutex = Mutex()
    private val storage = LinkedHashMap<CacheKey, Entry<V>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    override suspend fun value(key: CacheKey): V? = mutex.withLock { storage[key]?.value }

    override suspend fun setValue(
        value: V,
        key: CacheKey,
    ): Unit = setValue(value, key, currentTimeMillis())

    override suspend fun setValue(
        value: V,
        key: CacheKey,
        timestamp: Long,
    ): Unit =
        mutex.withLock {
            storage[key] = Entry(value, timestamp)
            evictIfNeeded()
        }

    override suspend fun removeValue(key: CacheKey) {
        mutex.withLock { storage.remove(key) }
    }

    override suspend fun removeAll() {
        mutex.withLock { storage.clear() }
    }

    override suspend fun timestamp(key: CacheKey): Long? = mutex.withLock { storage[key]?.timestamp }

    /** Removes entries older than [maxAgeMillis] relative to the current time. */
    public suspend fun removeExpiredEntries(maxAgeMillis: Long) {
        mutex.withLock {
            val now = currentTimeMillis()
            storage.entries.removeAll { now - it.value.timestamp > maxAgeMillis }
        }
    }

    /** The current number of entries. */
    public suspend fun count(): Int = mutex.withLock { storage.size }

    private fun evictIfNeeded() {
        val limit = maxSize ?: return
        while (storage.size > limit) {
            val eldest = storage.entries.iterator().next()
            storage.remove(eldest.key)
        }
    }

    private companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
