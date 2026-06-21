package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheChange
import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.shouldUseCachedData
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
}

/**
 * The default [Repository]: reads/writes a [LocalDataSource] and falls through to the network per
 * [CachePolicy], always **writing through** to local storage on a successful network load.
 * Mirrors SwiftyNetwork's `GenericRepository`.
 *
 * Concurrent loads for the same [CacheKey] are **coalesced**: a burst of callers that all miss the
 * cache triggers a single network request whose result they all await — the same single-flight
 * pattern the network layer uses for token refresh.
 *
 * @param scope coroutine scope hosting the coalesced loads. Pass your own to tie loads to a
 *   lifecycle you control; when omitted, an internal `SupervisorJob` on [Dispatchers.Default] is
 *   created and [close] cancels it. [close] never cancels a scope you supplied.
 *
 * Prefer the reified `GenericRepository(networkDataSource, localDataSource)` factory below, which
 * captures [responseType] for you.
 */
public class GenericRepository<E : Any>(
    private val networkDataSource: NetworkDataSource,
    private val localDataSource: LocalDataSource<E>,
    private val responseType: TypeInfo,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
) : Repository<E> {
    private val ownsScope: Boolean = scope == null
    private val coalesceScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlightMutex = Mutex()
    private val inFlight = HashMap<CacheKey, Deferred<E>>()

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
                val cached = localDataSource.entry(cacheKey)
                if (cached != null &&
                    policy.shouldUseCachedData(currentTimeMillis() - cached.timestamp)
                ) {
                    cached.value
                } else {
                    loadAndStore(endpoint, cacheKey)
                }
            }
        }

    override fun stream(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
        policy: CachePolicy,
    ): Flow<E> =
        flow {
            emit(fetch(endpoint, cacheKey, policy))
            localDataSource
                .changes()
                .filter { it.affects(cacheKey) }
                .collect { localDataSource.read(cacheKey)?.let { value -> emit(value) } }
        }.distinctUntilChanged()

    /**
     * Cancels in-flight coalesced loads and releases the internal coroutine scope. No-op when a
     * caller-supplied scope was provided — cancel that scope yourself.
     */
    public fun close() {
        if (ownsScope) coalesceScope.cancel()
    }

    private suspend fun loadAndStore(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
    ): E {
        val deferred =
            inFlightMutex.withLock {
                inFlight[cacheKey]?.let { return@withLock it }
                lateinit var started: Deferred<E>
                started =
                    coalesceScope.async {
                        try {
                            val entity: E = networkDataSource.request(endpoint, null, null, responseType)
                            localDataSource.write(entity, cacheKey)
                            entity
                        } finally {
                            inFlightMutex.withLock { if (inFlight[cacheKey] === started) inFlight.remove(cacheKey) }
                        }
                    }
                inFlight[cacheKey] = started
                started
            }
        return deferred.await()
    }
}

private fun CacheChange.affects(key: CacheKey): Boolean =
    when (this) {
        is CacheChange.Updated -> this.key == key
        is CacheChange.Removed -> this.key == key
        CacheChange.Cleared -> true
    }

/** Reified factory capturing the response [TypeInfo] for [GenericRepository]. */
public inline fun <reified E : Any> GenericRepository(
    networkDataSource: NetworkDataSource,
    localDataSource: LocalDataSource<E>,
    noinline currentTimeMillis: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
): GenericRepository<E> = GenericRepository(networkDataSource, localDataSource, typeInfo<E>(), currentTimeMillis, scope)
