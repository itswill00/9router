package com.ninerouter.android.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Approximate Anthropic `messages/count_tokens` estimator. Mirrors the
 * original character-count rules (dividing by four) rather than claiming
 * tokenizer exactness, so callers get the same ballpark the gateway reports
 * upstream.
 */
object TokenEstimator {
    fun estimateAnthropicInputTokens(body: JsonObject): Int {
        val messages = body["messages"] as? JsonArray
        var totalChars = countValueChars(body["system"]) + countValueChars(body["tools"])
        if (messages != null) {
            for (message in messages) totalChars += countMessageChars(message)
        }
        return (totalChars + 3) / 4
    }

    private fun countValueChars(value: JsonElement?): Int = when (value) {
        null, is JsonNull -> 0
        is JsonPrimitive -> value.content.length
        is JsonArray -> value.sumOf { countValueChars(it) }
        is JsonObject -> value.entries.sumOf { (key, item) -> key.length + countValueChars(item) }
    }

    private fun countContentBlockChars(block: JsonElement?): Int = when (block) {
        null, is JsonNull -> 0
        is JsonPrimitive -> block.content.length
        is JsonArray -> countValueChars(block)
        is JsonObject -> when ((block["type"] as? JsonPrimitive)?.content) {
            "text" -> countValueChars(block["text"])
            "tool_use" -> countValueChars(block["name"]) + countValueChars(block["input"])
            "tool_result" -> countValueChars(block["content"])
            "thinking" -> countValueChars(block["thinking"])
            else -> countValueChars(block)
        }
    }

    private fun countMessageChars(message: JsonElement?): Int {
        val obj = message as? JsonObject ?: return 0
        return when (val content = obj["content"]) {
            null, is JsonNull -> 0
            is JsonPrimitive -> content.content.length
            is JsonArray -> content.sumOf { countContentBlockChars(it) }
            else -> countValueChars(content)
        }
    }
}
