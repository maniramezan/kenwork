package io.github.maniramezan.kemwork.cache

/**
 * A stable, hashable identifier for a cached value.
 *
 * Mirrors SwiftyNetwork's `CacheKey`. Build keys from raw strings, URLs, or structured
 * components so that the same logical resource always maps to the same key.
 *
 * @property rawValue the underlying string identity used for equality and hashing.
 */
public data class CacheKey(
    public val rawValue: String,
) {
    public companion object {
        /** A key derived from a URL string. */
        public fun url(url: String): CacheKey = CacheKey(url)

        /** A key composed of ordered [components] joined by [separator]. */
        public fun components(
            components: List<String>,
            separator: String = ":",
        ): CacheKey = CacheKey(components.joinToString(separator))

        /** A key scoped to a [userId] for a given [resource], e.g. `user:42:profile`. */
        public fun user(
            userId: String,
            resource: String,
        ): CacheKey = CacheKey("user:$userId:$resource")

        /**
         * A key for an [endpoint] plus sorted [parameters], e.g. `videos?limit=20&offset=0`.
         * Parameters are sorted by name so key identity is independent of insertion order.
         */
        public fun endpoint(
            endpoint: String,
            parameters: Map<String, String> = emptyMap(),
        ): CacheKey {
            if (parameters.isEmpty()) return CacheKey(endpoint)
            val query =
                parameters.entries
                    .sortedBy { it.key }
                    .joinToString("&") { "${it.key}=${it.value}" }
            return CacheKey("$endpoint?$query")
        }
    }
}
