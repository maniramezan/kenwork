package io.github.maniramezan.kenwork.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeoutTest {
    @Test
    fun `an endpoint timeout overrides the client's request timeout`(): Unit =
        runBlocking {
            val client =
                testClient {
                    delay(5_000)
                    json("""{"id":1,"name":"ada"}""")
                }
            val started = System.nanoTime()
            assertFailsWith<NetworkError.Timeout> {
                client.request<Sample>(TestEndpoint("slow", timeoutMillis = 100))
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000)
        }

    @Test
    fun `endpoints without a timeout keep the client's request timeout`(): Unit =
        runBlocking {
            val client =
                testClient {
                    delay(200)
                    json("""{"id":1,"name":"ada"}""")
                }
            assertEquals(Sample(1, "ada"), client.request<Sample>(TestEndpoint("slow")))
        }

    @Test
    fun `socketTimeoutMillis bounds a server that accepts and never answers`(): Unit =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val accepted = mutableListOf<java.net.Socket>()
                val acceptor = thread(isDaemon = true) { runCatching { accepted += server.accept() } }
                val client =
                    NetworkClient(
                        NetworkClientConfiguration(
                            retryPolicy = RetryPolicy.None,
                            socketTimeoutMillis = 200,
                            connectTimeoutMillis = 2_000,
                        ),
                    )
                try {
                    val started = System.nanoTime()
                    assertFailsWith<NetworkError.Timeout> {
                        client.request<Sample>(TestEndpoint("hang", baseUrl = "http://127.0.0.1:${server.localPort}"))
                    }
                    assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
                } finally {
                    client.close()
                    accepted.forEach { it.close() }
                    acceptor.join(1_000)
                }
            }
        }
}
