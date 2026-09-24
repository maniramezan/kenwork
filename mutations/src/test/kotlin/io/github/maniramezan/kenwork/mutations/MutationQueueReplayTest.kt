package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.testing.jsonResponse
import io.github.maniramezan.kenwork.testing.mockNetworkClient
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MutationQueueReplayTest {
    @Test
    fun `a restored mutation sends its body through a real NetworkClient`() =
        runTest {
            val store = InMemoryMutationStore()
            store.save(
                MutationRecord(
                    id = "record-1",
                    key = MutationKey.of("like", "video", 42).value,
                    codecId = SetLikeStateCodec.id,
                    payload = SetLikeStateCodec.encode(SetLikeState(42), LikeBody(true)),
                    enqueuedAtMillis = 0L,
                ),
            )
            val sentBody = CompletableDeferred<String>()
            val client =
                mockNetworkClient { request ->
                    val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()
                    sentBody.complete(bytes?.decodeToString() ?: "<no body>")
                    jsonResponse("{}")
                }
            val queue = MutationQueue(apiClient = client, scope = backgroundScope, store = store, codecs = listOf(SetLikeStateCodec))

            queue.restore()

            assertEquals("""{"liked":true}""", sentBody.await())
        }
}
