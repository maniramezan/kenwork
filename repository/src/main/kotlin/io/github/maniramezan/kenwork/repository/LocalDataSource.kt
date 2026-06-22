package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.Cache
import io.github.maniramezan.kenwork.cache.CacheChange
import io.github.maniramezan.kenwork.cache.CacheEntry
import io.github.maniramezan.kenwork.cache.CacheKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The local persistence side of a [Repository]. Mirrors SwiftyNetwork's `LocalDataSource`.
 *
 * @param E the stored entity type.
 */
public interface LocalDataSource<E : Any> {
    /** Returns the entity stored for [key], or `null`. */
    public suspend fun read(key: CacheKey): E?

    /** Stores [entity] for [key]. */
    public suspend fun write(
        entity: E,
        key: CacheKey,
    )

    /** Removes the entity stored for [key]. */
    public suspend fun remove(key: CacheKey)

    /** Removes all stored entities. */
    public suspend fun removeAll()

    /** Returns the epoch-millisecond timestamp recorded for [key], or `null`. */
    public suspend fun timestamp(key: CacheKey): Long?

    /**
     * Atomically returns the entity and its timestamp for [key], or `null` if absent.
     *
     * The default composes [read] and [timestamp] and is not atomic; sources backed by a
     * concurrency-safe store should override it.
     */
    public suspend fun entry(key: CacheKey): CacheEntry<E>? {
        val entity = read(key) ?: return null
        return timestamp(key)?.let { CacheEntry(entity, it) }
    }

    /**
     * A hot stream of [CacheChange]s for the underlying store, enabling reactive reads via
     * [Repository.stream]. The default is an empty flow (non-reactive source).
     */
    public fun changes(): Flow<CacheChange> = emptyFlow()
}

/**
 * A [LocalDataSource] backed by a kenwork [Cache]. Mirrors SwiftyNetwork's
 * `CacheBasedLocalDataSource`.
 */
public class CacheBasedLocalDataSource<E : Any>(
    private val cache: Cache<E>,
) : LocalDataSource<E> {
    override suspend fun read(key: CacheKey): E? = cache.value(key)

    override suspend fun entry(key: CacheKey): CacheEntry<E>? = cache.entry(key)

    override suspend fun write(
        entity: E,
        key: CacheKey,
    ): Unit = cache.setValue(entity, key)

    override suspend fun remove(key: CacheKey): Unit = cache.removeValue(key)

    override suspend fun removeAll(): Unit = cache.removeAll()

    override suspend fun timestamp(key: CacheKey): Long? = cache.timestamp(key)

    override fun changes(): Flow<CacheChange> = cache.changes()
}
