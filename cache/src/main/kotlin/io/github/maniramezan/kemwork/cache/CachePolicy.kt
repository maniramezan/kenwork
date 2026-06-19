package io.github.maniramezan.kemwork.cache

/**
 * Strategy describing how a repository should reconcile cached data with the network.
 * Mirrors SwiftyNetwork's `CachePolicy`.
 */
public sealed interface CachePolicy {
    /** Return cached data when present, otherwise load from the network. */
    public data object ReturnCacheElseLoad : CachePolicy

    /** Always load from the network, ignoring (but refreshing) the cache. */
    public data object ReloadIgnoringCache : CachePolicy

    /**
     * Return cached data only if it is no older than [maxAgeMillis]; otherwise load from the
     * network.
     */
    public data class ReturnCacheIfNotExpired(
        public val maxAgeMillis: Long,
    ) : CachePolicy

    public companion object {
        /** The default policy: [ReturnCacheElseLoad]. */
        public val Default: CachePolicy = ReturnCacheElseLoad
    }
}

/**
 * Whether cached data of age [cacheAgeMillis] may be served under this policy.
 */
public fun CachePolicy.shouldUseCachedData(cacheAgeMillis: Long): Boolean =
    when (this) {
        CachePolicy.ReturnCacheElseLoad -> true
        CachePolicy.ReloadIgnoringCache -> false
        is CachePolicy.ReturnCacheIfNotExpired -> cacheAgeMillis <= maxAgeMillis
    }
