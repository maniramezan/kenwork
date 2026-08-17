package io.github.maniramezan.kenwork.mutations

/**
 * Identifies the *logical* mutation being made, independent of when it was enqueued.
 *
 * [MutationQueue] coalesces by key: enqueueing a mutation under a key that already has one
 * pending or retrying replaces it, so rapidly toggling the same logical state (e.g. liking then
 * unliking video 42 before the first call lands) collapses to a single call for the latest
 * desired state instead of replaying every intermediate one.
 *
 * Construct one per logical target, e.g. `MutationKey("like:video:42")` or via [of].
 */
@JvmInline
public value class MutationKey(
    public val value: String,
) {
    public companion object {
        /** Builds a key by joining [parts] with `:`, e.g. `MutationKey.of("like", "video", 42)`. */
        public fun of(vararg parts: Any): MutationKey = MutationKey(parts.joinToString(":"))
    }
}
