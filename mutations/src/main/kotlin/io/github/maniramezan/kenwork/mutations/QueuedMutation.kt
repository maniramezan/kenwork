package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo

/**
 * The live, in-process description of one enqueued mutation: which [endpoint] to call and what
 * [body] to send. This is what [MutationQueue] actually executes via
 * [io.github.maniramezan.kenwork.network.ApiClient.request].
 *
 * It is deliberately *not* a suspend lambda. A closure can't be written to disk and reconstructed
 * after process death, so it can't back a durable [MutationStore]. An endpoint + body pair can —
 * see [MutationCodec] for how to make one round-trip through persistence.
 */
public class QueuedMutation<B : Any>(
    public val id: String,
    public val key: MutationKey,
    public val endpoint: NetworkEndpoint,
    public val body: B?,
    public val bodyType: TypeInfo?,
)
