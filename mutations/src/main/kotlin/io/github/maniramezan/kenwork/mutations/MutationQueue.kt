package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.ApiClient
import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.KenworkLogger
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.RetryPolicy
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - **Lifecycle & Cleanup**: completed workers are removed once idle, and [cancel] cancels in-flight
 *   work, removes persisted records, and resets status to `null`. Internal status tracking is bounded
 *   so the queue does not grow indefinitely.
 *
 * @param apiClient executes the underlying HTTP calls.
 * @param scope owns every mutation's execution + retry backoff; mutations outlive the caller's
 *   own scope (e.g. a ViewModel's) as long as this scope is alive.
 * @param store where enqueued mutations (that were given a [MutationCodec]) are persisted.
 * @param defaultRetryPolicy applied to mutations enqueued without an explicit `retryPolicy`.
 *   Defaults to retrying non-idempotent methods too — the whole point of this queue is to make
 *   `POST`/`PATCH` mutations retryable — unlike [DefaultRetryPolicy]'s own conservative default.
 * @param maxStatuses maximum number of status flows to retain per queue instance. When exceeded,
 *   the least-recently-accessed entries are evicted. Must be positive. Defaults to 64.
 * @param codecs [MutationCodec]s to register upfront, so [restore] can decode their records even
 *   before any matching [enqueue] call runs in this process. Enqueueing with a new codec also
 *   registers it.
 */
public class MutationQueue(
    private val apiClient: ApiClient,
    private val scope: CoroutineScope,
    private val store: MutationStore = InMemoryMutationStore(),
    private val defaultRetryPolicy: RetryPolicy = DefaultRetryPolicy(retryNonIdempotent = true),
    private val maxStatuses: Int = 64,
    codecs: List<MutationCodec<*>> = emptyList(),
) {
    init {
        require(maxStatuses > 0) { "maxStatuses must be positive, got $maxStatuses" }
    }

    private val codecsById =
        ConcurrentHashMap<String, MutationCodec<*>>().apply {
            codecs.forEach { put(it.id, it) }
        }

    private val statuses = LinkedHashMap<MutationKey, MutableStateFlow<MutationStatus?>>(INITIAL_STATUS_CAPACITY, LOAD_FACTOR, true)

    private val workers = ConcurrentHashMap<MutationKey, KeyWorker>()
    private val workersMutex = Mutex()

    /**
     * The current/most recent [MutationStatus] for [key], or `null` if nothing has ever been
     * enqueued under it or if it was cancelled via [cancel]. Keeps emitting past a terminal
     * [MutationStatus.Succeeded]/[MutationStatus.Failed] until evicted or superseded by a new mutation.
     */
    public fun statusFlow(key: MutationKey): StateFlow<MutationStatus?> = statusFlowFor(key).asStateFlow()

    /**
     * Cancels any pending or in-flight mutation for [key].
     *
     * If a mutation is currently retrying or executing, it is cancelled. The persisted record
     * (if any) is removed from [store], and [statusFlow] for [key] transitions to `null`.
     *
     * Reporting `null` indicates that the queue is idle for this key without introducing a
     * new [MutationStatus] subclass that would break consumers with exhaustive `when` expressions.
     */
    public suspend fun cancel(key: MutationKey) {
        workersMutex.withLock {
            workers.remove(key)?.cancel()
            synchronized(statuses) { statuses.remove(key)?.value = null }
        }
    }

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

        workersMutex.withLock {
            workerFor(key).submit(Enqueued(mutation, record, retryPolicy ?: defaultRetryPolicy))
        }
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
            .forEach { records -> restoreNewest(records) }
    }

    /**
     * Resubmits the newest of [records] (all sharing one key) and drops its predecessors. A record
     * whose codec is missing or whose payload fails to decode is left in [store] untouched, and
     * never prevents other keys from being restored.
     */
    private suspend fun restoreNewest(records: List<MutationRecord>) {
        val record = records.maxWith(compareBy(MutationRecord::enqueuedAtMillis).thenBy(MutationRecord::id))
        val key = MutationKey(record.key)
        // Already executing in this process (e.g. restore() called twice): resubmitting would send
        // it again, and coalescing it with itself would delete its own persisted record.
        if (workers[key]?.isTracking(record.id) == true) return

        @Suppress("UNCHECKED_CAST")
        val codec = codecsById[record.codecId] as? MutationCodec<Any> ?: return
        val decoded =
            try {
                codec.decode(record.payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                KenworkLogger.error(
                    "Skipping persisted mutation ${record.id}: codec '${record.codecId}' failed to decode it",
                    error,
                    attributes = mapOf("kenwork.mutation.codec_id" to record.codecId),
                )
                return
            }
        // Do not discard anything until the newest desired state is known to be recoverable. If
        // its codec is unavailable (or decoding throws), a future app version must still be able
        // to restore it and then clean up its predecessors.
        records.filterNot { it === record }.forEach { store.remove(it.id) }
        val mutation = QueuedMutation(record.id, key, decoded.endpoint, decoded.body, decoded.bodyType)
        workersMutex.withLock {
            workerFor(key).submit(Enqueued(mutation, record, defaultRetryPolicy))
        }
    }

    private fun statusFlowFor(key: MutationKey): MutableStateFlow<MutationStatus?> =
        synchronized(statuses) {
            val flow = statuses.getOrPut(key) { MutableStateFlow(null) }
            trimStatuses(except = key)
            flow
        }

    /** Retain active workers' flows so observers can always find the status being updated. */
    private fun trimStatuses(except: MutationKey? = null) {
        val iterator = statuses.entries.iterator()
        while (statuses.size > maxStatuses && iterator.hasNext()) {
            val key = iterator.next().key
            if (key != except && !workers.containsKey(key)) iterator.remove()
        }
    }

    private fun workerFor(key: MutationKey): KeyWorker =
        workers.getOrPut(key) {
            KeyWorker(apiClient, store, statusFlowFor(key), scope) { finishedWorker ->
                workersMutex.withLock {
                    if (finishedWorker.isIdle()) workers.remove(key, finishedWorker)
                    synchronized(statuses) { trimStatuses() }
                }
            }
        }

    private companion object {
        private const val INITIAL_STATUS_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }

    /** Binary-compatibility shim for callers compiled before [maxStatuses] was introduced. */
    @Deprecated("Binary-compatibility shim; use primary constructor with maxStatuses.", level = DeprecationLevel.HIDDEN)
    public constructor(
        apiClient: ApiClient,
        scope: CoroutineScope,
        store: MutationStore = InMemoryMutationStore(),
        defaultRetryPolicy: RetryPolicy = DefaultRetryPolicy(retryNonIdempotent = true),
        codecs: List<MutationCodec<*>> = emptyList(),
    ) : this(
        apiClient = apiClient,
        scope = scope,
        store = store,
        defaultRetryPolicy = defaultRetryPolicy,
        maxStatuses = 64,
        codecs = codecs,
    )
}
