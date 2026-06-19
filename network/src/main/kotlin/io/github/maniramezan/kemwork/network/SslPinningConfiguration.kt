package io.github.maniramezan.kemwork.network

import okhttp3.CertificatePinner

/**
 * Certificate (public-key) pinning configuration. Mirrors SwiftyNetwork's
 * `SSLPinningConfiguration`, implemented on top of OkHttp's [CertificatePinner].
 *
 * OkHttp pins the Subject Public Key Info (SPKI) SHA-256, so pins are supplied as base64 SPKI
 * hashes via [Pin.publicKeySha256].
 *
 * @property policies per-host pinning policy keyed by hostname.
 */
public class SslPinningConfiguration(
    public val policies: Map<String, HostPolicy>,
) {
    /** A pinning policy for a single host. */
    public data class HostPolicy(
        public val pins: Set<Pin>,
        public val includesSubdomains: Boolean = false,
    )

    /** A single SPKI SHA-256 pin (base64). */
    public data class Pin(
        public val sha256PublicKeyBase64: String,
    ) {
        public companion object {
            /** Builds a public-key pin from a base64-encoded SPKI SHA-256 hash. */
            public fun publicKeySha256(base64: String): Pin = Pin(base64)
        }
    }

    public companion object {
        /** Builds a configuration pinning [pinnedHosts] with a shared [includesSubdomains] flag. */
        public fun pinning(
            pinnedHosts: Map<String, Set<Pin>>,
            includesSubdomains: Boolean = false,
        ): SslPinningConfiguration = SslPinningConfiguration(pinnedHosts.mapValues { HostPolicy(it.value, includesSubdomains) })
    }
}

/** Builds an OkHttp [CertificatePinner] from this configuration. */
internal fun SslPinningConfiguration.toCertificatePinner(): CertificatePinner {
    val builder = CertificatePinner.Builder()
    policies.forEach { (host, policy) ->
        val patterns = if (policy.includesSubdomains) listOf(host, "**.$host") else listOf(host)
        policy.pins.forEach { pin ->
            val sha = "sha256/${pin.sha256PublicKeyBase64}"
            patterns.forEach { pattern -> builder.add(pattern, sha) }
        }
    }
    return builder.build()
}
