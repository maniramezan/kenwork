package io.github.maniramezan.kenwork.network

/**
 * A type-safe description of one HTTP endpoint. Mirrors SwiftyNetwork's `NetworkEndpoint`.
 *
 * Implement this (typically as an `object` or `data class` per route) and pass it to
 * [NetworkClient]. Only [baseUrl], [path], and [method] are required; the rest default sensibly.
 */
public interface NetworkEndpoint {
    /** The scheme + host (+ optional base path), e.g. `https://api.example.com`. */
    public val baseUrl: String

    /** The path relative to [baseUrl], e.g. `videos/42`. Leading/trailing slashes are normalized. */
    public val path: String

    /** The HTTP method. */
    public val method: HttpMethod

    /** Query parameters appended to the URL, or `null` for none. */
    public val queryItems: List<Pair<String, String>>? get() = null

    /** Extra request headers, or `null` for none. */
    public val headers: Map<String, String>? get() = null

    /** Per-request authorization; defaults to [AuthorizationType.None] (use the client provider). */
    public val authorization: AuthorizationType get() = AuthorizationType.None

    /** A raw request body, or `null`. Ignored when a typed body is passed to the client. */
    public val body: ByteArray? get() = null
}

/**
 * Joins [NetworkEndpoint.baseUrl] and [NetworkEndpoint.path] with exactly one separating slash,
 * regardless of how either side is punctuated. Matches SwiftyNetwork's URL normalization.
 */
internal fun NetworkEndpoint.resolvedUrl(): String {
    val base = baseUrl.trimEnd('/')
    val trimmedPath = path.trimStart('/')
    return if (trimmedPath.isEmpty()) base else "$base/$trimmedPath"
}
