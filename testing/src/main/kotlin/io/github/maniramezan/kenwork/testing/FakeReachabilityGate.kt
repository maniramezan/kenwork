package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.ReachabilityGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/**
 * A controllable [ReachabilityGate] for tests. Starts [reachable] (default `true`, so
 * [awaitReachable] returns immediately); flip it with [setReachable] to make a pending
 * [awaitReachable] resume. [awaitCount] records how many times the gate was awaited.
 */
public class FakeReachabilityGate(
    reachable: Boolean = true,
) : ReachabilityGate {
    private val state = MutableStateFlow(reachable)
    private val awaits = AtomicInteger(0)

    /** How many times [awaitReachable] has been called. */
    public val awaitCount: Int get() = awaits.get()

    /** Whether the gate currently reports connectivity. */
    public val isReachable: Boolean get() = state.value

    /** Sets connectivity, resuming any coroutine suspended in [awaitReachable] when set to `true`. */
    public fun setReachable(reachable: Boolean) {
        state.value = reachable
    }

    override suspend fun awaitReachable() {
        awaits.incrementAndGet()
        state.first { it }
    }
}
