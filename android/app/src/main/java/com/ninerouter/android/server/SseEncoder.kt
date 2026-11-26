package com.ninerouter.android.server

import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.Translator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TreeMap

private val sseJson = Json { ignoreUnknownKeys = true }

/**
 * Turns the pivot OpenAI chat chunk produced by [Translator.sseDelta] into the
 * wire protocol the client actually asked for. The stream writer normalizes
 * every upstream dialect to OpenAI once, then hands each chunk to the encoder
 * selected from the response format; the encoder owns the terminal framing.
 *
 * Ported from `open-sse/translator/response/openai-to-claude.js` and
 * `open-sse/translator/response/openai-responses.js` so Android clients get the
 * same event shapes as the original gateway instead of an OpenAI chunk envelope.
 */
internal interface SseEncoder {
    /** Convert one upstream OpenAI chat chunk into zero or more downstream `data:` frames. */
    fun accept(chunk: JsonObject): List<String>

    /** Emit the protocol terminal frames once the upstream body is exhausted. */
    fun finish(finishReason: String?, sawToolCalls: Boolean, usage: JsonObject?): List<String>

    companion object {
        fun forFormat(format: String, model: String): SseEncoder = when (format) {
            Formats.CLAUDE -> AnthropicSseEncoder(model)
            Formats.OPENAI_RESPONSES -> ResponsesSseEncoder(model)
            else -> OpenAiSseEncoder(model)
        }
    }
}

