package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthStaleResponseTest {
    private class Endpoint(
        override val path: String,
    ) : NetworkEndpoint {
        override val baseUrl = "https://api.test"
        override val method = HttpMethod.GET
    }

    @Test
    fun `late 401 using an already replaced token does not refresh again`() =
        runTest {
            val bothStarted = CompletableDeferred<Unit>()
            val firstCompleted = CompletableDeferred<Unit>()
            val oldRequests = AtomicInteger()
            val refreshes = AtomicInteger()
            val provider = OAuthAuthorizationProvider("old") { "new-${refreshes.incrementAndGet()}" }
            val engine =
                MockEngine { request ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer old") {
                        if (oldRequests.incrementAndGet() == 2) bothStarted.complete(Unit)
                        bothStarted.await()
                        if (request.url.encodedPath.endsWith("second")) firstCompleted.await()
                        json("{}", HttpStatusCode.Unauthorized)
                    } else {
                        json("{}")
                    }
                }
            val client = NetworkClient(NetworkClientConfiguration(engine = engine, authorizationProvider = provider, retryDelayMillis = 0))
            try {
                val first = async { client.request<Unit>(Endpoint("first")) }
                val second = async { client.request<Unit>(Endpoint("second")) }
                first.await()
                firstCompleted.complete(Unit)
                second.await()
                assertEquals(1, refreshes.get())
            } finally {
                client.close()
                engine.close()
                provider.close()
            }
        }
}
