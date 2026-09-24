package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.network.request
import io.github.maniramezan.kenwork.testing.RecordingNetworkEventListener
import io.github.maniramezan.kenwork.testing.jsonResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthenticatedClientSampleTest {
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
            val client = createAuthenticatedClient(tokens, telemetry = telemetry, engine = engine)
            try {
                val video: Video = client.request(GetVideo(42))

                assertEquals(Video(42, "Hello"), video)
                assertEquals(1, tokens.refreshes)
                assertEquals("v1/videos/:id", telemetry.events.single().endpointId)
            } finally {
                client.close()
                engine.close()
            }
        }
}
