package com.ninerouter.android.data

import java.net.InetAddress
import java.net.URI

/**
 * Remote-access policy for the embedded gateway.
 *
 * Loopback stays the default and the only mode that needs no key. Network mode
 * is opt-in: it accepts loopback plus private Wi-Fi, link-local and
 * CGNAT/tailnet peers, always requires an API key, and never serves public
 * source addresses. A public domain is expected to reach the phone through a
 * tunnel or reverse proxy, so the last hop is still a private/tailnet peer.
 */
object Exposure {
    const val LOOPBACK = "loopback"
    const val NETWORK = "network"

    fun enabled(settings: Settings): Boolean = settings.exposure == NETWORK

    /** True for loopback and RFC1918 / link-local / CGNAT / ULA peers. */
    fun allows(remote: InetAddress?): Boolean {
        if (remote == null) return false
        if (remote.isLoopbackAddress) return true
        val bytes = remote.address
        return when (bytes.size) {
            4 -> isPrivateV4(bytes)
            16 -> isPrivateV6(bytes)
            else -> false
        }
    }

    /**
     * Normalizes a public base URL: adds https:// when the scheme is missing,
     * drops a trailing slash and a trailing `/v1` or `/api/v1`, and rejects
     * credentials, query strings and non-HTTP schemes. Returns "" for blank
     * input and null when the value is not a usable URL.
     */
    fun normalizePublicUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.contains("://")) value = "https://$value"
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.userInfo != null) return null
            if (uri.query != null || uri.fragment != null) return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            // A public base URL carries the gateway key. Allow cleartext only
            // for literal loopback/private hosts; anything else must be HTTPS.
            if (scheme == "http" && !isLiteralPrivateHost(host)) return null
            val hostText = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val path = uri.path.orEmpty()
            if (path.any { it == ' ' }) return null
            val root = path.trimEnd('/')
                .removeSuffix("/api/v1")
                .removeSuffix("/v1")
                .trimEnd('/')
            buildString {
                append(scheme).append("://").append(hostText)
                if (uri.port != -1) append(':').append(uri.port)
                append(root)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Configured public root, or null when unset/invalid. */
    fun publicRoot(settings: Settings): String? =
        normalizePublicUrl(settings.publicBaseUrl).orEmpty().takeIf { it.isNotBlank() }

    private fun isLiteralPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val literal = host.removePrefix("[").removeSuffix("]")
        val isIpv4 = literal.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        val isIpv6 = literal.contains(':')
        if (!isIpv4 && !isIpv6) return false
        return runCatching { allows(InetAddress.getByName(literal)) }.getOrDefault(false)
    }

    /** OpenAI-compatible base URL clients should use (public when configured). */
    fun apiRoot(settings: Settings, loopbackBase: String): String =
        publicRoot(settings)?.let { "$it/v1" } ?: "$loopbackBase/v1"

    private fun isPrivateV4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        return when {
            a0 == 10 -> true
            a0 == 172 && a1 in 16..31 -> true
            a0 == 192 && a1 == 168 -> true
            a0 == 100 && a1 in 64..127 -> true
            a0 == 169 && a1 == 254 -> true
            a0 == 127 -> true
            else -> false
        }
    }

    private fun isPrivateV6(b: ByteArray): Boolean {
        // ::1
        if (b.take(15).all { it == 0.toByte() } && b[15] == 1.toByte()) return true
        // ::ffff:a.b.c.d
        if (b.take(10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) {
            return isPrivateV4(b.copyOfRange(12, 16))
        }
        // ::a.b.c.d
        if (b.take(12).all { it == 0.toByte() }) {
            return isPrivateV4(b.copyOfRange(12, 16))
        }
        val first = b[0].toInt() and 0xff
        if (first and 0xfe == 0xfc) return true // fc00::/7 (ULA, includes Tailscale)
        if (first == 0xfe && (b[1].toInt() and 0xc0) == 0x80) return true // fe80::/10
        return false
    }
}
