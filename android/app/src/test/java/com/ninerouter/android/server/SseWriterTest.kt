package com.ninerouter.android.server

import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SseWriterTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun stream(sse: String): ChatEngine.Outcome.Stream = ChatEngine.Outcome.Stream(
        response = Response.Builder()
            .request(Request.Builder().url("https://example.test/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(sse.toResponseBody("text/event-stream".toMediaType()))
            .build(),
        targetFormat = Formats.OPENAI,
        sourceFormat = Formats.OPENAI,
        model = "orca/deepseek-v4.1-flash-1017",
    )

    private fun dataLines(sse: String): List<String> {
        val out = ByteArrayOutputStream()
        SseWriter.write(out, stream(sse))
        return out.toString(Charsets.UTF_8.name())
            .lineSequence()
            .filter { it.startsWith("data:") }
            .toList()
    }

    private fun lastChunk(lines: List<String>): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(lines[lines.size - 2].removePrefix("data: ").trim()).jsonObject

    @Test
    fun `emits a terminal finish_reason chunk before DONE`() {
        val lines = dataLines(
            """
            data: {"choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":null}]}

            data: [DONE]

            """.trimIndent()
        )

        assertEquals("data: [DONE]", lines.last())
        val choice = lastChunk(lines)["choices"]!!.jsonArray.single().jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(0, choice["delta"]!!.jsonObject.size)
    }

    @Test
    fun `emits exactly one DONE even when upstream sends one`() {
        val lines = dataLines(
            """
            data: {"choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent()
        )

        assertEquals(1, lines.count { it.trim() == "data: [DONE]" })
    }

    @Test
    fun `preserves length finish reason for max token cutoffs`() {
        val lines = dataLines(
            """
            data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

            data: [DONE]

            """.trimIndent()
        )

        val choice = lastChunk(lines)["choices"]!!.jsonArray.single().jsonObject
        assertEquals("length", choice["finish_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `still terminates a stream that never sends finish reason or DONE`() {
        val lines = dataLines(
            """
            data: {"choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":null}]}

            """.trimIndent()
        )

        assertEquals("data: [DONE]", lines.last())
        val choice = lastChunk(lines)["choices"]!!.jsonArray.single().jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning-only streams still get a terminal chunk`() {
        val lines = dataLines(
            """
            data: {"choices":[{"index":0,"delta":{"reasoning_content":"thinking..."},"finish_reason":null}]}

            data: [DONE]

            """.trimIndent()
        )

        assertTrue(lines.any { it.contains("reasoning_content") })
        val choice = lastChunk(lines)["choices"]!!.jsonArray.single().jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `infers tool_calls when upstream streams tools without a finish reason`() {
        val lines = dataLines(
            """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{}"}}]},"finish_reason":null}]}

            data: [DONE]

            """.trimIndent()
        )

        val choice = lastChunk(lines)["choices"]!!.jsonArray.single().jsonObject
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
    }
}
