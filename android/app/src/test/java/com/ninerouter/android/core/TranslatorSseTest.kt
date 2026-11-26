package com.ninerouter.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TranslatorSseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `extracts upstream OpenAI finish reason from terminal frame`() {
        assertEquals(
            "length",
            Translator.sseFinishReason(
                Formats.OPENAI,
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
            ),
        )
    }

    @Test
    fun `maps Claude max tokens stop reason to OpenAI length`() {
        assertEquals(
            "length",
            Translator.sseFinishReason(
                Formats.CLAUDE,
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}",
            ),
        )
    }

    @Test
    fun `synthesizes terminal OpenAI chunk with finish reason`() {
        val terminal = json.parseToJsonElement(Translator.terminalSseChunk("orca/model", "stop").removePrefix("data: "))
            .jsonObject
        val choice = terminal["choices"]!!.jsonArray.single().jsonObject

        assertEquals("chat.completion.chunk", terminal["object"]!!.jsonPrimitive.content)
        assertEquals("orca/model", terminal["model"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(0, choice["delta"]!!.jsonObject.size)
    }

    @Test
    fun `preserves reasoning only deltas as OpenAI reasoning_content`() {
        val converted = Translator.sseDelta(
            Formats.OPENAI,
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"},\"finish_reason\":null}]}",
            "orca/model",
        )

        assertNotNull(converted)
        val delta = json.parseToJsonElement(converted!!.removePrefix("data: "))
            .jsonObject["choices"]!!.jsonArray.single().jsonObject["delta"]!!.jsonObject
        assertEquals("thinking", delta["reasoning_content"]!!.jsonPrimitive.content)
        assertNull(delta["content"])
    }

    @Test
    fun `forwards tool call deltas without dropping the index and id`() {
        val raw = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}]},\"finish_reason\":null}]}"
        val converted = Translator.sseDelta(Formats.OPENAI, raw, "orca/model")

        assertNotNull(converted)
        val delta = json.parseToJsonElement(converted!!.removePrefix("data: "))
            .jsonObject["choices"]!!.jsonArray.single().jsonObject["delta"]!!.jsonObject
        val call = delta["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals(0, call["index"]!!.jsonPrimitive.content.toInt())
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
    }
}
