package io.github.maniramezan.kemwork.repository

import io.github.maniramezan.kemwork.cache.CacheKey
import io.github.maniramezan.kemwork.cache.CachePolicy
import io.github.maniramezan.kemwork.cache.shouldUseCachedData
import io.github.maniramezan.kemwork.network.NetworkDataSource
import io.github.maniramezan.kemwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo

/**
 * Coordinates a remote [NetworkDataSource] with a [LocalDataSource] under a [CachePolicy].
 * Mirrors SwiftyNetwork's `Repository`.
 */
public interface Repository<E : Any> {
    /**
     * Returns the entity for [endpoint], honoring [policy] against the value cached under
     * [cacheKey].
     */
    public suspend fun fetch(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
        policy: CachePolicy = CachePolicy.Default,
    ): E
}

/**
 * The default [Repository]: reads/writes a [LocalDataSource] and falls through to the network per
 * [CachePolicy], always **writing through** to local storage on a successful network load.
 * Mirrors SwiftyNetwork's `GenericRepository`.
 *
 * Prefer the reified `GenericRepository(networkDataSource, localDataSource)` factory below, which
 * captures [responseType] for you.
 */
public class GenericRepository<E : Any>(
    private val networkDataSource: NetworkDataSource,
    private val localDataSource: LocalDataSource<E>,
    private val responseType: TypeInfo,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : Repository<E> {
    override suspend fun fetch(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
        policy: CachePolicy,
    ): E =
        when (policy) {
            CachePolicy.ReloadIgnoringCache -> loadAndStore(endpoint, cacheKey)
            CachePolicy.ReturnCacheElseLoad ->
                localDataSource.read(cacheKey) ?: loadAndStore(endpoint, cacheKey)
            is CachePolicy.ReturnCacheIfNotExpired -> {
                val cached = localDataSource.read(cacheKey)
                val timestamp = localDataSource.timestamp(cacheKey)
                if (cached != null &&
                    timestamp != null &&
                    policy.shouldUseCachedData(currentTimeMillis() - timestamp)
                ) {
                    cached
                } else {
                    loadAndStore(endpoint, cacheKey)
                }
            }
        }

    private suspend fun loadAndStore(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
    ): E {
        val entity: E = networkDataSource.request(endpoint, null, null, responseType)
        localDataSource.write(entity, cacheKey)
        return entity
    }
}

/** Reified factory capturing the response [TypeInfo] for [GenericRepository]. */
public inline fun <reified E : Any> GenericRepository(
    networkDataSource: NetworkDataSource,
    localDataSource: LocalDataSource<E>,
    noinline currentTimeMillis: () -> Long = System::currentTimeMillis,
): GenericRepository<E> = GenericRepository(networkDataSource, localDataSource, typeInfo<E>(), currentTimeMillis)
