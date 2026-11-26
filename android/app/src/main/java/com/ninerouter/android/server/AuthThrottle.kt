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

    private fun key(remote: InetAddress): String = remote.hostAddress ?: remote.toString()

    private fun trim(now: Long) {
        if (entries.size < 256) return
        entries.entries.removeAll { (_, entry) ->
            synchronized(entry) { entry.blockedUntil <= now && now - entry.windowStart > WINDOW_MS }
        }
    }
}
