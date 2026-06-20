package io.github.maniramezan.kenwork.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.util.Base64

/**
 * How an outgoing request is authorized. Mirrors SwiftyNetwork's `AuthorizationType`.
 *
 * A request may carry its own [AuthorizationType] (via [NetworkEndpoint.authorization]); when it
 * is [None], the client falls back to its [AuthorizationProvider], if any.
 */
public sealed interface AuthorizationType {
    /** No authorization header is added. */
    public data object None : AuthorizationType

    /** HTTP Basic auth from a raw [username]/[password] pair (base64-encoded at send time). */
    public data class Basic(
        public val username: String,
        public val password: String,
    ) : AuthorizationType

    /** HTTP Basic auth from a pre-encoded `user:password` [credential]. */
    public data class BasicEncoded(
        public val credential: String,
    ) : AuthorizationType

    /** Bearer token auth (`Authorization: Bearer <token>`). */
    public data class Bearer(
        public val token: String,
    ) : AuthorizationType

    /** API-key auth, sent in [header] (defaults to `X-API-Key`). */
    public data class ApiKey(
        public val key: String,
        public val header: String = "X-API-Key",
    ) : AuthorizationType

    /** An arbitrary [header]/[value] pair. */
    public data class Custom(
        public val header: String,
        public val value: String,
    ) : AuthorizationType
}

/** Applies this authorization to the outgoing Ktor [builder]. */
internal fun AuthorizationType.applyTo(builder: HttpRequestBuilder) {
    when (this) {
        AuthorizationType.None -> Unit
        is AuthorizationType.Basic -> {
            val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
            builder.header(HttpHeaders.Authorization, "Basic $encoded")
        }
        is AuthorizationType.BasicEncoded -> builder.header(HttpHeaders.Authorization, "Basic $credential")
        is AuthorizationType.Bearer -> builder.header(HttpHeaders.Authorization, "Bearer $token")
        is AuthorizationType.ApiKey -> builder.header(header, key)
        is AuthorizationType.Custom -> builder.header(header, value)
    }
}
