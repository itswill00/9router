package com.ninerouter.android.server

import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Translator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.OutputStream

/**
 * Bridges one upstream stream to the client's requested protocol. Upstream
 * dialects are normalized to an OpenAI chat chunk once, then handed to the
 * [SseEncoder] chosen from the response format, which owns the terminal frames
 * (`[DONE]` for OpenAI, `message_stop` for Anthropic, `response.completed` for
 * Responses).
 */
internal object SseWriter {
    private val json = Json { ignoreUnknownKeys = true }

    fun writeBody(output: OutputStream, result: ChatEngine.Outcome.Stream) {
        try {
            write(output, result)
        } finally {
            result.response.close()
        }
    }

    /** Exposed internally so JVM tests can exercise exact downstream SSE framing. */
    internal fun write(output: OutputStream, result: ChatEngine.Outcome.Stream) {
        val encoder = SseEncoder.forFormat(result.sourceFormat, result.model)
        val source = result.response.body?.source()
        var finishReason: String? = null
        var sawToolCalls = false
        var usage: JsonObject? = null
        while (true) {
            val line = source?.readUtf8Line() ?: break
            Translator.sseFinishReason(result.targetFormat, line, result.cliEnvelope)?.let { finishReason = it }
            Translator.sseUsage(result.targetFormat, line, result.cliEnvelope)?.let { usage = it }
            val converted = Translator.sseDelta(result.targetFormat, line, result.model, result.cliEnvelope)
                ?: continue
            if (converted.contains("[DONE]")) continue
            if (converted.contains("\"tool_calls\"")) sawToolCalls = true
            val chunk = try {
                json.parseToJsonElement(converted.removePrefix("data:").trim()) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: continue
            encoder.accept(chunk).forEach { writeFrame(output, it) }
        }

        encoder.finish(finishReason, sawToolCalls, usage).forEach { writeFrame(output, it) }
        output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeFrame(output: OutputStream, frame: String) {
        val bytes = (frame + "\n\n").toByteArray(Charsets.UTF_8)
        output.write(bytes.size.toString(16).toByteArray(Charsets.US_ASCII))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
    }
}
