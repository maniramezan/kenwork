package io.github.maniramezan.kenwork.network

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObservabilityHooksTest {
    @Test
    @Suppress("DestructuringDeclarationWithTooManyEntries")
    fun `copy preserves final-attempt metadata and destructuring compatibility`() {
        val event =
            NetworkEvent(
                endpointId = "samples/:id",
                method = "GET",
                durationMs = 12,
                attempt = 1,
                isFinalAttempt = false,
            )

        val copied = event.copy(durationMs = 20)
        val (endpointId, method, durationMs, _, _, _, attempt) = copied

        assertEquals(false, copied.isFinalAttempt)
        assertEquals("samples/:id", endpointId)
        assertEquals("GET", method)
        assertEquals(20, durationMs)
        assertEquals(1, attempt)
    }

    @Test
    fun `legacy NetworkEvent JVM constructor and copy bridge remain linkable`() {
        val eventClass = NetworkEvent::class.java

        eventClass.getConstructor(
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaObjectType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        eventClass.getDeclaredMethod(
            "copy\$default",
            eventClass,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaObjectType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
    }

    @Test
    fun `requestHeaderProvider headers are attached to the outgoing request`(): Unit =
        runBlocking {
            var seenHeader: String? = null
            val client =
                testClient(
                    requestHeaderProvider = RequestHeaderProvider { _, attempt -> mapOf("traceparent" to "trace-$attempt") },
                ) {
                    seenHeader = it.headers["traceparent"]
                    json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(TestEndpoint("samples/1"))
            assertEquals("trace-0", seenHeader)
        }

    @Test
    fun `requestHeaderProvider is invoked fresh with the current attempt number on retries`(): Unit =
        runBlocking {
            val seenHeaders = mutableListOf<String>()
            var calls = 0
            val client =
                testClient(
                    maxTransientRetries = 2,
                    requestHeaderProvider = RequestHeaderProvider { _, attempt -> mapOf("traceparent" to "trace-$attempt") },
                ) {
                    seenHeaders += it.headers["traceparent"].orEmpty()
                    calls++
                    if (calls < 3) json("{}", HttpStatusCode.InternalServerError) else json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(TestEndpoint("samples/1"))
            assertEquals(listOf("trace-0", "trace-1", "trace-2"), seenHeaders)
        }

    @Test
    fun `requestHeaderProvider headers are added after endpoint headers and can override them`(): Unit =
        runBlocking {
            var seenHeader: String? = null
            val client =
                testClient(
                    requestHeaderProvider = RequestHeaderProvider { _, _ -> mapOf("X-Trace" to "from-provider") },
                ) {
                    seenHeader = it.headers["X-Trace"]
                    json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(TestEndpoint("samples/1", headers = mapOf("X-Trace" to "from-endpoint")))
            assertEquals("from-provider", seenHeader)
        }

    @Test
    fun `requestInterceptor wraps each attempt and sees the correct attempt index`(): Unit =
        runBlocking {
            val seenAttempts = mutableListOf<Int>()
            var calls = 0
            val client =
                testClient(
                    maxTransientRetries = 2,
                    requestInterceptor =
                        object : RequestInterceptor {
                            override suspend fun <T> intercept(
                                endpoint: NetworkEndpoint,
                                attempt: Int,
                                proceed: suspend () -> T,
                            ): T {
                                seenAttempts += attempt
                                return proceed()
                            }
                        },
                ) {
                    calls++
                    if (calls < 3) json("{}", HttpStatusCode.InternalServerError) else json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(TestEndpoint("samples/1"))
            assertEquals(listOf(0, 1, 2), seenAttempts)
        }

    @Test
    fun `requestInterceptor observes and can react to a failed attempt before it propagates`(): Unit =
        runBlocking {
            var observedFailure = false
            val client =
                testClient(
                    requestInterceptor =
                        object : RequestInterceptor {
                            override suspend fun <T> intercept(
                                endpoint: NetworkEndpoint,
                                attempt: Int,
                                proceed: suspend () -> T,
                            ): T =
                                try {
                                    proceed()
                                } catch (t: Throwable) {
                                    observedFailure = true
                                    throw t
                                }
                        },
                ) {
                    json("{}", HttpStatusCode.NotFound)
                }
            assertFailsWith<NetworkError.NotFound> { client.request<Sample>(TestEndpoint("samples/1")) }
            assertTrue(observedFailure)
        }

    @Test
    fun `requestInterceptor entry-exit brackets each attempt for span-like timing`(): Unit =
        runBlocking {
            val events = mutableListOf<String>()
            var calls = 0
            val client =
                testClient(
                    maxTransientRetries = 1,
                    requestInterceptor =
                        object : RequestInterceptor {
                            override suspend fun <T> intercept(
                                endpoint: NetworkEndpoint,
                                attempt: Int,
                                proceed: suspend () -> T,
                            ): T {
                                events += "start-$attempt"
                                try {
                                    return proceed()
                                } finally {
                                    events += "end-$attempt"
                                }
                            }
                        },
                ) {
                    calls++
                    if (calls == 1) json("{}", HttpStatusCode.InternalServerError) else json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(TestEndpoint("samples/1"))
            assertEquals(listOf("start-0", "end-0", "start-1", "end-1"), events)
        }

    @Test
    fun `behavior is unchanged when the hooks are unset`(): Unit =
        runBlocking {
            val client = testClient { json("""{"id":1,"name":"ada"}""") }
            val sample: Sample = client.request(TestEndpoint("samples/1"))
            assertEquals(Sample(1, "ada"), sample)
        }

    @Test
    fun `isFinalAttempt is false for a retried attempt and true for the final outcome`(): Unit =
        runBlocking {
            val listener = RecordingListener()
            var calls = 0
            val client =
                testClient(maxTransientRetries = 1, eventListener = listener) {
                    calls++
                    if (calls == 1) json("{}", HttpStatusCode.InternalServerError) else json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(TestEndpoint("samples/1"))
            assertEquals(2, listener.events.size)
            assertEquals(false, listener.events[0].isFinalAttempt)
            assertEquals(true, listener.events[1].isFinalAttempt)
        }

    @Test
    fun `isFinalAttempt is true for a failure the retry policy declines to retry`(): Unit =
        runBlocking {
            val listener = RecordingListener()
            val client = testClient(eventListener = listener) { json("{}", HttpStatusCode.NotFound) }
            assertFailsWith<NetworkError.NotFound> { client.request<Sample>(TestEndpoint("samples/1")) }
            assertEquals(true, listener.events.single().isFinalAttempt)
        }
}
