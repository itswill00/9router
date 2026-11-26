package com.ninerouter.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TranslatorNativeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `claude to claude keeps tool schemas and image blocks`() {
        val body = buildJsonObject {
            put("model", "claude-sonnet")
            put("max_tokens", 1024)
            put("system", "be strict")
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("name", "read_file")
                    put("description", "Read a file")
                    put("input_schema", buildJsonObject { put("type", "object") })
                })
            })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "look") })
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/png")
                                put("data", "AAAA")
                            })
                        })
                    })
                })
            })
        }

        val out = Translator.requestBody(Formats.CLAUDE, Formats.CLAUDE, body, "claude-upstream", true)

        assertEquals("claude-upstream", out["model"]!!.jsonPrimitive.content)
        assertEquals("read_file", out["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val parts = out["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals("image", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", out["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `responses to responses keeps input items and reasoning`() {
        val body = buildJsonObject {
            put("model", "gpt-5-codex")
            put("instructions", "be brief")
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "input_text"); put("text", "hello") })
                    })
                })
            })
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("name", "shell")
                    put("parameters", buildJsonObject { put("type", "object") })
                })
            })
            put("reasoning", buildJsonObject { put("effort", "high") })
        }

        val out = Translator.requestBody(
            Formats.OPENAI_RESPONSES,
            Formats.OPENAI_RESPONSES,
            body,
            "codex-upstream",
            true,
        )

        assertEquals("codex-upstream", out["model"]!!.jsonPrimitive.content)
        assertEquals("shell", out["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("high", out["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("hello", out["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single()
            .jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `claude tool use stream becomes openai tool calls`() {
        val start = Translator.sseDelta(
            Formats.CLAUDE,
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read\"}}",
            "orca/model",
        )
        val delta = Translator.sseDelta(
            Formats.CLAUDE,
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}",
            "orca/model",
        )

        assertNotNull(start)
        assertNotNull(delta)
        val first = json.parseToJsonElement(start!!.removePrefix("data: "))
            .jsonObject["choices"]!!.jsonArray.single().jsonObject["delta"]!!.jsonObject
        val call = first["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("toolu_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("read", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val args = json.parseToJsonElement(delta!!.removePrefix("data: "))
            .jsonObject["choices"]!!.jsonArray.single().jsonObject["delta"]!!.jsonObject["tool_calls"]!!
            .jsonArray.single().jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("{\"path\":", args)
    }

    @Test
    fun `usage frames merge instead of replacing input with zero`() {
        val start = buildJsonObject {
            put("prompt_tokens", 100L)
            put("completion_tokens", 0L)
            put("total_tokens", 100L)
        }
        val end = buildJsonObject {
            put("prompt_tokens", 0L)
            put("completion_tokens", 25L)
            put("total_tokens", 25L)
        }

        val merged = Translator.mergeUsage(start, end)!!

        assertEquals(100L, merged["prompt_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(25L, merged["completion_tokens"]!!.jsonPrimitive.content.toLong())
    }
}
