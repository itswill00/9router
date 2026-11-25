package com.ninerouter.android.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Minimal, fail-open port of open-sse/rtk: compress bulky tool_result text in place. */
object Rtk {
    private const val MAX_CHARS = 4000

    /** Fail-open: returns the original body untouched when nothing matched or anything threw. */
    fun apply(body: JsonObject): Pair<JsonObject, Int> {
        val messages = body["messages"] as? JsonArray ?: return body to 0
        var hits = 0
        val rewritten = JsonArray(messages.map { msg ->
            if (msg !is JsonObject) return@map msg
            val role = (msg["role"] as? JsonPrimitive)?.content
            val content = msg["content"] ?: return@map msg
            when {
                role == "tool" && content is JsonPrimitive -> {
                    val out = squash(content.content)
                    if (out != content.content) hits++
                    JsonObject(msg.toMap() + ("content" to JsonPrimitive(out)))
                }
                content is JsonArray -> {
                    val (out, n) = compressBlocks(content)
                    hits += n
                    if (n > 0) JsonObject(msg.toMap() + ("content" to out)) else msg
                }
                else -> msg
            }
        })
        if (hits == 0) return body to 0
        return JsonObject(body.toMap() + ("messages" to rewritten)) to hits
    }

    private fun compressBlocks(blocks: JsonArray): Pair<JsonArray, Int> {
        var n = 0
        val out = JsonArray(blocks.map { block ->
            if (block !is JsonObject) return@map block
            val type = (block["type"] as? JsonPrimitive)?.content
            val key = if (type == "tool_result") "content" else "text"
            val value = block[key] as? JsonPrimitive ?: return@map block
            val squeezed = squash(value.content)
            if (squeezed == value.content) return@map block
            n++
            JsonObject(block.toMap() + (key to JsonPrimitive(squeezed)))
        })
        return out to n
    }

    private fun squash(text: String): String {
        var s = Regex("\\n{3,}").replace(text, "\n\n")
        if (s.length > MAX_CHARS) {
            s = s.take(MAX_CHARS) + "\n…[RTK truncated " + (s.length - MAX_CHARS) + " chars]"
        }
        return s
    }
}