private fun text(value: kotlinx.serialization.json.JsonElement?): String? =
    (value as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

private fun num(value: kotlinx.serialization.json.JsonElement?): Long =
    (value as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

private fun encode(data: JsonObject): String = sseJson.encodeToString(data)

/** OpenAI Chat Completions: unchanged framing, one terminal finish chunk then `[DONE]`. */
private class OpenAiSseEncoder(private val model: String) : SseEncoder {
    override fun accept(chunk: JsonObject): List<String> = listOf("data: " + encode(chunk))

    override fun finish(finishReason: String?, sawToolCalls: Boolean, usage: JsonObject?): List<String> = listOf(
        Translator.terminalSseChunk(
            model,
            finishReason ?: if (sawToolCalls) "tool_calls" else "stop",
            usage,
        ),
        "data: [DONE]",
    )
}

/**
 * Anthropic Messages: `message_start`, thinking/text/tool content blocks, then
 * `message_delta` (stop_reason + usage) and `message_stop`. No `[DONE]` — that
 * sentinel is OpenAI-only and breaks Anthropic clients.
 */
private class AnthropicSseEncoder(private val model: String) : SseEncoder {
    private var messageStartSent = false
    private var messageId = "msg_" + System.currentTimeMillis()
    private var nextBlockIndex = 0
    private var thinkingBlockStarted = false
    private var thinkingBlockIndex = -1
    private var textBlockStarted = false
    private var textBlockClosed = false
    private var textBlockIndex = -1
    private val toolCalls = LinkedHashMap<Int, ToolState>()
    private val toolArgBuffers = HashMap<Int, StringBuilder>()

    private data class ToolState(val id: String, val name: String, val blockIndex: Int)

    override fun accept(chunk: JsonObject): List<String> {
        val choices = chunk["choices"] as? JsonArray ?: return emptyList()
        val first = choices.firstOrNull() as? JsonObject ?: return emptyList()
        val delta = first["delta"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<String>()

        ensureMessageStart(out, chunk)

        val reasoning = text(delta["reasoning_content"]) ?: text(delta["reasoning"])
        if (!reasoning.isNullOrEmpty()) {
            stopTextBlock(out)
            if (!thinkingBlockStarted) {
                thinkingBlockStarted = true
                thinkingBlockIndex = nextBlockIndex++
                out += frame("content_block_start", buildJsonObject {
                    put("type", "content_block_start")
                    put("index", thinkingBlockIndex)
                    put("content_block", buildJsonObject { put("type", "thinking"); put("thinking", "") })
                })
            }
            out += frame("content_block_delta", buildJsonObject {
                put("type", "content_block_delta")
                put("index", thinkingBlockIndex)
                put("delta", buildJsonObject { put("type", "thinking_delta"); put("thinking", reasoning) })
            })
        }

        val content = text(delta["content"])
        if (!content.isNullOrEmpty()) {
            stopThinkingBlock(out)
            if (!textBlockStarted) {
                textBlockStarted = true
                textBlockClosed = false
                textBlockIndex = nextBlockIndex++
                out += frame("content_block_start", buildJsonObject {
                    put("type", "content_block_start")
                    put("index", textBlockIndex)
                    put("content_block", buildJsonObject { put("type", "text"); put("text", "") })
                })
            }
            out += frame("content_block_delta", buildJsonObject {
                put("type", "content_block_delta")
                put("index", textBlockIndex)
                put("delta", buildJsonObject { put("type", "text_delta"); put("text", content) })
            })
        }

        (delta["tool_calls"] as? JsonArray)?.forEach { raw ->
            val tc = raw as? JsonObject ?: return@forEach
            val idx = num(tc["index"]).toInt()
            val id = text(tc["id"])
            val name = text((tc["function"] as? JsonObject)?.get("name"))
            if (!id.isNullOrEmpty() && !toolCalls.containsKey(idx)) {
                stopThinkingBlock(out)
                stopTextBlock(out)
                val blockIndex = nextBlockIndex++
                val cleanName = name?.removePrefix("proxy_") ?: ""
                toolCalls[idx] = ToolState(id, cleanName, blockIndex)
                out += frame("content_block_start", buildJsonObject {
                    put("type", "content_block_start")
                    put("index", blockIndex)
                    put("content_block", buildJsonObject {
                        put("type", "tool_use")
                        put("id", id)
                        put("name", cleanName)
                        put("input", buildJsonObject {})
                    })
                })
            }
            val args = text((tc["function"] as? JsonObject)?.get("arguments"))
            if (!args.isNullOrEmpty()) {
                toolArgBuffers.getOrPut(idx) { StringBuilder() }.append(args)
            }
        }

        return out
    }

    override fun finish(finishReason: String?, sawToolCalls: Boolean, usage: JsonObject?): List<String> {
        val out = mutableListOf<String>()
        ensureMessageStart(out, null)
        stopThinkingBlock(out)
        stopTextBlock(out)
        for ((idx, tool) in toolCalls) {
            val buffered = toolArgBuffers[idx]?.toString()
            if (!buffered.isNullOrEmpty()) {
                out += frame("content_block_delta", buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", tool.blockIndex)
                    put("delta", buildJsonObject { put("type", "input_json_delta"); put("partial_json", buffered) })
                })
            }
            out += frame("content_block_stop", buildJsonObject {
                put("type", "content_block_stop"); put("index", tool.blockIndex)
            })
        }
        out += frame("message_delta", buildJsonObject {
            put("type", "message_delta")
            put("delta", buildJsonObject {
                put("stop_reason", claudeStop(finishReason, sawToolCalls))
                put("stop_sequence", JsonNull)
            })
            put("usage", buildJsonObject {
                put("input_tokens", usage?.get("prompt_tokens")?.let { num(it) } ?: 0L)
                put("output_tokens", usage?.get("completion_tokens")?.let { num(it) } ?: 0L)
                val cached = usage?.get("cached_tokens")?.let { num(it) } ?: 0L
                if (cached > 0L) put("cache_read_input_tokens", cached)
            })
        })
        out += frame("message_stop", buildJsonObject { put("type", "message_stop") })
        return out
    }

    private fun ensureMessageStart(out: MutableList<String>, chunk: JsonObject?) {
        if (messageStartSent) return
        messageStartSent = true
        chunk?.get("id")?.let { raw ->
            val id = (raw as? JsonPrimitive)?.content
            if (!id.isNullOrBlank()) {
                val stripped = if (id.startsWith("chatcmpl-")) "msg_" + id.removePrefix("chatcmpl-") else id
                if (stripped.length >= 8) messageId = stripped
            }
        }
        out += frame("message_start", buildJsonObject {
            put("type", "message_start")
            put("message", buildJsonObject {
                put("id", messageId)
                put("type", "message")
                put("role", "assistant")
                put("model", model)
                put("content", buildJsonArray {})
                put("stop_reason", JsonNull)
                put("stop_sequence", JsonNull)
                put("usage", buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) })
            })
        })
    }

    private fun stopThinkingBlock(out: MutableList<String>) {
        if (!thinkingBlockStarted) return
        thinkingBlockStarted = false
        out += frame("content_block_stop", buildJsonObject {
            put("type", "content_block_stop"); put("index", thinkingBlockIndex)
        })
    }

    private fun stopTextBlock(out: MutableList<String>) {
        if (!textBlockStarted || textBlockClosed) return
        textBlockClosed = true
        textBlockStarted = false
        out += frame("content_block_stop", buildJsonObject {
            put("type", "content_block_stop"); put("index", textBlockIndex)
        })
    }

    private fun claudeStop(reason: String?, sawToolCalls: Boolean): String =
        when (reason ?: if (sawToolCalls) "tool_calls" else "stop") {
            "stop" -> "end_turn"
            "length" -> "max_tokens"
            "tool_calls" -> "tool_use"
            "content_filter" -> "refusal"
            else -> "end_turn"
        }
}

