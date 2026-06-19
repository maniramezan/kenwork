package io.github.maniramezan.kemwork.network

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
}
