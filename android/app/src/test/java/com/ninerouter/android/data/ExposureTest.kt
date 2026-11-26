package com.ninerouter.android.data

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureTest {
    @Test
    fun normalizesBareHostToHttps() {
        assertEquals("https://ai.example.com", Exposure.normalizePublicUrl(" ai.example.com/ "))
    }

    @Test
    fun stripsTrailingSlashAndApiSuffix() {
        assertEquals("https://ai.example.com", Exposure.normalizePublicUrl("https://ai.example.com/v1/"))
        assertEquals(
            "https://ai.example.com/router",
            Exposure.normalizePublicUrl("https://ai.example.com/router/api/v1"),
        )
    }

    @Test
    fun keepsPortAndPath() {
        assertEquals(
            "http://192.168.1.5:8080/9r",
            Exposure.normalizePublicUrl("http://192.168.1.5:8080/9r/"),
        )
    }

    @Test
    fun rejectsUnsafeOrUnusableValues() {
        assertNull(Exposure.normalizePublicUrl("ftp://ai.example.com"))
        assertNull(Exposure.normalizePublicUrl("https://user:pass@ai.example.com"))
        assertNull(Exposure.normalizePublicUrl("https://ai.example.com/?token=1"))
        assertNull(Exposure.normalizePublicUrl("https://"))
        assertNull(Exposure.normalizePublicUrl("http://ai.example.com"))
        assertEquals("", Exposure.normalizePublicUrl("   "))
    }

    @Test
    fun apiRootUsesPublicUrlWhenConfigured() {
        val configured = Settings(publicBaseUrl = "https://ai.example.com/v1")

        assertEquals(
            "https://ai.example.com/v1",
            Exposure.apiRoot(configured, "http://127.0.0.1:20128"),
        )
        assertEquals(
            "http://127.0.0.1:20128/v1",
            Exposure.apiRoot(Settings(), "http://127.0.0.1:20128"),
        )
    }

    @Test
    fun allowsLoopbackPrivateAndTailnetPeers() {
        listOf("127.0.0.1", "192.168.1.20", "10.8.0.2", "172.20.4.3", "100.101.102.103", "169.254.10.1")
            .forEach { assertTrue(it, Exposure.allows(InetAddress.getByName(it))) }

        listOf("::1", "fd7a:115c:a1e0::1", "fe80::1")
            .forEach { assertTrue(it, Exposure.allows(InetAddress.getByName(it))) }
    }

    @Test
    fun rejectsPublicPeers() {
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "203.0.113.9")
            .forEach { assertFalse(it, Exposure.allows(InetAddress.getByName(it))) }

        assertFalse(Exposure.allows(InetAddress.getByName("2001:4860:4860::8888")))
        assertFalse(Exposure.allows(null))
    }
}