/**
 * OpenAI Responses: `response.created` / `response.in_progress`, reasoning and
 * message output items, tool-call items, then `response.completed` (or
 * `response.incomplete` on a length cutoff). Codex CLI closes on the terminal
 * event, so no `[DONE]` is appended.
 */
private class ResponsesSseEncoder(private val model: String) : SseEncoder {
    private var started = false
    private var responseId = "resp_" + System.currentTimeMillis()
    private val created = System.currentTimeMillis() / 1000
    private var seq = 0
    private var reasoningId: String? = null
    private var reasoningIndex = 0
    private var reasoningPartAdded = false
    private var reasoningBuf = StringBuilder()
    private var reasoningDone = false
    private val msgItemAdded = HashMap<Int, Boolean>()
    private val msgContentAdded = HashMap<Int, Boolean>()
    private val msgTextBuf = HashMap<Int, StringBuilder>()
    private val msgItemDone = HashMap<Int, Boolean>()
    private val funcNames = HashMap<Int, String>()
    private val funcCallIds = HashMap<Int, String>()
    private val funcItemAdded = HashMap<Int, Boolean>()
    private val funcArgsBuf = HashMap<Int, StringBuilder>()
    private val funcItemDone = HashMap<Int, Boolean>()
    private val completedItems = TreeMap<Int, JsonObject>()
    private var completedSent = false
    private var inThinking = false

    private companion object {
        const val MESSAGE_INDEX = 1
        const val TOOL_INDEX_BASE = 2
    }

    override fun accept(chunk: JsonObject): List<String> {
        val choices = chunk["choices"] as? JsonArray
        if (choices == null || choices.isEmpty()) return emptyList()
        val choice = choices.firstOrNull() as? JsonObject ?: return emptyList()
        val delta = choice["delta"] as? JsonObject ?: return emptyList()
        val idx = num(choice["index"]).toInt()
        val out = mutableListOf<String>()

        ensureStarted(out, chunk)

        val reasoning = text(delta["reasoning_content"]) ?: text(delta["reasoning"])
        if (!reasoning.isNullOrEmpty()) {
            startReasoning(out, idx)
            emitReasoningDelta(out, reasoning)
        }

        val rawContent = text(delta["content"])
        if (!rawContent.isNullOrEmpty()) {
            var content = rawContent
            if (content.contains("<think>")) {
                inThinking = true
                content = content.replace("<think>", "")
                startReasoning(out, idx)
            }
            if (content.contains("</think>")) {
                val parts = content.split("</think>")
                val thinkPart = parts.first()
                val textPart = parts.drop(1).joinToString("</think>")
                if (thinkPart.isNotEmpty()) emitReasoningDelta(out, thinkPart)
                closeReasoning(out)
                inThinking = false
                content = textPart
            }
            if (inThinking && content.isNotEmpty()) {
                emitReasoningDelta(out, content)
                return out
            }
            if (content.isNotEmpty()) {
                closeReasoning(out)
                emitTextContent(out, MESSAGE_INDEX, content)
            }
        }

        val toolCalls = delta["tool_calls"] as? JsonArray
        if (toolCalls != null && toolCalls.isNotEmpty()) {
            closeReasoning(out)
            closeMessage(out, MESSAGE_INDEX)
            toolCalls.forEach { raw -> (raw as? JsonObject)?.let { emitToolCall(out, it) } }
        }

        return out
    }

