package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkClientRequestTest {
    @Test
    fun `decodes a JSON response`(): Unit =
        runBlocking {
            val client = testClient { json("""{"id":1,"name":"ada"}""") }
            val sample: Sample = client.request(TestEndpoint("samples/1"))
            assertEquals(Sample(1, "ada"), sample)
        }

    @Test
    fun `forwards query items and headers`(): Unit =
        runBlocking {
            var seenUrl = ""
            var seenHeader: String? = null
            val client =
                testClient {
                    seenUrl = it.url.toString()
                    seenHeader = it.headers["X-Trace"]
                    json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(
                TestEndpoint(
                    path = "samples",
                    queryItems = listOf("limit" to "20", "offset" to "0"),
                    headers = mapOf("X-Trace" to "abc"),
                ),
            )
            assertTrue(seenUrl.contains("limit=20"), seenUrl)
            assertTrue(seenUrl.contains("offset=0"), seenUrl)
            assertEquals("abc", seenHeader)
        }

    @Test
    fun `sends a typed POST body and decodes the response`(): Unit =
        runBlocking {
            var method = ""
            val client =
                testClient {
                    method = it.method.value
                    json("""{"id":2,"name":"created"}""", HttpStatusCode.Created)
                }
            val result: Sample =
                client.request(TestEndpoint("samples", method = HttpMethod.POST), body = Sample(2, "created"))
            assertEquals("POST", method)
            assertEquals(Sample(2, "created"), result)
        }

    @Test
    fun `execute discards the body for no-content endpoints`(): Unit =
        runBlocking {
            val client = testClient { respond(ByteArray(0), HttpStatusCode.NoContent) }
            client.execute(TestEndpoint("samples/1", method = HttpMethod.DELETE))
        }

    @Test
    fun `maps 401 to Unauthorized`(): Unit =
        runBlocking {
            val client = testClient { json("""{"error":"nope"}""", HttpStatusCode.Unauthorized) }
            assertFailsWith<NetworkError.Unauthorized> { client.request<Sample>(TestEndpoint("samples/1")) }
        }

    @Test
    fun `maps 403 to Forbidden and 404 to NotFound`(): Unit =
        runBlocking {
            assertFailsWith<NetworkError.Forbidden> {
                testClient { json("{}", HttpStatusCode.Forbidden) }.request<Sample>(TestEndpoint("x"))
            }
            assertFailsWith<NetworkError.NotFound> {
                testClient { json("{}", HttpStatusCode.NotFound) }.request<Sample>(TestEndpoint("x"))
            }
        }

    @Test
    fun `maps 500 to ServerError carrying the body`(): Unit =
        runBlocking {
            val client = testClient { json("""{"error":"boom"}""", HttpStatusCode.InternalServerError) }
            val error =
                assertFailsWith<NetworkError.ServerError> { client.request<Sample>(TestEndpoint("x")) }
            assertEquals(500, error.statusCode)
            assertTrue(String(error.body!!).contains("boom"))
        }

    @Test
    fun `wraps malformed JSON in DecodingFailed`(): Unit =
        runBlocking {
            val client = testClient { json("not-json") }
            assertFailsWith<NetworkError.DecodingFailed> { client.request<Sample>(TestEndpoint("x")) }
        }
}
