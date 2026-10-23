package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.network.NetworkError
import io.github.maniramezan.kenwork.network.request
import io.github.maniramezan.kenwork.testing.RecordingNetworkEventListener
import io.github.maniramezan.kenwork.testing.jsonResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticatedSessionSampleTest {
    @Test
    fun `closing the session cancels refresh and prevents later client requests`() =
        runTest {
            val refreshing = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val tokens =
                object : TokenSource {
                    override val accessToken = "expired"

                    override suspend fun refresh(): String? {
                        refreshing.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
            val engine = MockEngine { jsonResponse("{}", HttpStatusCode.Unauthorized) }
            val session = createAuthenticatedSession(tokens, engine = engine)

            try {
                val pending =
                    async {
                        assertFailsWith<NetworkError.AuthorizationRefreshFailed> { session.client.request<Video>(GetVideo(42)) }
                    }
                refreshing.await()
                session.close()
                stopped.await()
                pending.await()

                assertFailsWith<IllegalStateException> { session.client.request<Video>(GetVideo(42)) }
                session.close()
            } finally {
                session.close()
                engine.close()
            }
        }

    private class FakeTokens : TokenSource {
        override val accessToken = "expired"
        var refreshes = 0

        override suspend fun refresh(): String {
            refreshes++
            return "fresh"
        }
    }

    @Test
    fun `a 401 refreshes the token once and replays the request`(): Unit =
        runBlocking {
            val tokens = FakeTokens()
            val telemetry = RecordingNetworkEventListener()
            val engine =
                MockEngine { request ->
                    when (request.headers[HttpHeaders.Authorization]) {
                        "Bearer fresh" -> jsonResponse("""{"id":42,"title":"Hello"}""")
                        else -> jsonResponse("{}", HttpStatusCode.Unauthorized)
                    }
                }
            val session = createAuthenticatedSession(tokens, telemetry = telemetry, engine = engine)
            try {
                val video: Video = session.client.request(GetVideo(42))

                assertEquals(Video(42, "Hello"), video)
                assertEquals(1, tokens.refreshes)
                assertEquals("v1/videos/:id", telemetry.events.single().endpointId)
            } finally {
                session.close()
                engine.close()
            }
        }
}
