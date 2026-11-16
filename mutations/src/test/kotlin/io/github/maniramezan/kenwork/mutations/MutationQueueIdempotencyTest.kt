package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.NetworkError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MutationQueueIdempotencyTest {
    private val retryOnce = DefaultRetryPolicy(maxRetries = 1, backoffBaseMillis = 0, retryNonIdempotent = true)

    @Test
    fun `every attempt of a mutation carries the same idempotency key`() =
        runTest {
            val api = RecordingApiClient { _, index -> if (index == 0) throw NetworkError.ServerError(503, null) }
            val queue = MutationQueue(api, backgroundScope, defaultRetryPolicy = retryOnce, idempotencyKeyHeader = "Idempotency-Key")

            val handle = queue.enqueue(MutationKey("like:42"), SetLikeState(42), LikeBody(true))
            settle()

            assertEquals(listOf(handle.id, handle.id), api.calls.map { it.endpoint.headers?.get("Idempotency-Key") })
        }

    @Test
    fun `a restored mutation keeps its persisted idempotency key`() =
        runTest {
            val store = InMemoryMutationStore()
            store.save(
                MutationRecord(
                    "persisted-id",
                    "like:7",
                    SetLikeStateCodec.id,
                    SetLikeStateCodec.encode(SetLikeState(7), LikeBody(true)),
                    1,
                ),
            )
            val api = RecordingApiClient()
            val queue =
                MutationQueue(
                    api,
                    backgroundScope,
                    store,
                    codecs = listOf(SetLikeStateCodec),
                    idempotencyKeyHeader = "Idempotency-Key",
                )

            queue.restore()
            settle()

            assertEquals(
                "persisted-id",
                api.calls
                    .single()
                    .endpoint.headers
                    ?.get("Idempotency-Key"),
            )
        }

    @Test
    fun `the endpoint's own headers and route are kept`() =
        runTest {
            val endpoint =
                object : NetworkEndpoint {
                    override val baseUrl = "https://api.test"
                    override val path = "follow/9"
                    override val method = HttpMethod.PUT
                    override val headers = mapOf("X-Client" to "app")
                }
            val api = RecordingApiClient()
            val queue = MutationQueue(api, backgroundScope, idempotencyKeyHeader = "Idempotency-Key")

            queue.enqueue(MutationKey("follow:9"), endpoint)
            settle()

            val sent = api.calls.single().endpoint
            assertEquals("app", sent.headers?.get("X-Client"))
            assertEquals("follow/9", sent.path)
            assertEquals(HttpMethod.PUT, sent.method)
        }

    @Test
    fun `no header is added by default`() =
        runTest {
            val api = RecordingApiClient()
            val queue = MutationQueue(api, backgroundScope)
            queue.enqueue(MutationKey("like:1"), SetLikeState(1), LikeBody(true))
            settle()
            assertNull(
                api.calls
                    .single()
                    .endpoint.headers,
            )
        }
}
