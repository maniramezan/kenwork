package io.github.maniramezan.kenwork.cache

/**
 * An asynchronous key-value cache.
 *
 * The Kotlin counterpart of SwiftyNetwork's `Cache` protocol: all operations are `suspend`
 * functions and implementations are expected to be safe for concurrent use (e.g. guarded by a
 * [kotlinx.coroutines.sync.Mutex]). Timestamps are epoch milliseconds.
 *
 * @param V the cached value type.
 */
public interface Cache<V : Any> {
    /** Returns the value stored for [key], or `null` if absent. */
    public suspend fun value(key: CacheKey): V?

    /** Stores [value] for [key], stamping it with the current time. */
    public suspend fun setValue(
        value: V,
        key: CacheKey,
    )

    /** Removes any value stored for [key]. */
    public suspend fun removeValue(key: CacheKey)

    /** Removes every entry. */
    public suspend fun removeAll()

    /** Returns the epoch-millisecond timestamp recorded for [key], or `null` if absent. */
    public suspend fun timestamp(key: CacheKey): Long?
}

/**
 * A [Cache] that lets callers supply an explicit timestamp when writing, so values promoted
 * between cache layers can keep their original age. Mirrors SwiftyNetwork's `TimestampedCache`.
 */
public interface TimestampedCache<V : Any> : Cache<V> {
    /** Stores [value] for [key] stamped with the given epoch-millisecond [timestamp]. */
    public suspend fun setValue(
        value: V,
        key: CacheKey,
        timestamp: Long,
    )
}

/**
 * Marker interface for caches backed by durable storage (disk, database). Mirrors
 * SwiftyNetwork's `PersistentCache`.
 */
public interface PersistentCache<V : Any> : Cache<V>
