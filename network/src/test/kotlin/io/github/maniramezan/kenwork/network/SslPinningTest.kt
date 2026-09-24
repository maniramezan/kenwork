package io.github.maniramezan.kenwork.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SslPinningTest {
    private val pin = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="

    @Test
    fun `pinning factory builds per-host policies`() {
        val config =
            SslPinningConfiguration.pinning(
                pinnedHosts = mapOf("api.test" to setOf(SslPinningConfiguration.Pin.publicKeySha256(pin))),
                includesSubdomains = true,
            )
        val policy = config.policies.getValue("api.test")
        assertTrue(policy.includesSubdomains)
        assertEquals(setOf(SslPinningConfiguration.Pin(pin)), policy.pins)
    }

    @Test
    fun `builds an OkHttp certificate pinner without error`() {
        val config =
            SslPinningConfiguration(
                mapOf(
                    "api.test" to
                        SslPinningConfiguration.HostPolicy(
                            pins = setOf(SslPinningConfiguration.Pin(pin)),
                            includesSubdomains = true,
                        ),
                ),
            )
        assertNotNull(config.toCertificatePinner())
    }

    @Test
    fun `rejects a host policy with no pins`() {
        assertFailsWith<IllegalArgumentException> { SslPinningConfiguration.HostPolicy(pins = emptySet()) }
        assertFailsWith<IllegalArgumentException> {
            SslPinningConfiguration.pinning(pinnedHosts = mapOf("api.test" to emptySet()))
        }
    }

    @Test
    fun `rejects a blank pin`() {
        assertFailsWith<IllegalArgumentException> { SslPinningConfiguration.Pin.publicKeySha256(" ") }
    }
}
