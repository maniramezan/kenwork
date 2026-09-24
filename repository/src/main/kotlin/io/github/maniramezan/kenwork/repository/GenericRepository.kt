package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheChange
import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.shouldUseCachedData
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *   created and [close] cancels it. [close] never cancels a scope you supplied, and a failed load
 *   never cancels it either — each load runs under its own supervisor, and its failure is
 *   delivered only to the callers awaiting it.
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
    ): Flow<E> = observe(endpoint, cacheKey, policy, emitRemovals = false).filterNotNull()

    override fun streamOrNull(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
        policy: CachePolicy,
    ): Flow<E?> = observe(endpoint, cacheKey, policy, emitRemovals = true)

    /**
     * Cancels in-flight coalesced loads and releases the internal coroutine scope. No-op when a
     * caller-supplied scope was provided — cancel that scope yourself.
     */
    public fun close() {
        if (ownsScope) coalesceScope.cancel()
    }

    /**
     * Emits the initial [fetch], then the re-read local value after every change affecting
     * [cacheKey]. With [emitRemovals] a removal/clear emits `null`; otherwise it is skipped.
     */
    private fun observe(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
        policy: CachePolicy,
        emitRemovals: Boolean,
    ): Flow<E?> =
        channelFlow {
            val sendLock = Mutex()
            var changeEmitted = false
            // Subscribe to changes *before* the initial fetch so a mutation landing between the
            // fetch and the subscription can't be missed (a fetch-then-subscribe ordering would
            // leave a narrow gap where such a change is silently dropped).
            val subscribed = CompletableDeferred<Unit>()
            val changesJob =
                launch {
                    localDataSource
                        .changes()
                        .filter { it.affects(cacheKey) }
                        .onStart { subscribed.complete(Unit) }
                        .collect {
                            val current = localDataSource.read(cacheKey)
                            if (current != null || emitRemovals) {
                                sendLock.withLock {
                                    changeEmitted = true
                                    send(current)
                                }
                            }
                        }
                }
            subscribed.await()
            val initial = fetch(endpoint, cacheKey, policy)
            // A change emitted while fetch() ran was read *after* that change landed, so it is at
            // least as fresh as `initial` (including fetch's own write-through). Sending `initial`
            // after it could leave a stale value as the stream's latest emission.
            sendLock.withLock { if (!changeEmitted) send(initial) }
            changesJob.join()
        }.distinctUntilChanged()

    private suspend fun loadAndStore(
        endpoint: NetworkEndpoint,
        cacheKey: CacheKey,
    ): E {
        val deferred =
            inFlightMutex.withLock {
                inFlight[cacheKey]?.let { return@withLock it }
                // Each load gets its own supervisor so a failure reaches only the callers awaiting
                // it, never the (possibly caller-owned) parent scope. Completing the supervisor right
                // away lets it finish with the load instead of lingering as an active child.
                val loadJob = SupervisorJob(coalesceScope.coroutineContext[Job])
                lateinit var started: Deferred<E>
                started =
                    coalesceScope.async(loadJob) {
                        try {
                            val entity: E = networkDataSource.request(endpoint, null, null, responseType)
                            localDataSource.write(entity, cacheKey)
                            entity
                        } finally {
                            inFlightMutex.withLock { if (inFlight[cacheKey] === started) inFlight.remove(cacheKey) }
                        }
                    }
                loadJob.complete()
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
