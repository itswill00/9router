package com.ninerouter.android.server

import com.ninerouter.android.core.TokenEstimator
import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.AppState
import com.ninerouter.android.data.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreCompatibilityRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun countsAnthropicTextAndToolBlocks() {
        val body = parse(
            """
            {
              "system": "You are helpful",
              "messages": [
                {"role": "user", "content": "Hello"},
                {"role": "assistant", "content": [
                  {"type": "text", "text": "Hi there"},
                  {"type": "tool_use", "name": "read", "input": {"path": "a"}}
                ]},
                {"role": "user", "content": [
                  {"type": "tool_result", "tool_use_id": "t1", "content": "result-data"}
                ]}
              ],
              "tools": [{"name": "read", "input_schema": {"type": "object"}}]
            }
            """.trimIndent(),
        )

        // 79 counted characters, divided by four and rounded up.
        assertEquals(20, TokenEstimator.estimateAnthropicInputTokens(body))
    }

    @Test
    fun countTokensRejectsMalformedJson() {
        val result = CompatibilityRoutes.countTokens("{not json")

        assertEquals(400, result.status)
        assertTrue(result.body.contains("Invalid JSON body"))
    }

    @Test
    fun countTokensReturnsInputTokensForValidBody() {
        val result = CompatibilityRoutes.countTokens("""{"messages":[{"role":"user","content":"abcd"}]}""")

        assertEquals(200, result.status)
        assertEquals(1, parse(result.body)["input_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun embeddingRejectsRestrictedKeyModel() {
        val state = AppState(settings = Settings(requireApiKey = true, apiKey = "sk-primary")).apply {
            apiKeys += ApiKey("restricted", key = "sk-limited", allowedModels = listOf("openai/text-embedding-3-small"))
        }
        val headers = mapOf("x-api-key" to "sk-limited")

        assertNull(ApiAccess.deniedModel(headers, buildJsonObject { put("model", "openai/text-embedding-3-small") }, state))
        assertEquals("Model not allowed", ApiAccess.deniedModel(headers, buildJsonObject { put("model", "openai/text-embedding-3-large") }, state))
    }

    @Test
    fun embeddingPassesInputAndDimensions() {
        val body = parse(
            """{"model":"openai/text-embedding-3-small","input":["a","b"],"dimensions":256,"encoding_format":"float","user":"u","unsupported":true}""",
        )

        val payload = EmbeddingsApi.payload(body, "text-embedding-3-small")

        assertEquals("text-embedding-3-small", payload["model"]!!.jsonPrimitive.content)
        assertEquals(2, payload["input"]!!.jsonArray.size)
        assertEquals("256", payload["dimensions"]!!.jsonPrimitive.content)
        assertEquals("float", payload["encoding_format"]!!.jsonPrimitive.content)
        assertEquals("u", payload["user"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("unsupported"))
    }

    @Test
    fun responsesCompactIsExplicitlySupportedOr501() {
        val result = CompatibilityRoutes.compact()

        assertEquals(501, result.status)
        val error = parse(result.body)["error"]!!.jsonObject
        assertEquals("unsupported_operation", error["type"]!!.jsonPrimitive.content)
        assertEquals("unsupported_operation", error["code"]!!.jsonPrimitive.content)
    }
}
