package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.AuthorizationType
import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.execute
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SimpleEndpoint(
    override val path: String = "ping",
    override val method: HttpMethod = HttpMethod.GET,
) : NetworkEndpoint {
    override val baseUrl = "https://api.test"
}

@OptIn(ExperimentalCoroutinesApi::class)
class TestingHelpersTest {
    @Test
    fun `mockNetworkClient drives a request and records a telemetry event`(): Unit =
        runBlocking {
            val listener = RecordingNetworkEventListener()
            val client = mockNetworkClient(eventListener = listener) { jsonResponse("""{"ok":true}""") }

            client.execute(SimpleEndpoint())

            assertEquals(1, listener.events.size)
            assertTrue(listener.events[0].isSuccess)
        }

    @Test
    fun `RecordingRetryPolicy records decisions and a FakeReachabilityGate is awaited on retry`(): Unit =
        runBlocking {
            val policy = RecordingRetryPolicy(DefaultRetryPolicy(maxRetries = 1, backoffBaseMillis = 0))
            val gate = FakeReachabilityGate(reachable = true)
            var calls = 0
            val client =
                mockNetworkClient(retryPolicy = policy, reachabilityGate = gate) {
                    calls++
                    if (calls == 1) jsonResponse("{}", HttpStatusCode.InternalServerError) else jsonResponse("{}")
                }

            client.execute(SimpleEndpoint())

            assertEquals(2, calls)
            assertEquals(1, policy.decisions.size)
            assertEquals(0L, policy.decisions[0].delayMillis)
            assertEquals("ServerError", policy.decisions[0].error::class.simpleName)
            assertEquals(1, gate.awaitCount)
        }

    @Test
    fun `FakeReachabilityGate suspends until reachable`() =
        runTest {
            val gate = FakeReachabilityGate(reachable = false)
            assertFalse(gate.isReachable)
            val job = launch { gate.awaitReachable() }
            runCurrent()
            assertTrue(job.isActive)

            gate.setReachable(true)
            advanceUntilIdle()

            assertFalse(job.isActive)
            assertEquals(1, gate.awaitCount)
            assertTrue(gate.isReachable)
        }

    @Test
    fun `FakeAuthorizationProvider refreshes successfully`() =
        runTest {
            val provider = FakeAuthorizationProvider(initialToken = "t", refreshedToken = "r")
            assertEquals(AuthorizationType.Bearer("t"), provider.currentAuthorization())
            assertTrue(provider.refreshAuthorizationIfNeeded())
            assertEquals(AuthorizationType.Bearer("r"), provider.currentAuthorization())
            assertEquals(1, provider.refreshCount)
        }

    @Test
    fun `FakeAuthorizationProvider reports failed refresh and no token`() =
        runTest {
            val provider = FakeAuthorizationProvider(initialToken = null, refreshSucceeds = false)
            assertEquals(AuthorizationType.None, provider.currentAuthorization())
            assertFalse(provider.refreshAuthorizationIfNeeded())
            assertEquals(1, provider.refreshCount)
        }
}
