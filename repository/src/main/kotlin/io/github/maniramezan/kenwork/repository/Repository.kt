package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    /**
     * Observes the entity for [endpoint]/[cacheKey] reactively: emits the result of an initial
     * [fetch], then re-emits whenever the underlying local store reports a change for [cacheKey].
     *
     * The default implementation is non-reactive (it emits a single [fetch]); [GenericRepository]
     * overrides it to follow [LocalDataSource.changes].
     */
    public fun stream(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
        policy: CachePolicy = CachePolicy.Default,
    ): Flow<E> = flow { emit(fetch(endpoint, cacheKey, policy)) }

    /**
     * Observes the entity like [stream], also emitting `null` when its local value is removed.
     *
     * Use this when consumers must clear displayed state after an explicit removal, cache expiry,
     * or a full local-store clear.
     */
    public fun streamOrNull(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
        policy: CachePolicy = CachePolicy.Default,
    ): Flow<E?> = flow { emit(fetch(endpoint, cacheKey, policy)) }
}

// Kept in this file (not GenericRepository.kt): moving a public top-level function changes its JVM
// facade class (`RepositoryKt`), which is part of the published ABI.

/** Reified factory capturing the response [io.ktor.util.reflect.TypeInfo] for [GenericRepository]. */
public inline fun <reified E : Any> GenericRepository(
    networkDataSource: NetworkDataSource,
    localDataSource: LocalDataSource<E>,
    noinline currentTimeMillis: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
): GenericRepository<E> = GenericRepository(networkDataSource, localDataSource, typeInfo<E>(), currentTimeMillis, scope)
