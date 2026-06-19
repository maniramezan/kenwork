package io.github.maniramezan.kemwork.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthorizationTypeTest {
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
