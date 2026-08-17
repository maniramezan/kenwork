package io.github.maniramezan.kenwork.mutations

/**
 * Returned immediately by [MutationQueue.enqueueMutation]/[enqueue]. Execution happens in the
 * background, so this carries no result — observe [MutationQueue.statusFlow] with [key] for
 * outcome.
 */
public class MutationHandle(
    public val id: String,
    public val key: MutationKey,
)
