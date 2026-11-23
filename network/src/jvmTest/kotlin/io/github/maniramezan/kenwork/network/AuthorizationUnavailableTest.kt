package io.github.maniramezan.kenwork.network

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthorizationUnavailableTest {
    private class ThrowingProvider(
        private val error: Exception,
    ) : AuthorizationProvider {
        override suspend fun currentAuthorization(): AuthorizationType = throw error

        override suspend fun refreshAuthorizationIfNeeded(): Boolean = false
    }

    @Test
    fun `a provider that cannot supply authorization fails the request without sending it`(): Unit =
        runBlocking {
            val cause = IllegalStateException("session revoked")
            var calls = 0
            val client =
                testClient(authorizationProvider = ThrowingProvider(cause)) {
                    calls++
                    json("""{"id":1,"name":"a"}""")
                }

            val error = assertFailsWith<NetworkError.AuthorizationUnavailable> { client.request<Sample>(TestEndpoint("x")) }

            assertEquals(0, calls)
            assertSame(cause, error.cause)
        }

    @Test
    fun `cancellation from the provider propagates unchanged`(): Unit =
        runBlocking {
            val client = testClient(authorizationProvider = ThrowingProvider(CancellationException("stop"))) { json("{}") }

            assertFailsWith<CancellationException> { client.request<Sample>(TestEndpoint("x")) }
        }

    @Test
    fun `endpoint-level authorization does not consult a failing provider`(): Unit =
        runBlocking {
            var header: String? = null
            val client =
                testClient(authorizationProvider = ThrowingProvider(IllegalStateException("revoked"))) {
                    header = it.headers[HttpHeaders.Authorization]
                    json("""{"id":1,"name":"a"}""")
                }

            client.request<Sample>(TestEndpoint("x", authorization = AuthorizationType.Bearer("endpoint")))

            assertEquals("Bearer endpoint", header)
        }

    @Test
    fun `a provider returning None still sends the request without credentials`(): Unit =
        runBlocking {
            var header: String? = "sentinel"
            val client =
                testClient(authorizationProvider = TestAuthProvider(token = null)) {
                    header = it.headers[HttpHeaders.Authorization]
                    json("""{"id":1,"name":"a"}""")
                }

            client.request<Sample>(TestEndpoint("x"))

            assertNull(header)
        }

    @Test
    fun `a revoked or missing credential is not transient, so the default policy does not retry it`() {
        val error = NetworkError.AuthorizationUnavailable(IllegalStateException("revoked"))
        assertFalse(error.isTransient())
        assertNull(DefaultRetryPolicy(maxRetries = 3).retryDelayMillis(1, HttpMethod.GET, error))
    }

    @Test
    fun `a provider that failed because the device is offline is transient`() {
        assertTrue(NetworkError.AuthorizationUnavailable(UnknownHostException("auth.example.com")).isTransient())
        assertTrue(NetworkError.AuthorizationUnavailable(NetworkError.NoInternetConnection).isTransient())
    }

    @Test
    fun `a connectivity failure wrapped inside an SDK exception is found through the cause chain`() {
        val sdkError = IllegalStateException("token refresh failed", SocketTimeoutException("timed out"))
        assertTrue(NetworkError.AuthorizationUnavailable(sdkError).isTransient())
    }

    @Test
    fun `a cyclic cause chain stops instead of looping`() {
        val cyclic = CyclicException()
        assertFalse(NetworkError.AuthorizationUnavailable(cyclic).isTransient())
    }

    private class CyclicException : Exception("cyclic") {
        override val cause: Throwable get() = this
    }

    @Test
    fun `reports the failure to the event listener with its own error type`(): Unit =
        runBlocking {
            val listener = RecordingListener()
            val client =
                testClient(authorizationProvider = ThrowingProvider(IllegalStateException("revoked")), eventListener = listener) {
                    json("""{"id":1,"name":"a"}""")
                }

            assertFailsWith<NetworkError.AuthorizationUnavailable> { client.request<Sample>(TestEndpoint("x")) }

            assertEquals("AuthorizationUnavailable", listener.events.single().errorType)
        }
}
