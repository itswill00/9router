package com.ninerouter.android.server

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Small per-peer throttle for failed key attempts while the gateway is exposed
 * to the network. Loopback is never throttled, so local development and the
 * in-app Test button keep working.
 */
internal object AuthThrottle {
    private const val WINDOW_MS = 60_000L
    private const val MAX_FAILURES = 8
    private const val BLOCK_MS = 60_000L

    private class Entry {
        var windowStart: Long = 0
        var failures: Int = 0
        var blockedUntil: Long = 0
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun isBlocked(remote: InetAddress?, now: Long = System.currentTimeMillis()): Boolean {
        if (remote == null || remote.isLoopbackAddress) return false
        val entry = entries[key(remote)] ?: return false
        synchronized(entry) {
            if (entry.blockedUntil > now) return true
            if (entry.blockedUntil != 0L) {
                entry.blockedUntil = 0
                entry.failures = 0
                entry.windowStart = 0
            }
            return false
        }
    }

    fun recordFailure(remote: InetAddress?, now: Long = System.currentTimeMillis()) {
        if (remote == null || remote.isLoopbackAddress) return
        trim(now)
        val entry = entries.getOrPut(key(remote)) { Entry() }
        synchronized(entry) {
            if (entry.windowStart == 0L || now - entry.windowStart > WINDOW_MS) {
                entry.windowStart = now
                entry.failures = 0
            }
            entry.failures += 1
            if (entry.failures >= MAX_FAILURES) {
                entry.blockedUntil = now + BLOCK_MS
                entry.failures = 0
                entry.windowStart = 0
            }
        }
    }

    /**
     * Bucket peers by network, not by full address. An IPv6 LAN/tailnet peer
     * normally owns a /64 and can rotate the interface identifier on every
     * attempt, so keying the full address would let it bypass the block.
     */
    private fun key(remote: InetAddress): String {
        val bytes = remote.address
        if (bytes.size == 4) return "v4:" + v4(bytes)
        if (bytes.size == 16) {
            // ::ffff:a.b.c.d shares the IPv4 bucket.
            if (bytes.take(10).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
            ) {
                return "v4:" + v4(bytes.copyOfRange(12, 16))
            }
            val prefix = bytes.copyOfRange(0, 8).joinToString(":") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            return "v6:$prefix::/64"
        }
        return remote.hostAddress ?: remote.toString()
    }

    private fun v4(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xff).toString() }

    private fun trim(now: Long) {
        if (entries.size < 256) return
        entries.entries.removeAll { (_, entry) ->
            synchronized(entry) { entry.blockedUntil <= now && now - entry.windowStart > WINDOW_MS }
        }
    }
}
