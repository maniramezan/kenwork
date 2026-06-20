package io.github.maniramezan.kenwork.network

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthTest {
    @Test
    fun `attaches bearer token from the provider`(): Unit =
        runBlocking {
            var header: String? = null
            val client =
                testClient(authorizationProvider = TestAuthProvider("token")) {
                    header = it.headers[HttpHeaders.Authorization]
                    json("""{"id":1,"name":"a"}""")
                }
            client.request<Sample>(TestEndpoint("x"))
            assertEquals("Bearer token", header)
        }

    @Test
    fun `endpoint-level authorization wins over the provider`(): Unit =
        runBlocking {
            var header: String? = null
            val client =
                testClient(authorizationProvider = TestAuthProvider("provider")) {
                    header = it.headers[HttpHeaders.Authorization]
                    json("""{"id":1,"name":"a"}""")
                }
            client.request<Sample>(TestEndpoint("x", authorization = AuthorizationType.Bearer("endpoint")))
            assertEquals("Bearer endpoint", header)
        }

    @Test
    fun `omits authorization when there is no provider and no endpoint auth`(): Unit =
        runBlocking {
            var header: String? = "sentinel"
            val client =
                testClient {
                    header = it.headers[HttpHeaders.Authorization]
                    json("""{"id":1,"name":"a"}""")
                }
            client.request<Sample>(TestEndpoint("x"))
            assertNull(header)
        }

    @Test
    fun `refreshes and retries once after a 401, then succeeds`(): Unit =
        runBlocking {
            val provider = TestAuthProvider("stale")
            val sentHeaders = mutableListOf<String?>()
            var calls = 0
            val client =
                testClient(authorizationProvider = provider) {
                    sentHeaders += it.headers[HttpHeaders.Authorization]
                    calls++
                    if (calls == 1) {
                        json("{}", HttpStatusCode.Unauthorized)
                    } else {
                        json("""{"id":1,"name":"a"}""")
                    }
                }
            val result: Sample = client.request(TestEndpoint("x"))
            assertEquals(Sample(1, "a"), result)
            assertEquals(2, calls)
            assertEquals(1, provider.refreshCount)
            assertEquals(listOf<String?>("Bearer stale", "Bearer fresh"), sentHeaders)
        }

    @Test
    fun `surfaces 401 when there is no provider`(): Unit =
        runBlocking {
            val client = testClient { json("{}", HttpStatusCode.Unauthorized) }
            assertFailsWith<NetworkError.Unauthorized> { client.request<Sample>(TestEndpoint("x")) }
        }

    @Test
    fun `throws AuthorizationRefreshFailed when refresh fails`(): Unit =
        runBlocking {
            val provider = TestAuthProvider("stale", refreshedToken = null, refreshSucceeds = false)
            val client = testClient(authorizationProvider = provider) { json("{}", HttpStatusCode.Unauthorized) }
            assertFailsWith<NetworkError.AuthorizationRefreshFailed> { client.request<Sample>(TestEndpoint("x")) }
        }

    @Test
    fun `does not retry when maxAuthRefreshAttempts is zero`(): Unit =
        runBlocking {
            val provider = TestAuthProvider("stale")
            val client =
                testClient(authorizationProvider = provider, maxAuthRefreshAttempts = 0) {
                    json("{}", HttpStatusCode.Unauthorized)
                }
            assertFailsWith<NetworkError.Unauthorized> { client.request<Sample>(TestEndpoint("x")) }
            assertEquals(0, provider.refreshCount)
        }
}
