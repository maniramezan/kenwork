package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Mutations are published to [changes] so reactive consumers can observe the cache; reads (which
 * only reorder for LRU) do not emit.
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

    private val changeFlow =
        MutableSharedFlow<CacheChange>(
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun changes(): Flow<CacheChange> = changeFlow.asSharedFlow()

    override suspend fun value(key: CacheKey): V? = mutex.withLock { storage[key]?.value }

    override suspend fun entry(key: CacheKey): CacheEntry<V>? = mutex.withLock { storage[key]?.let { CacheEntry(it.value, it.timestamp) } }

    override suspend fun setValue(
        value: V,
        key: CacheKey,
    ): Unit = setValue(value, key, currentTimeMillis())

    override suspend fun setValue(
        value: V,
        key: CacheKey,
        timestamp: Long,
    ) {
        val evicted =
            mutex.withLock {
                storage[key] = Entry(value, timestamp)
                evictIfNeeded()
            }
        changeFlow.tryEmit(CacheChange.Updated(key))
        evicted.forEach { changeFlow.tryEmit(CacheChange.Removed(it)) }
    }

    override suspend fun removeValue(key: CacheKey) {
        val removed = mutex.withLock { storage.remove(key) != null }
        if (removed) changeFlow.tryEmit(CacheChange.Removed(key))
    }

    override suspend fun removeAll() {
        mutex.withLock { storage.clear() }
        changeFlow.tryEmit(CacheChange.Cleared)
    }

    override suspend fun timestamp(key: CacheKey): Long? = mutex.withLock { storage[key]?.timestamp }

    /** Removes entries older than [maxAgeMillis] relative to the current time. */
    public suspend fun removeExpiredEntries(maxAgeMillis: Long) {
        val expired =
            mutex.withLock {
                val now = currentTimeMillis()
                val keys = storage.entries.filter { now - it.value.timestamp > maxAgeMillis }.map { it.key }
                keys.forEach { storage.remove(it) }
                keys
            }
        expired.forEach { changeFlow.tryEmit(CacheChange.Removed(it)) }
    }

    /** The current number of entries. */
    public suspend fun count(): Int = mutex.withLock { storage.size }

    /** Evicts least-recently-used entries past [maxSize] and returns the evicted keys. */
    private fun evictIfNeeded(): List<CacheKey> {
        val limit = maxSize ?: return emptyList()
        val evicted = mutableListOf<CacheKey>()
        while (storage.size > limit) {
            val eldest = storage.entries.iterator().next()
            storage.remove(eldest.key)
            evicted += eldest.key
        }
        return evicted
    }

    private companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val CHANGE_BUFFER_CAPACITY = 64
    }
}
