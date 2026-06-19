package io.github.maniramezan.kemwork.repository

import io.github.maniramezan.kemwork.cache.Cache
import io.github.maniramezan.kemwork.cache.CacheKey

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
}

/**
 * A [LocalDataSource] backed by a kemwork [Cache]. Mirrors SwiftyNetwork's
 * `CacheBasedLocalDataSource`.
 */
public class CacheBasedLocalDataSource<E : Any>(
    private val cache: Cache<E>,
) : LocalDataSource<E> {
    override suspend fun read(key: CacheKey): E? = cache.value(key)

    override suspend fun write(
        entity: E,
        key: CacheKey,
    ): Unit = cache.setValue(entity, key)

    override suspend fun remove(key: CacheKey): Unit = cache.removeValue(key)

    override suspend fun removeAll(): Unit = cache.removeAll()

    override suspend fun timestamp(key: CacheKey): Long? = cache.timestamp(key)
}
