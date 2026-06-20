package io.github.maniramezan.kenwork.network

import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExceptionMappingTest {
    @Test
    fun `maps unknown host to NoInternetConnection`(): Unit =
        runBlocking {
            val client = testClient { throw UnknownHostException("api.test") }
            assertFailsWith<NetworkError.NoInternetConnection> { client.request<Sample>(TestEndpoint("x")) }
        }

    @Test
    fun `maps connect failures to NoInternetConnection`(): Unit =
        runBlocking {
            val client = testClient { throw ConnectException("refused") }
            assertFailsWith<NetworkError.NoInternetConnection> { client.request<Sample>(TestEndpoint("x")) }
        }

    @Test
    fun `maps unexpected failures to Underlying`(): Unit =
        runBlocking {
            val client = testClient { throw IllegalArgumentException("weird") }
            assertFailsWith<NetworkError.Underlying> { client.request<Sample>(TestEndpoint("x")) }
        }
}