    override fun finish(finishReason: String?, sawToolCalls: Boolean, usage: JsonObject?): List<String> {
        val out = mutableListOf<String>()
        ensureStarted(out, null)
        msgItemAdded.keys.sorted().forEach { closeMessage(out, it) }
        closeReasoning(out)
        funcCallIds.keys.sorted().forEach { closeToolCall(out, it) }
        if (!completedSent) {
            completedSent = true
            val incomplete = finishReason == "length"
            emit(out, if (incomplete) "response.incomplete" else "response.completed") {
                put("response", buildJsonObject {
                    put("id", responseId)
                    put("object", "response")
                    put("created_at", created)
                    put("status", if (incomplete) "incomplete" else "completed")
                    put("background", false)
                    put("error", JsonNull)
                    put("output", buildJsonArray { completedItems.values.forEach { add(it) } })
                    if (incomplete) {
                        put("incomplete_details", buildJsonObject { put("reason", "max_output_tokens") })
                    }
                    if (usage != null) {
                        val input = num(usage["prompt_tokens"])
                        val output = num(usage["completion_tokens"])
                        put("usage", buildJsonObject {
                            put("input_tokens", input)
                            put("output_tokens", output)
                            put("total_tokens", input + output)
                        })
                    }
                })
            }
        }
        return out
    }

    private fun ensureStarted(out: MutableList<String>, chunk: JsonObject?) {
        if (started) return
        started = true
        chunk?.get("id")?.let { raw ->
            val id = (raw as? JsonPrimitive)?.content
            if (!id.isNullOrBlank()) responseId = "resp_$id"
        }
        emit(out, "response.created") {
            put("response", buildJsonObject {
                put("id", responseId)
                put("object", "response")
                put("created_at", created)
                put("status", "in_progress")
                put("background", false)
                put("error", JsonNull)
                put("output", buildJsonArray {})
            })
        }
        emit(out, "response.in_progress") {
            put("response", buildJsonObject {
                put("id", responseId)
                put("object", "response")
                put("created_at", created)
                put("status", "in_progress")
            })
        }
    }

    private fun startReasoning(out: MutableList<String>, idx: Int) {
        if (reasoningId != null) return
        reasoningId = "rs_${responseId}_$idx"
        reasoningIndex = idx
        emit(out, "response.output_item.added") {
            put("output_index", idx)
            put("item", buildJsonObject {
                put("id", reasoningId)
                put("type", "reasoning")
                put("summary", buildJsonArray {})
            })
        }
        emit(out, "response.reasoning_summary_part.added") {
            put("item_id", reasoningId)
            put("output_index", idx)
            put("summary_index", 0)
            put("part", buildJsonObject { put("type", "summary_text"); put("text", "") })
        }
        reasoningPartAdded = true
    }

    private fun emitReasoningDelta(out: MutableList<String>, text: String) {
        if (text.isEmpty()) return
        reasoningBuf.append(text)
        emit(out, "response.reasoning_summary_text.delta") {
            put("item_id", reasoningId)
            put("output_index", reasoningIndex)
            put("summary_index", 0)
            put("delta", text)
        }
    }

    private fun closeReasoning(out: MutableList<String>) {
        val id = reasoningId ?: return
        if (reasoningDone) return
        reasoningDone = true
        val full = reasoningBuf.toString()
        if (reasoningPartAdded) {
            emit(out, "response.reasoning_summary_text.done") {
                put("item_id", id)
                put("output_index", reasoningIndex)
                put("summary_index", 0)
                put("text", full)
            }
            emit(out, "response.reasoning_summary_part.done") {
                put("item_id", id)
                put("output_index", reasoningIndex)
                put("summary_index", 0)
                put("part", buildJsonObject { put("type", "summary_text"); put("text", full) })
            }
        }
        val item = buildJsonObject {
            put("id", id)
            put("type", "reasoning")
            put("summary", buildJsonArray { add(buildJsonObject { put("type", "summary_text"); put("text", full) }) })
        }
        emit(out, "response.output_item.done") {
            put("output_index", reasoningIndex)
            put("item", item)
        }
        completedItems[reasoningIndex] = item
    }

    private fun emitTextContent(out: MutableList<String>, idx: Int, content: String) {
        if (msgItemAdded[idx] != true) {
            msgItemAdded[idx] = true
            emit(out, "response.output_item.added") {
                put("output_index", idx)
                put("item", buildJsonObject {
                    put("id", "msg_${responseId}_$idx")
                    put("type", "message")
                    put("content", buildJsonArray {})
                    put("role", "assistant")
                })
            }
        }
        if (msgContentAdded[idx] != true) {
            msgContentAdded[idx] = true
            emit(out, "response.content_part.added") {
                put("item_id", "msg_${responseId}_$idx")
                put("output_index", idx)
                put("content_index", 0)
                put("part", buildJsonObject {
                    put("type", "output_text")
                    put("annotations", buildJsonArray {})
                    put("logprobs", buildJsonArray {})
                    put("text", "")
                })
            }
        }
        emit(out, "response.output_text.delta") {
            put("item_id", "msg_${responseId}_$idx")
            put("output_index", idx)
            put("content_index", 0)
            put("delta", content)
            put("logprobs", buildJsonArray {})
        }
        msgTextBuf.getOrPut(idx) { StringBuilder() }.append(content)
    }

