package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.RetryPolicy
import io.ktor.util.reflect.typeInfo

/** Enqueues a bodyless mutation (e.g. `POST /videos/42/like` with no request body). */
public suspend fun MutationQueue.enqueue(
    key: MutationKey,
    endpoint: NetworkEndpoint,
    retryPolicy: RetryPolicy? = null,
): MutationHandle = enqueueMutation<Unit>(key, endpoint, body = null, bodyType = null, codec = null, retryPolicy = retryPolicy)

/** Enqueues a mutation sending [body] of type [B], serialized on execution. */
public suspend inline fun <reified B : Any> MutationQueue.enqueue(
    key: MutationKey,
    endpoint: NetworkEndpoint,
    body: B,
    codec: MutationCodec<B>? = null,
    retryPolicy: RetryPolicy? = null,
): MutationHandle = enqueueMutation(key, endpoint, body, typeInfo<B>(), codec, retryPolicy)
