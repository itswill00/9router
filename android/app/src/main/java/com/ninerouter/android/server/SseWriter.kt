package com.ninerouter.android.server

import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Translator
import java.io.OutputStream

/** Converts upstream event frames to OpenAI chat chunks and guarantees a
 * terminal finish_reason chunk before the OpenAI [DONE] sentinel. */
internal object SseWriter {
    fun writeBody(output: OutputStream, result: ChatEngine.Outcome.Stream) {
        try {
            write(output, result)
        } finally {
            result.response.close()
        }
    }

    /** Exposed internally so JVM tests can exercise exact downstream SSE framing. */
    internal fun write(output: OutputStream, result: ChatEngine.Outcome.Stream) {
        val source = result.response.body?.source()
        var finishReason: String? = null
        var sawToolCalls = false
        while (true) {
            val line = source?.readUtf8Line() ?: break
            Translator.sseFinishReason(result.targetFormat, line, result.cliEnvelope)?.let {
                finishReason = it
            }
            val converted = Translator.sseDelta(result.targetFormat, line, result.model, result.cliEnvelope)
                ?: continue
            if (converted.contains("[DONE]")) continue
            if (converted.contains("\"tool_calls\"")) sawToolCalls = true
            writeFrame(output, converted)
        }

        // OpenAI-compatible clients require exactly one terminal chunk that
        // carries finish_reason. Upstream may omit it, or the translator may
        // have dropped a content-less terminal frame, so always emit our own.
        val terminalReason = finishReason ?: if (sawToolCalls) "tool_calls" else "stop"
        writeFrame(output, Translator.terminalSseChunk(result.model, terminalReason))
        writeFrame(output, "data: [DONE]")
        output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeFrame(output: OutputStream, data: String) {
        val frame = data + "\n\n"
        val bytes = frame.toByteArray(Charsets.UTF_8)
        output.write(bytes.size.toString(16).toByteArray(Charsets.US_ASCII))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
    }
}
