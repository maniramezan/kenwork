package io.github.maniramezan.kenwork.network

/** Coarse network reachability state. Mirrors SwiftyNetwork's `NetworkReachability`. */
public enum class NetworkReachability {
    /** The default network is validated as having internet access. */
    REACHABLE,

    /** There is no default network, or it is not validated. */
    UNREACHABLE,

    /** Not known yet, because monitoring has not started. */
    UNKNOWN,
}

/**
 * A connectivity gate the retry path can wait on. On Android, `NetworkMonitor.asReachabilityGate()`
 * provides one. It is kept abstract so [NetworkClient] doesn't depend on any concrete connectivity
 * source: on the desktop JVM, or in tests, implement it over whatever signal you have.
 */
public fun interface ReachabilityGate {
    /** Suspends until connectivity is (believed) available, returning immediately if it already is. */
    public suspend fun awaitReachable()
}
