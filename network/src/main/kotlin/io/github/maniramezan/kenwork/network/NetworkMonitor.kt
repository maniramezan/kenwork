package io.github.maniramezan.kenwork.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Coarse network reachability state. Mirrors SwiftyNetwork's `NetworkReachability`. */
public enum class NetworkReachability {
    REACHABLE,
    UNREACHABLE,
    UNKNOWN,
}

/**
 * A connectivity gate the retry path can wait on. Implemented by [NetworkMonitor] (via
 * [NetworkMonitor.asReachabilityGate]), but kept abstract so [NetworkClient] doesn't depend on any
 * concrete connectivity source and can be tested without one.
 */
public fun interface ReachabilityGate {
    /** Suspends until connectivity is (believed) available, returning immediately if it already is. */
    public suspend fun awaitReachable()
}

/**
 * Observes device connectivity via [ConnectivityManager]. The Kotlin counterpart of
 * SwiftyNetwork's `NetworkMonitor` (which wraps `NWPathMonitor`).
 *
 * [status] is [NetworkReachability.UNKNOWN] until [start] is called. [updates] is a hot
 * [StateFlow] that replays the current value to new collectors and then emits changes — the
 * Kotlin analog of SwiftyNetwork's `AsyncStream`.
 */
public class NetworkMonitor(
    context: Context,
) {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val mutableStatus = MutableStateFlow(NetworkReachability.UNKNOWN)

    /** A hot stream of reachability changes (replays the current value on subscribe). */
    public val updates: StateFlow<NetworkReachability> = mutableStatus.asStateFlow()

    /** The current reachability. */
    public val status: NetworkReachability get() = mutableStatus.value

    /** Whether the network is currently reachable. */
    public val isReachable: Boolean get() = mutableStatus.value == NetworkReachability.REACHABLE

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mutableStatus.value = NetworkReachability.REACHABLE
            }

            override fun onLost(network: Network) {
                mutableStatus.value = currentReachability()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                mutableStatus.value =
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        NetworkReachability.REACHABLE
                    } else {
                        NetworkReachability.UNREACHABLE
                    }
            }
        }

    /** Begins monitoring. Seeds [status] from the current network, then listens for changes. */
    public fun start() {
        val manager = connectivityManager ?: return
        mutableStatus.value = currentReachability()
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    /** Stops monitoring. */
    public fun stop() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    /**
     * Suspends until [status] becomes [NetworkReachability.REACHABLE], returning immediately if it
     * already is. Requires [start] to have been called; otherwise the status stays
     * [NetworkReachability.UNKNOWN] and this never resumes — bound it with `withTimeout` for
     * standalone use (the retry path already does).
     */
    public suspend fun awaitReachable() {
        updates.first { it == NetworkReachability.REACHABLE }
    }

    /** Adapts this monitor to a [ReachabilityGate] for [NetworkClientConfiguration.reachabilityGate]. */
    public fun asReachabilityGate(): ReachabilityGate = ReachabilityGate { awaitReachable() }

    private fun currentReachability(): NetworkReachability {
        val manager = connectivityManager ?: return NetworkReachability.UNKNOWN
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        return when {
            capabilities == null -> NetworkReachability.UNREACHABLE
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkReachability.REACHABLE
            else -> NetworkReachability.UNREACHABLE
        }
    }
}
