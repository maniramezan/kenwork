package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.KenworkLogger
import io.github.maniramezan.kenwork.network.LogCategory
import io.github.maniramezan.kenwork.network.LogLevel
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.NetworkError
import io.github.maniramezan.kenwork.network.execute
import io.github.maniramezan.kenwork.network.request
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private class PingEndpoint(
    override val method: HttpMethod = HttpMethod.GET,
) : NetworkEndpoint {
    override val baseUrl = "https://api.test"
    override val path = "ping"
}

class FakesTest {
    @Test
    fun `FakeApiClient records requests and returns scripted responses`() =
        runTest {
            val api =
                FakeApiClient { request ->
                    if (request.index == 0) throw NetworkError.Timeout else "pong"
                }

            assertFailsWith<NetworkError.Timeout> { api.request<String>(PingEndpoint()) }
            assertEquals("pong", api.request<String, String>(PingEndpoint(HttpMethod.POST), "hello"))

            val recorded = api.requests
            assertEquals(listOf(0, 1), recorded.map { it.index })
            assertEquals(HttpMethod.POST, recorded[1].endpoint.method)
            assertEquals("hello", recorded[1].body)
            assertEquals(String::class, recorded[1].responseType.type)
        }

    @Test
    fun `FakeApiClient defaults to a Unit response`() =
        runTest {
            val api = FakeApiClient()
            api.execute(PingEndpoint())
            assertEquals(1, api.requests.size)
        }

    @Test
    fun `withRecordedLogs captures lines and restores the logger`() {
        val sinkBefore = KenworkLogger.sink
        val levelBefore = KenworkLogger.level

        val entries =
            withRecordedLogs(LogLevel.INFO) { sink ->
                assertNotSame(sinkBefore, KenworkLogger.sink)
                KenworkLogger.info("hello", LogCategory.CACHE, attributes = mapOf("k" to 1))
                KenworkLogger.debug("filtered out at INFO")
                sink.entries
            }

        assertEquals(1, entries.size)
        assertEquals(RecordingLogSink.Entry(LogLevel.INFO, LogCategory.CACHE, "hello", null, mapOf("k" to 1)), entries.single())
        assertSame(sinkBefore, KenworkLogger.sink)
        assertEquals(levelBefore, KenworkLogger.level)
    }

    @Test
    fun `withRecordedLogs restores the logger when the block throws`() {
        val sinkBefore = KenworkLogger.sink
        assertFailsWith<IllegalStateException> { withRecordedLogs { error("boom") } }
        assertSame(sinkBefore, KenworkLogger.sink)
    }
}
