package io.github.maniramezan.kenwork.mutations

import kotlinx.serialization.Serializable

/**
 * The fully serializable snapshot of one enqueued mutation that a [MutationStore] persists.
 *
 * This is the payoff of [QueuedMutation] not being a raw closure: every field here is a plain
 * string/number, so a durable [MutationStore] implementation can write it straight to disk (a
 * `MutationRecord` is `@Serializable`, so `Json.encodeToString`/SQLDelight/Room/DataStore all work
 * without extra glue) and hand it back unchanged after the process restarts. [MutationQueue]
 * reconstructs the executable [QueuedMutation] from a record via the [MutationCodec] identified
 * by [codecId].
 *
 * @property id a unique id for this specific enqueue (regenerated each time coalescing replaces it).
 * @property key the [MutationKey.value] this record coalesces under.
 * @property codecId the [MutationCodec.id] that can decode [payload] back into an endpoint + body.
 * @property payload the codec-produced encoding of the endpoint + body to replay.
 * @property enqueuedAtMillis wall-clock time the mutation was (re-)enqueued, for diagnostics/ordering.
 */
@Serializable
public data class MutationRecord(
    public val id: String,
    public val key: String,
    public val codecId: String,
    public val payload: String,
    public val enqueuedAtMillis: Long,
)
