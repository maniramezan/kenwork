package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.testing.FakeReachabilityGate
import io.github.maniramezan.kenwork.testing.jsonResponse
import io.github.maniramezan.kenwork.testing.mockNetworkClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MutationQueueConnectivityTest {
    @Test
    fun `an offline mutation stays persisted until the client gate opens`() =
        runTest {
            val gate = FakeReachabilityGate(reachable = false)
            val sends = AtomicInteger()
            val client =
                mockNetworkClient(reachabilityGate = gate) {
                    sends.incrementAndGet()
                    jsonResponse("")
                }
            val store = InMemoryMutationStore()
            val queue = MutationQueue(client, backgroundScope, store = store)
            val key = MutationKey("offline")

            try {
                queue.enqueue(key, SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec)
                runCurrent()

                assertEquals(0, sends.get())
                assertEquals(1, gate.awaitCount)
                assertEquals(1, store.loadAll().size)

                gate.setReachable(true)
                queue.statusFlow(key).first { it is MutationStatus.Succeeded }
                settle()

                assertEquals(1, sends.get())
                assertTrue(store.loadAll().isEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun `cancel interrupts a client connectivity wait and removes persisted work`() =
        runTest {
            val gate = FakeReachabilityGate(reachable = false)
            val sends = AtomicInteger()
            val client =
                mockNetworkClient(reachabilityGate = gate) {
                    sends.incrementAndGet()
                    jsonResponse("")
                }
            val store = InMemoryMutationStore()
            val queue =
                MutationQueue(
                    client,
                    backgroundScope,
                    store = store,
                    defaultRetryPolicy = DefaultRetryPolicy(backoffBaseMillis = 0),
                )
            val key = MutationKey("offline")

            try {
                queue.enqueue(key, SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec)
                runCurrent()
                queue.cancel(key)
                gate.setReachable(true)
                settle()

                assertEquals(0, sends.get())
                assertEquals(null, queue.statusFlow(key).value)
                assertTrue(store.loadAll().isEmpty())
            } finally {
                client.close()
            }
        }
}
