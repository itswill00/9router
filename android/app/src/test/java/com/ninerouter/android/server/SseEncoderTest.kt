package com.ninerouter.android.server

import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SseEncoderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun openAiChunk(
        content: String? = null,
        reasoning: String? = null,
        toolCalls: JsonArray? = null,
        id: String = "chatcmpl-abc12345",
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", 0)
        put("model", "m")
        put(
            "choices",
            buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put(
                        "delta",
                        buildJsonObject {
                            if (content != null) put("content", content)
                            if (reasoning != null) put("reasoning_content", reasoning)
                            if (toolCalls != null) put("tool_calls", toolCalls)
                        },
                    )
                    put("finish_reason", JsonNull)
                })
            },
        )
    }

    private fun toolCall(index: Int, id: String?, name: String?, arguments: String?): JsonObject = buildJsonObject {
        put("index", index)
        if (id != null) put("id", id)
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                if (name != null) put("name", name)
                if (arguments != null) put("arguments", arguments)
            },
        )
    }

    private fun dataOf(frame: String): JsonObject =
        json.parseToJsonElement(frame.substringAfter("data: ").trim()).jsonObject

    private fun stream(sse: String, sourceFormat: String, targetFormat: String = Formats.OPENAI): ChatEngine.Outcome.Stream =
        ChatEngine.Outcome.Stream(
            response = Response.Builder()
                .request(Request.Builder().url("https://example.test/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType()))
                .build(),
            targetFormat = targetFormat,
            sourceFormat = sourceFormat,
            model = "orca/deepseek-v4.1-flash-1017",
        )

    private fun write(sse: String, sourceFormat: String, targetFormat: String = Formats.OPENAI): String {
        val out = ByteArrayOutputStream()
        SseWriter.write(out, stream(sse, sourceFormat, targetFormat))
        return out.toString(Charsets.UTF_8.name())
    }

    private fun frames(sse: String, sourceFormat: String, targetFormat: String = Formats.OPENAI): List<String> =
        write(sse, sourceFormat, targetFormat)
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ---- OpenAI Chat ----

    @Test
    fun `openAiChatStreamHasTerminalFinishBeforeDone`() {
        val lines = write(
            """
            data: {"id":"chatcmpl-a","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":null}]}

            data: {"id":"chatcmpl-a","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent(),
            Formats.OPENAI,
        ).lineSequence().filter { it.startsWith("data:") }.toList()

        assertEquals("data: [DONE]", lines.last())
        val choice = dataOf(lines[lines.size - 2])["choices"]!!.jsonArray.single().jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
    }

    // ---- Anthropic Messages ----

    @Test
    fun `anthropicStreamUsesMessageEventsAndNoOpenAiDone`() {
        val body = write(
            """
            data: {"id":"chatcmpl-abc12345","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

            data: {"id":"chatcmpl-abc12345","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent(),
            Formats.CLAUDE,
        )

        assertTrue(body.contains("event: message_start"))
        assertTrue(body.contains("event: content_block_start"))
        assertTrue(body.contains("event: content_block_delta"))
        assertTrue(body.contains("\"type\":\"text_delta\""))
        assertTrue(body.contains("event: message_delta"))
        assertTrue(body.contains("event: message_stop"))
        assertFalse(body.contains("data: [DONE]"))
        assertFalse(body.contains("chat.completion.chunk"))
    }

    @Test
    fun `claudeToolUseFramesPreserveToolIdAndArguments`() {
        val encoder = SseEncoder.forFormat(Formats.CLAUDE, "m")
        val frames = mutableListOf<String>()
        frames += encoder.accept(
            openAiChunk(toolCalls = buildJsonArray { add(toolCall(0, "toolu_1", "Read", "{\"file")) }),
        )
        frames += encoder.accept(
            openAiChunk(toolCalls = buildJsonArray { add(toolCall(0, null, null, "_path\":\"a\"}")) }),
        )
        frames += encoder.finish("tool_calls", sawToolCalls = true, usage = null)

        val start = frames.map { dataOf(it) }.first { it["type"]!!.jsonPrimitive.content == "content_block_start" }
        val block = start["content_block"]!!.jsonObject
        assertEquals("tool_use", block["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", block["id"]!!.jsonPrimitive.content)
        assertEquals("Read", block["name"]!!.jsonPrimitive.content)

        val partial = frames.map { dataOf(it) }
            .first { (it["delta"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "input_json_delta" }
        assertEquals("{\"file_path\":\"a\"}", partial["delta"]!!.jsonObject["partial_json"]!!.jsonPrimitive.content)

        val delta = frames.map { dataOf(it) }.first { it["type"]!!.jsonPrimitive.content == "message_delta" }
        assertEquals("tool_use", delta["delta"]!!.jsonObject["stop_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `maxTokensMapsToLengthOrIncomplete`() {
        val claude = SseEncoder.forFormat(Formats.CLAUDE, "m")
        claude.accept(openAiChunk(content = "cut"))
        val claudeFrames = claude.finish("length", sawToolCalls = false, usage = null).map { dataOf(it) }
        val delta = claudeFrames.first { it["type"]!!.jsonPrimitive.content == "message_delta" }
        assertEquals("max_tokens", delta["delta"]!!.jsonObject["stop_reason"]!!.jsonPrimitive.content)

        val responses = SseEncoder.forFormat(Formats.OPENAI_RESPONSES, "m")
        responses.accept(openAiChunk(content = "cut"))
        val responseFrames = responses.finish("length", sawToolCalls = false, usage = null).map { dataOf(it) }
        val terminal = responseFrames.first { it["type"]!!.jsonPrimitive.content == "response.incomplete" }
        assertEquals("max_output_tokens", terminal["response"]!!.jsonObject["incomplete_details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoningDeltaIsPreserved`() {
        val claude = SseEncoder.forFormat(Formats.CLAUDE, "m")
        val claudeFrames = (claude.accept(openAiChunk(reasoning = "hmm")) + claude.finish("stop", false, null))
            .map { dataOf(it) }
        val thinking = claudeFrames.first { (it["delta"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "thinking_delta" }
        assertEquals("hmm", thinking["delta"]!!.jsonObject["thinking"]!!.jsonPrimitive.content)

        val responses = SseEncoder.forFormat(Formats.OPENAI_RESPONSES, "m")
        val responseFrames = (responses.accept(openAiChunk(reasoning = "hmm")) + responses.finish("stop", false, null))
            .map { dataOf(it) }
        val reasoning = responseFrames.first { it["type"]!!.jsonPrimitive.content == "response.reasoning_summary_text.delta" }
        assertEquals("hmm", reasoning["delta"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exactlyOneTerminalMarkerPerProtocol`() {
        val upstream = """
            data: {"id":"chatcmpl-a","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}]}

            data: {"id":"chatcmpl-a","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()

        assertEquals(1, write(upstream, Formats.OPENAI).split("data: [DONE]").size - 1)
        assertEquals(1, write(upstream, Formats.CLAUDE).split("event: message_stop").size - 1)
        assertEquals(1, write(upstream, Formats.OPENAI_RESPONSES).split("event: response.completed").size - 1)
    }

    // ---- OpenAI Responses ----

    @Test
    fun `responsesStreamUsesResponseEvents`() {
        val body = write(
            """
            data: {"id":"chatcmpl-abc12345","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

            data: {"id":"chatcmpl-abc12345","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent(),
            Formats.OPENAI_RESPONSES,
        )

        assertTrue(body.contains("event: response.created"))
        assertTrue(body.contains("event: response.in_progress"))
        assertTrue(body.contains("event: response.output_item.added"))
        assertTrue(body.contains("event: response.output_text.delta"))
        assertTrue(body.contains("event: response.output_item.done"))
        assertTrue(body.contains("event: response.completed"))
        assertFalse(body.contains("data: [DONE]"))
        assertFalse(body.contains("chat.completion.chunk"))
    }

    @Test
    fun `responsesTerminalRepeatsStreamedOutput`() {
        val encoder = SseEncoder.forFormat(Formats.OPENAI_RESPONSES, "m")
        encoder.accept(openAiChunk(content = "Hello"))
        val body = encoder.finish("stop", sawToolCalls = false, usage = null)
        val completed = body.map { dataOf(it) }.first { it["type"]!!.jsonPrimitive.content == "response.completed" }
        val output = completed["response"]!!.jsonObject["output"]!!.jsonArray
        assertEquals(1, output.size)
        val text = output.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Hello", text)
    }
}
