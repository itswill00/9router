package com.ninerouter.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderNodeKeysTest {
    private fun node(apiKey: String, apiKeys: List<String> = emptyList()) = ProviderNode(
        id = "node",
        prefix = "custom",
        baseUrl = "https://example.test/v1",
        apiKey = apiKey,
        apiKeys = apiKeys,
    )

    @Test
    fun legacySingleKeyStillWorks() {
        assertEquals(listOf("sk-old"), node("sk-old").effectiveKeys())
    }

    @Test
    fun keysAreTrimmedDeduplicatedAndBlankFree() {
        val keys = node("a", listOf(" a ", "b", "", "a", "c")).effectiveKeys()
        assertEquals(listOf("a", "b", "c"), keys)
    }

    @Test
    fun savingKeysKeepsLegacyFirstKeyAndResetsCursor() {
        val node = node("", listOf("a", "b")).apply { keyCursor = 1 }
        node.setKeys(listOf("", " x ", "y", "x"))
        assertEquals(listOf("x", "y"), node.apiKeys)
        assertEquals("x", node.apiKey)
        assertEquals(0, node.keyCursor)
    }
}
