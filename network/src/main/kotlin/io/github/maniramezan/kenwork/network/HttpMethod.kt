package io.github.maniramezan.kenwork.network

/** HTTP request methods supported by [NetworkEndpoint]. Mirrors SwiftyNetwork's `HTTPMethod`. */
public enum class HttpMethod(
    public val value: String,
) {
    GET("GET"),
    HEAD("HEAD"),
    POST("POST"),
    PUT("PUT"),
    PATCH("PATCH"),
    DELETE("DELETE"),
    OPTIONS("OPTIONS"),
    ;

    /**
     * Whether this method is idempotent (safe to transparently retry). Per RFC 7231 every method
     * except [POST] and [PATCH] is idempotent.
     */
    public val isIdempotent: Boolean
        get() = this != POST && this != PATCH
}
