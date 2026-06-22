package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A cached [value] together with the epoch-millisecond [timestamp] it was stored at. */
public data class CacheEntry<V : Any>(
    public val value: V,
    public val timestamp: Long,
)

/**
 * A mutation observed on a [Cache], emitted by [Cache.changes].
 *
 * Lets reactive consumers (e.g. a repository `stream`) re-read only the keys that actually
 * changed instead of polling.
 */
public sealed interface CacheChange {
    /** The value stored for [key] was set or replaced. */
    public data class Updated(
        public val key: CacheKey,
    ) : CacheChange

    /** The value stored for [key] was removed (explicitly or by eviction/expiry). */
    public data class Removed(
        public val key: CacheKey,
    ) : CacheChange

    /** Every entry was removed. */
    public data object Cleared : CacheChange
}

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

    /**
     * Atomically returns the value **and** its timestamp for [key], or `null` if absent.
     *
     * Prefer this over separate [value]/[timestamp] calls when both are needed: a single lookup
     * cannot observe a value and a timestamp from two different writes. The default implementation
     * composes [value] and [timestamp] and is therefore **not** atomic; thread-safe caches should
     * override it.
     */
    public suspend fun entry(key: CacheKey): CacheEntry<V>? {
        val value = value(key) ?: return null
        return timestamp(key)?.let { CacheEntry(value, it) }
    }

    /**
     * A hot stream of [CacheChange]s for this cache. The default is an empty flow, so caches that
     * cannot observe mutations are simply non-reactive; observable caches override this.
     *
     * Delivery is **latest-wins**: under a burst that outruns a slow collector, the oldest change
     * notifications may be dropped, but the most recent one is retained. Consumers that re-read on
     * each event (e.g. `Repository.stream`) therefore always converge to the current value — they
     * may skip intermediate values, never the latest.
     */
    public fun changes(): Flow<CacheChange> = emptyFlow()
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
