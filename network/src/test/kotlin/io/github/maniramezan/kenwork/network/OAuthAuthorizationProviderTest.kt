package io.github.maniramezan.kenwork.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuthAuthorizationProviderTest {
    @Test
    fun `currentAuthorization returns the bearer token`() =
        runTest {
            val provider = OAuthAuthorizationProvider("abc") { "never" }
            assertEquals(AuthorizationType.Bearer("abc"), provider.currentAuthorization())
        }

    @Test
    fun `refresh updates the access token`() =
        runTest {
            val provider = OAuthAuthorizationProvider("old") { "new" }
            assertTrue(provider.refreshAuthorizationIfNeeded())
            assertEquals(AuthorizationType.Bearer("new"), provider.currentAuthorization())
        }

    @Test
    fun `failed refresh reports false and keeps the old token`() =
        runTest {
            val provider = OAuthAuthorizationProvider("old") { null }
            assertTrue(!provider.refreshAuthorizationIfNeeded())
            assertEquals(AuthorizationType.Bearer("old"), provider.currentAuthorization())
        }

    @Test
    fun `coalesces concurrent refreshes into a single handler call`() =
        runTest {
            val handlerCalls = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            val provider =
                OAuthAuthorizationProvider("old") {
                    handlerCalls.incrementAndGet()
                    gate.await()
                    "new"
                }

            val jobs = List(12) { async { provider.refreshAuthorizationIfNeeded() } }
            // Let every coroutine reach the shared in-flight refresh before it completes.
            repeat(5) { yield() }
            gate.complete(Unit)
            val results = jobs.awaitAll()

            assertEquals(1, handlerCalls.get())
            assertTrue(results.all { it })
            assertEquals(AuthorizationType.Bearer("new"), provider.currentAuthorization())
        }
}
