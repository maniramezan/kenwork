package io.github.maniramezan.kemwork.network

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointAndEventTest {
    @Test
    fun `resolvedUrl joins base and path with one slash`() {
        assertEquals("https://api.test/videos/1", TestEndpoint("/videos/1", baseUrl = "https://api.test/").resolvedUrl())
        assertEquals("https://api.test/videos", TestEndpoint("videos", baseUrl = "https://api.test").resolvedUrl())
        assertEquals("https://api.test", TestEndpoint("", baseUrl = "https://api.test/").resolvedUrl())
    }

    @Test
    fun `endpointId normalizes numeric and uuid segments`() {
        assertEquals("videos/:id", TestEndpoint("videos/42").endpointId())
        assertEquals("root", TestEndpoint("/").endpointId())
        assertEquals(
            "videos/:uuid",
            TestEndpoint("videos/550e8400-e29b-41d4-a716-446655440000").endpointId(),
        )
    }

    @Test
    fun `records a success event`(): Unit =
        runBlocking {
            val listener = RecordingListener()
            val client = testClient(eventListener = listener) { json("""{"id":1,"name":"a"}""") }
            client.request<Sample>(TestEndpoint("videos/1"))
            val event = listener.events.single()
            assertEquals("videos/:id", event.endpointId)
            assertEquals("GET", event.method)
            assertEquals(200, event.statusCode)
            assertTrue(event.isSuccess)
        }

    @Test
    fun `records a retryable failure event for server errors`(): Unit =
        runBlocking {
            val listener = RecordingListener()
            val client = testClient(eventListener = listener) { json("{}", HttpStatusCode.InternalServerError) }
            assertFailsWith<NetworkError.ServerError> { client.request<Sample>(TestEndpoint("videos/1")) }
            val event = listener.events.single()
            assertEquals(500, event.statusCode)
            assertEquals("ServerError", event.errorType)
            assertTrue(event.isRetryable)
        }
}
