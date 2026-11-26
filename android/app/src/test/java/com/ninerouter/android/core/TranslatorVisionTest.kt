package com.ninerouter.android.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslatorVisionTest {
    private val body = buildJsonObject {
        put("model", "test-model")
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "what is this?") })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,QUJD") })
                    })
                })
            })
        })
    }

    @Test
    fun `openai image parts survive claude translation`() {
        val out = Translator.requestBody(Formats.OPENAI, Formats.CLAUDE, body, "test-model", false)
        val blocks = ((out["messages"] as JsonArray)[0] as JsonObject)["content"] as JsonArray
        val image = blocks[1] as JsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai image parts survive gemini translation`() {
        val out = Translator.requestBody(Formats.OPENAI, Formats.GEMINI, body, "test-model", false)
        val parts = (((out["contents"] as JsonArray)[0] as JsonObject)["parts"]) as JsonArray
        val inline = (parts[1] as JsonObject)["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("QUJD", inline["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai image parts survive responses translation`() {
        val out = Translator.requestBody(Formats.OPENAI, Formats.OPENAI_RESPONSES, body, "test-model", false)
        val content = (((out["input"] as JsonArray)[0] as JsonObject)["content"]) as JsonArray
        val image = content[1] as JsonObject
        assertEquals("input_image", image["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD", image["image_url"]!!.jsonPrimitive.content)
    }
}
