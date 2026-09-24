package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.ApiClient
import io.github.maniramezan.kenwork.network.NetworkError
import io.github.maniramezan.kenwork.network.RetryPolicy
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One coalesced mutation, bundled with its persistence record (if any) and effective retry policy. */
internal class Enqueued<B : Any>(
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
internal class KeyWorker(
    private val apiClient: ApiClient,
    private val store: MutationStore,
    private val statusFlow: MutableStateFlow<MutationStatus?>,
    private val scope: CoroutineScope,
) {
    private val latest = MutableStateFlow<Enqueued<*>?>(null)
    private val stateMutex = Mutex()
    private var workerRunning = false

    /** Whether the mutation with [mutationId] is the one this worker is currently driving. */
    fun isTracking(mutationId: String): Boolean = latest.value?.mutation?.id == mutationId

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
        // Drop the superseded mutation's persisted record — unless it *is* the new one's record
        // (the same mutation resubmitted), which must survive until the mutation finishes.
        val staleRecord = superseded?.takeIf { it !== enqueued }?.record
        if (staleRecord != null && staleRecord.id != enqueued.record?.id) {
            store.remove(staleRecord.id)
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
        return withTimeoutOrNull(delayMillis) { latest.first { it !== current } } != null
    }
}
