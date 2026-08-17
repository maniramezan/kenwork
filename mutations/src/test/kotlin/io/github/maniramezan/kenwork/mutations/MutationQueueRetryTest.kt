package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.NetworkError
import io.github.maniramezan.kenwork.network.RetryPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MutationQueueRetryTest {
    @Test
    fun `retries a transient failure and eventually succeeds`() =
        runTest {
            val apiClient =
                RecordingApiClient { _, index ->
                    if (index == 0) throw NetworkError.ServerError(503, null)
                }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 2, backoffBaseMillis = 0, retryNonIdempotent = true),
                )
            val key = MutationKey.of("like", "video", 42)

            queue.enqueue(key, SetLikeState(42), LikeBody(true))
            settle()

            assertEquals(2, apiClient.calls.size)
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)
        }

    @Test
    fun `does not retry a non-idempotent method unless opted in`() =
        runTest {
            val apiClient = RecordingApiClient { _, _ -> throw NetworkError.ServerError(500, null) }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    // Mirrors DefaultRetryPolicy's own conservative default (retryNonIdempotent = false).
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 3, backoffBaseMillis = 0),
                )
            val key = MutationKey.of("like", "video", 1)

            queue.enqueue(key, SetLikeState(1), LikeBody(true))
            settle()

            assertEquals(1, apiClient.calls.size)
            val status = queue.statusFlow(key).value
            assertIs<MutationStatus.Failed>(status)
            assertIs<NetworkError.ServerError>(status.error)
        }

    @Test
    fun `per-mutation retryPolicy overrides the queue default`() =
        runTest {
            var calls = 0
            val apiClient =
                RecordingApiClient { _, index ->
                    calls++
                    if (index == 0) throw NetworkError.ServerError(500, null)
                }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    // Queue-wide default forbids retrying POST/PATCH...
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 3, backoffBaseMillis = 0),
                )
            val key = MutationKey.of("like", "video", 2)

            // ...but this specific mutation opts in explicitly.
            queue.enqueue(
                key,
                SetLikeState(2),
                LikeBody(true),
                retryPolicy = DefaultRetryPolicy(maxRetries = 3, backoffBaseMillis = 0, retryNonIdempotent = true),
            )
            settle()

            assertEquals(2, calls)
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)
        }

    @Test
    fun `gives up and reports Failed after exhausting max retries`() =
        runTest {
            val apiClient = RecordingApiClient { _, _ -> throw NetworkError.ServerError(503, null) }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 2, backoffBaseMillis = 0, retryNonIdempotent = true),
                )
            val key = MutationKey.of("like", "video", 3)

            queue.enqueue(key, SetLikeState(3), LikeBody(true))
            settle()

            // 1 initial attempt + 2 retries.
            assertEquals(3, apiClient.calls.size)
            val status = queue.statusFlow(key).value
            assertIs<MutationStatus.Failed>(status)
        }

    @Test
    fun `RetryPolicy None never retries`() =
        runTest {
            val apiClient = RecordingApiClient { _, _ -> throw NetworkError.Timeout }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    defaultRetryPolicy = RetryPolicy.None,
                )
            val key = MutationKey.of("like", "video", 4)

            queue.enqueue(key, SetLikeState(4), LikeBody(true))
            settle()

            assertEquals(1, apiClient.calls.size)
            assertIs<MutationStatus.Failed>(queue.statusFlow(key).value)
        }
}
