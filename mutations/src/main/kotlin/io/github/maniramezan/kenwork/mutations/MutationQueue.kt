package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.ApiClient
import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.NetworkError
import io.github.maniramezan.kenwork.network.RetryPolicy
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Enqueues "forgivable" mutations (likes, follows, and similar fire-and-forget writes) for
 * background execution with retry, instead of awaiting them inline from a ViewModel.
 *
 * [enqueueMutation]/[enqueue] return immediately — the actual [ApiClient.request] call and any
 * retries run on [scope], which the caller owns (e.g. an app-scoped `CoroutineScope` that outlives
 * any single screen). This is the fix for two problems with calling `apiClient.request(...)`
 * directly for mutations:
 * 1. No queueing: if the process dies mid-flight, the mutation is silently lost.
 * 2. No retry: [DefaultRetryPolicy] deliberately excludes `POST`/`PATCH` by default (retrying a
 *    non-idempotent call risks a duplicate write), so a plain `NetworkClient` never retries them —
 *    correct for most calls, wrong for ones the caller has decided are safe/idempotent-in-effect
 *    to retry (e.g. "set like state to true" is idempotent even though it's a POST).
 *
 * Behavior:
 * - **Coalescing**: enqueueing under a [MutationKey] that already has a pending/retrying mutation
 *   replaces it — including cancelling any in-progress retry backoff — so only the latest desired
 *   state for that key is ever sent. See [MutationKey].
 * - **Retry**: reuses [RetryPolicy] (the same interface [io.github.maniramezan.kenwork.network.NetworkClient]
 *   uses), but as a queue-level (or per-[enqueue]) setting distinct from
 *   [io.github.maniramezan.kenwork.network.NetworkClientConfiguration.retryPolicy] — so opting a
 *   mutation into non-idempotent retry never loosens the client's own default.
 * - **Persistence**: pass a [MutationCodec] to [enqueue] to have the mutation survive in [store]
 *   (see [MutationStore] for durability); omit it for a purely in-process, fire-and-forget mutation.
 * - **Status**: observe [statusFlow] for a key to reflect pending/retrying/succeeded/failed in the UI.
 *
 * @param apiClient executes the underlying HTTP calls.
 * @param scope owns every mutation's execution + retry backoff; mutations outlive the caller's
 *   own scope (e.g. a ViewModel's) as long as this scope is alive.
 * @param store where enqueued mutations (that were given a [MutationCodec]) are persisted.
 * @param defaultRetryPolicy applied to mutations enqueued without an explicit `retryPolicy`.
 *   Defaults to retrying non-idempotent methods too — the whole point of this queue is to make
 *   `POST`/`PATCH` mutations retryable — unlike [DefaultRetryPolicy]'s own conservative default.
 * @param codecs [MutationCodec]s to register upfront, so [restore] can decode their records even
 *   before any matching [enqueue] call runs in this process. Enqueueing with a new codec also
 *   registers it.
 */
public class MutationQueue(
    private val apiClient: ApiClient,
    private val scope: CoroutineScope,
    private val store: MutationStore = InMemoryMutationStore(),
    private val defaultRetryPolicy: RetryPolicy = DefaultRetryPolicy(retryNonIdempotent = true),
    codecs: List<MutationCodec<*>> = emptyList(),
) {
    private val codecsById =
        ConcurrentHashMap<String, MutationCodec<*>>().apply {
            codecs.forEach { put(it.id, it) }
        }
    private val statuses = ConcurrentHashMap<MutationKey, MutableStateFlow<MutationStatus?>>()
    private val workers = ConcurrentHashMap<MutationKey, KeyWorker>()

    /**
     * The current/most recent [MutationStatus] for [key], or `null` if nothing has ever been
     * enqueued under it. Keeps emitting past a terminal [MutationStatus.Succeeded]/[MutationStatus.Failed]
     * until a new mutation is enqueued for the same key.
     */
    public fun statusFlow(key: MutationKey): StateFlow<MutationStatus?> = statusFlowFor(key).asStateFlow()

    /**
     * Enqueues a mutation and returns immediately; [endpoint] (with [body], described by
     * [bodyType]) is executed on [scope] in the background, retried per [retryPolicy] (or
     * [defaultRetryPolicy] when `null`), and coalesced with any other pending mutation sharing
     * [key].
     *
     * Prefer the reified [enqueue] extensions for typed bodies; this is the type-erased core (see
     * [ApiClient.request] for the same pattern).
     *
     * @param codec when non-null, persists the mutation to [store] so it can be replayed via
     *   [restore] after a process restart. Omitted mutations are in-memory only.
     */
    public suspend fun <B : Any> enqueueMutation(
        key: MutationKey,
        endpoint: NetworkEndpoint,
        body: B? = null,
        bodyType: TypeInfo? = null,
        codec: MutationCodec<B>? = null,
        retryPolicy: RetryPolicy? = null,
    ): MutationHandle {
        val id = UUID.randomUUID().toString()
        val mutation = QueuedMutation(id, key, endpoint, body, bodyType)
        val record =
            codec?.let { c ->
                codecsById.putIfAbsent(c.id, c)
                MutationRecord(
                    id = id,
                    key = key.value,
                    codecId = c.id,
                    payload = c.encode(endpoint, body),
                    enqueuedAtMillis = System.currentTimeMillis(),
                )
            }
        record?.let { store.save(it) }

        workerFor(key).submit(Enqueued(mutation, record, retryPolicy ?: defaultRetryPolicy))
        return MutationHandle(id, key)
    }

    /**
     * Loads every persisted [MutationRecord] from [store] and resubmits it for execution, using
     * the [MutationCodec] registered under [MutationRecord.codecId] (via the constructor's
     * `codecs` or a prior [enqueue] call) to reconstruct the endpoint + body.
     *
     * Call once at startup, after registering every codec you enqueue with, to replay mutations
     * that didn't finish before the process died. Records whose codec isn't registered are left
     * untouched in [store] (so a later app version that registers the codec can still recover them).
     */
    public suspend fun restore() {
        store
            .loadAll()
            .groupBy(MutationRecord::key)
            .values
            .forEach { records ->
                val record = records.maxWith(compareBy(MutationRecord::enqueuedAtMillis).thenBy(MutationRecord::id))
                records.filterNot { it === record }.forEach { store.remove(it.id) }
                @Suppress("UNCHECKED_CAST")
                val codec = codecsById[record.codecId] as? MutationCodec<Any> ?: return@forEach
                val decoded = codec.decode(record.payload)
                val key = MutationKey(record.key)
                val mutation = QueuedMutation(record.id, key, decoded.endpoint, decoded.body, decoded.bodyType)
                workerFor(key).submit(Enqueued(mutation, record, defaultRetryPolicy))
            }
    }

    private fun statusFlowFor(key: MutationKey): MutableStateFlow<MutationStatus?> = statuses.getOrPut(key) { MutableStateFlow(null) }

    private fun workerFor(key: MutationKey): KeyWorker = workers.getOrPut(key) { KeyWorker(apiClient, store, statusFlowFor(key), scope) }
}

/** One coalesced mutation, bundled with its persistence record (if any) and effective retry policy. */
private class Enqueued<B : Any>(
    val mutation: QueuedMutation<B>,
    val record: MutationRecord?,
    val retryPolicy: RetryPolicy,
)

/**
 * Executes (with retry + coalescing) every mutation submitted for a single [MutationKey]. Only one
 * of these runs at a time per key: [submit] atomically swaps in the latest desired mutation, and
 * the worker loop always converges on whatever was most recently submitted, cancelling any
 * in-progress retry backoff for a superseded mutation.
 */
private class KeyWorker(
    private val apiClient: ApiClient,
    private val store: MutationStore,
    private val statusFlow: MutableStateFlow<MutationStatus?>,
    private val scope: CoroutineScope,
) {
    private val latest = MutableStateFlow<Enqueued<*>?>(null)
    private val stateMutex = Mutex()
    private var workerRunning = false

    suspend fun submit(enqueued: Enqueued<*>) {
        var superseded: Enqueued<*>? = null
        var startWorker = false
        stateMutex.withLock {
            superseded = latest.value
            latest.value = enqueued
            statusFlow.value = MutationStatus.Pending
            if (!workerRunning) {
                workerRunning = true
                startWorker = true
            }
        }
        if (superseded != null && superseded !== enqueued) {
            superseded.record?.let { store.remove(it.id) }
        }
        if (startWorker) {
            scope.launch { runLoop() }
        }
    }

    private suspend fun runLoop() {
        while (true) {
            val current = latest.value ?: return
            processOne(current)
            val finished =
                stateMutex.withLock {
                    if (latest.value === current) {
                        latest.value = null
                        workerRunning = false
                        true
                    } else {
                        false
                    }
                }
            if (finished) {
                return
            }
            // A newer mutation replaced `current` while we were working it; loop picks it up.
        }
    }

    // Each branch below is a guard clause for a distinct terminal/loop-continuation outcome
    // (superseded, succeeded, gave up, or scheduled a retry) — splitting it up would obscure the
    // state machine rather than clarify it.
    @Suppress("ReturnCount", "NestedBlockDepth")
    private suspend fun processOne(enqueued: Enqueued<*>) {
        @Suppress("UNCHECKED_CAST")
        val active = enqueued as Enqueued<Any>
        var attempt = 0
        while (true) {
            if (latest.value !== enqueued) return

            val failure = attemptOnce(active)
            if (failure == null) {
                val stillCurrent = updateStatusIfCurrent(enqueued, MutationStatus.Succeeded)
                if (stillCurrent) active.record?.let { store.remove(it.id) }
                return
            }

            val delayMillis = active.retryPolicy.retryDelayMillis(attempt + 1, active.mutation.endpoint.method, failure)
            if (delayMillis == null) {
                val stillCurrent = updateStatusIfCurrent(enqueued, MutationStatus.Failed(failure))
                if (stillCurrent) active.record?.let { store.remove(it.id) }
                return
            }

            attempt++
            if (!updateStatusIfCurrent(enqueued, MutationStatus.Retrying(attempt, failure))) return
            if (awaitDelayOrSupersede(delayMillis, enqueued)) return
        }
    }

    private suspend fun updateStatusIfCurrent(
        enqueued: Enqueued<*>,
        status: MutationStatus,
    ): Boolean =
        stateMutex.withLock {
            if (latest.value !== enqueued) return@withLock false
            statusFlow.value = status
            true
        }

    private suspend fun attemptOnce(active: Enqueued<Any>): NetworkError? =
        try {
            apiClient.request<Unit>(active.mutation.endpoint, active.mutation.body, active.mutation.bodyType, typeInfo<Unit>())
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkError) {
            error
        } catch (error: Throwable) {
            NetworkError.Underlying(error)
        }

    /** Waits [delayMillis], or returns early (`true`) the instant [current] is superseded. */
    private suspend fun awaitDelayOrSupersede(
        delayMillis: Long,
        current: Enqueued<*>,
    ): Boolean {
        if (latest.value !== current) return true
        return coroutineScope {
            val outcome = CompletableDeferred<Boolean>()
            val watcher =
                launch {
                    latest.filter { it !== current }.first()
                    outcome.complete(true)
                }
            val timer =
                launch {
                    delay(delayMillis)
                    outcome.complete(false)
                }
            val superseded = outcome.await()
            watcher.cancel()
            timer.cancel()
            superseded
        }
    }
}
