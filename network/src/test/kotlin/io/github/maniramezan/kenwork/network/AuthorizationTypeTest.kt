package io.github.maniramezan.kenwork.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthorizationTypeTest {
    @Test
    fun `string representations redact every credential type`() {
        val credentials =
            listOf(
                AuthorizationType.Basic("private-user", "private-password") to "Basic(<redacted>)",
                AuthorizationType.BasicEncoded("private-encoded") to "BasicEncoded(<redacted>)",
                AuthorizationType.Bearer("private-token") to "Bearer(<redacted>)",
                AuthorizationType.ApiKey("private-key") to "ApiKey(<redacted>)",
                AuthorizationType.Custom("X-Private", "private-value") to "Custom(<redacted>)",
            )
        credentials.forEach { (authorization, expected) -> assertEquals(expected, authorization.toString()) }
    }

    @Test
    fun `resolved authorization replaces conflicting headers`() {
        val credentials =
            listOf(
                AuthorizationType.Basic("u", "p") to HttpHeaders.Authorization,
                AuthorizationType.BasicEncoded("encoded") to HttpHeaders.Authorization,
                AuthorizationType.Bearer("token") to HttpHeaders.Authorization,
                AuthorizationType.ApiKey("key") to "X-API-Key",
                AuthorizationType.Custom("X-Auth", "value") to "X-Auth",
            )
        credentials.forEach { (authorization, header) ->
            val builder = HttpRequestBuilder()
            builder.headers.append(header.lowercase(), "stale")
            builder.headers.append(header, "also-stale")
            authorization.applyTo(builder)
            assertEquals(listOf(requireNotNull(applied(authorization, header))), builder.headers.getAll(header))
        }
    }

    private fun applied(
        type: AuthorizationType,
        header: String = HttpHeaders.Authorization,
    ): String? {
        val builder = HttpRequestBuilder()
        type.applyTo(builder)
        return builder.headers[header]
    }

    @Test
    fun `basic encodes credentials`() {
        // base64("u:p") == "dTpw"
        assertEquals("Basic dTpw", applied(AuthorizationType.Basic("u", "p")))
    }

    @Test
    fun `basic encoded passes the credential through`() {
        assertEquals("Basic abc123", applied(AuthorizationType.BasicEncoded("abc123")))
    }

    @Test
    fun `bearer sets the token`() {
        assertEquals("Bearer t0ken", applied(AuthorizationType.Bearer("t0ken")))
    }

    @Test
    fun `api key uses the configured header`() {
        assertEquals("secret", applied(AuthorizationType.ApiKey("secret"), "X-API-Key"))
    }

    @Test
    fun `custom sets an arbitrary header`() {
        assertEquals("value", applied(AuthorizationType.Custom("X-Custom", "value"), "X-Custom"))
    }

    @Test
    fun `none adds no authorization`() {
        assertNull(applied(AuthorizationType.None))
    }
}
