package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.NetworkError

/**
 * The lifecycle of one enqueued mutation, as observed via [MutationQueue.statusFlow].
 *
 * Optimistic-UI rollback is deliberately out of scope here: this type reports *outcome*
 * (did the call eventually succeed, is it still trying, did it give up), not UI state. Callers
 * that applied an optimistic update decide for themselves what [Failed] means for their UI.
 */
public sealed class MutationStatus {
    /** Enqueued and waiting for its first attempt (or waiting behind a coalesced predecessor). */
    public data object Pending : MutationStatus()

    /** The most recent attempt failed transiently and a retry is scheduled. */
    public data class Retrying(
        public val attempt: Int,
        public val error: NetworkError,
    ) : MutationStatus()

    /** The mutation was executed successfully. */
    public data object Succeeded : MutationStatus()

    /** The retry policy gave up (or refused to retry a non-idempotent failure) after [error]. */
    public data class Failed(
        public val error: NetworkError,
    ) : MutationStatus()
}
