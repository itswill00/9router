package com.ninerouter.android.server

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthThrottleTest {
    @Test
    fun loopbackIsNeverBlocked() {
        val loopback = InetAddress.getByName("127.0.0.1")

        repeat(20) { AuthThrottle.recordFailure(loopback, 1_000L) }

        assertFalse(AuthThrottle.isBlocked(loopback, 1_000L))
    }

    @Test
    fun repeatedFailuresBlockAPeerForOneMinute() {
        val peer = InetAddress.getByName("192.168.1.50")
        val start = 5_000L

        repeat(7) { AuthThrottle.recordFailure(peer, start) }
        assertFalse(AuthThrottle.isBlocked(peer, start))

        AuthThrottle.recordFailure(peer, start)
        assertTrue(AuthThrottle.isBlocked(peer, start))
        assertTrue(AuthThrottle.isBlocked(peer, start + 59_000))
        assertFalse(AuthThrottle.isBlocked(peer, start + 61_000))
    }
}