    private fun closeMessage(out: MutableList<String>, idx: Int) {
        if (msgItemAdded[idx] != true || msgItemDone[idx] == true) return
        msgItemDone[idx] = true
        val fullText = msgTextBuf[idx]?.toString() ?: ""
        val msgId = "msg_${responseId}_$idx"
        emit(out, "response.output_text.done") {
            put("item_id", msgId)
            put("output_index", idx)
            put("content_index", 0)
            put("text", fullText)
            put("logprobs", buildJsonArray {})
        }
        emit(out, "response.content_part.done") {
            put("item_id", msgId)
            put("output_index", idx)
            put("content_index", 0)
            put("part", buildJsonObject {
                put("type", "output_text")
                put("annotations", buildJsonArray {})
                put("logprobs", buildJsonArray {})
                put("text", fullText)
            })
        }
        val item = buildJsonObject {
            put("id", msgId)
            put("type", "message")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("annotations", buildJsonArray {})
                    put("logprobs", buildJsonArray {})
                    put("text", fullText)
                })
            })
            put("role", "assistant")
        }
        emit(out, "response.output_item.done") {
            put("output_index", idx)
            put("item", item)
        }
        completedItems[idx] = item
    }

    private fun emitToolCall(out: MutableList<String>, tc: JsonObject) {
        val tcIdx = num(tc["index"]).toInt()
        val outputIndex = TOOL_INDEX_BASE + tcIdx
        text(tc["id"])?.let { funcCallIds[tcIdx] = it }
        text((tc["function"] as? JsonObject)?.get("name"))?.let { funcNames[tcIdx] = it }
        val callId = funcCallIds[tcIdx]
        val name = funcNames[tcIdx]
        if (funcItemAdded[tcIdx] != true && callId != null && name != null) {
            funcItemAdded[tcIdx] = true
            emit(out, "response.output_item.added") {
                put("output_index", outputIndex)
                put("item", buildJsonObject {
                    put("id", "fc_$callId")
                    put("type", "function_call")
                    put("arguments", "")
                    put("call_id", callId)
                    put("name", name)
                })
            }
        }
        val args = text((tc["function"] as? JsonObject)?.get("arguments"))
        if (!args.isNullOrEmpty()) {
            if (funcItemAdded[tcIdx] == true && callId != null) {
                emit(out, "response.function_call_arguments.delta") {
                    put("item_id", "fc_$callId")
                    put("output_index", outputIndex)
                    put("delta", args)
                }
            }
            funcArgsBuf.getOrPut(tcIdx) { StringBuilder() }.append(args)
        }
    }

    private fun closeToolCall(out: MutableList<String>, idx: Int) {
        val callId = funcCallIds[idx] ?: return
        if (funcItemDone[idx] == true) return
        funcItemDone[idx] = true
        val outputIndex = TOOL_INDEX_BASE + idx
        val args = funcArgsBuf[idx]?.toString()?.takeIf { it.isNotEmpty() } ?: "{}"
        emit(out, "response.function_call_arguments.done") {
            put("item_id", "fc_$callId")
            put("output_index", outputIndex)
            put("arguments", args)
        }
        val item = buildJsonObject {
            put("id", "fc_$callId")
            put("type", "function_call")
            put("arguments", args)
            put("call_id", callId)
            put("name", funcNames[idx] ?: "")
        }
        emit(out, "response.output_item.done") {
            put("output_index", outputIndex)
            put("item", item)
        }
        completedItems[outputIndex] = item
    }

    private fun emit(out: MutableList<String>, type: String, body: JsonObjectBuilder.() -> Unit) {
        seq += 1
        val data = buildJsonObject {
            put("type", type)
            body()
            put("sequence_number", seq)
        }
        out += "event: $type\ndata: ${encode(data)}"
    }
}

private fun frame(type: String, data: JsonObject): String = "event: $type\ndata: ${encode(data)}"
