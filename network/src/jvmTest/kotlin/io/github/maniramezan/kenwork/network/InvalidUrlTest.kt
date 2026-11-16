package io.github.maniramezan.kenwork.network

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvalidUrlTest {
    private fun clientRecordingHits(hits: MutableList<String>): NetworkClient =
        testClient(maxTransientRetries = 2) {
            hits += it.url.toString()
            json("""{"id":1,"name":"ada"}""")
        }

    @Test
    fun `a base URL without a scheme fails with InvalidUrl instead of reaching localhost`(): Unit =
        runBlocking {
            val hits = mutableListOf<String>()
            val error =
                assertFailsWith<NetworkError.InvalidUrl> {
                    clientRecordingHits(hits).request<Sample>(TestEndpoint("samples/1", baseUrl = "api.test"))
                }
            assertEquals("api.test/samples/1", error.url)
            assertTrue(hits.isEmpty())
        }

    @Test
    fun `an unparseable base URL fails with InvalidUrl`(): Unit =
        runBlocking {
            val hits = mutableListOf<String>()
            assertFailsWith<NetworkError.InvalidUrl> {
                clientRecordingHits(hits).request<Sample>(TestEndpoint("samples/1", baseUrl = "https://"))
            }
            assertTrue(hits.isEmpty())
        }

    @Test
    fun `InvalidUrl keeps the URL out of its message and string form`() {
        val error = NetworkError.InvalidUrl("https://api.test/users/secret-token")
        assertFalse("secret-token" in error.message.orEmpty())
        assertFalse("secret-token" in error.toString())
        assertEquals("https://api.test/users/secret-token", error.url)
    }

    @Test
    fun `a valid URL with characters Ktor encodes still works`(): Unit =
        runBlocking {
            val hits = mutableListOf<String>()
            clientRecordingHits(hits).request<Sample>(TestEndpoint("search/hello world"))
            assertEquals(1, hits.size)
        }
}
