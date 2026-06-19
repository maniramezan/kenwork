package io.github.maniramezan.kemwork.testing

import io.github.maniramezan.kemwork.network.NetworkEvent
import io.github.maniramezan.kemwork.network.NetworkEventListener

/** A [NetworkEventListener] that records every emitted [NetworkEvent] for assertions. */
public class RecordingNetworkEventListener : NetworkEventListener {
    private val lock = Any()
    private val recorded = mutableListOf<NetworkEvent>()

    /** A snapshot of all events recorded so far, in order. */
    public val events: List<NetworkEvent>
        get() = synchronized(lock) { recorded.toList() }

    override fun onEvent(event: NetworkEvent) {
        synchronized(lock) { recorded.add(event) }
    }
}
