package com.ninerouter.android.core

import com.ninerouter.android.data.ProviderNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeProbeTest {
    private fun node(
        baseUrl: String,
        type: String = "openai-compatible",
        apiType: String = "chat",
    ) = ProviderNode(
        id = "test",
        prefix = "test",
        name = "Test",
        type = type,
        apiType = apiType,
        baseUrl = baseUrl,
    )

    @Test
    fun `chat completions endpoint is appended once`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            NodeProbe.chatUrl(node("https://api.example.com/v1")),
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            NodeProbe.chatUrl(node("https://api.example.com/v1/chat/completions")),
        )
    }

    @Test
    fun `responses and anthropic endpoints use their own paths`() {
        assertEquals(
            "https://api.example.com/v1/responses",
            NodeProbe.chatUrl(node("https://api.example.com/v1", apiType = "responses")),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            NodeProbe.chatUrl(node("https://api.anthropic.com/v1", type = "anthropic-compatible")),
        )
    }

    @Test
    fun `root url strips any terminal endpoint`() {
        assertEquals("https://api.example.com/v1", NodeProbe.rootUrl(node("https://api.example.com/v1/chat/completions")))
        assertEquals("https://api.example.com/v1", NodeProbe.rootUrl(node("https://api.example.com/v1/responses")))
        assertEquals("https://api.example.com/v1", NodeProbe.rootUrl(node("https://api.example.com/v1/models")))
        assertEquals("https://api.example.com/v1", NodeProbe.rootUrl(node("https://api.example.com/v1/")))
    }

    @Test
    fun `model list url is stable`() {
        assertEquals(
            "https://api.example.com/v1/models",
            NodeProbe.modelsUrl(node("https://api.example.com/v1/chat/completions")),
        )
    }

    @Test
    fun `node type helpers are case insensitive`() {
        val anthropic = node("https://api.example.com/v1", type = "Anthropic-Compatible")
        assertTrue(NodeProbe.isAnthropic(anthropic))
        assertFalse(NodeProbe.isResponses(anthropic))
        assertTrue(NodeProbe.typeLabel(anthropic).contains("Anthropic"))

        val responses = node("https://api.example.com/v1", apiType = "Responses")
        assertTrue(NodeProbe.isResponses(responses))
    }
}
