package com.ninerouter.android.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ported from open-sse/rtk/{caveman,ponytail,customPrompt}.js.
 *
 * These are system-prompt injectors, not body rewrites of tool output. They
 * mutate only the system channel of the translated body and are fail-open:
 * an unsupported format leaves the body untouched rather than corrupting it.
 */
object PromptInjectors {
    private const val CAVEMAN_LITE =
        "Terse responses. Keep exact code, paths, commands, errors and URLs. No filler, no status phrases, no narration."
    private const val CAVEMAN_FULL =
        "$CAVEMAN_LITE No decorative emoji. No self-reference. Preserve the user's language. Skip caveman style for security warnings, irreversible actions and ordered multi-step sequences, then resume."
    private const val CAVEMAN_ULTRA =
        "$CAVEMAN_FULL Extremely compressed: fragments and short sentences only where meaning stays unambiguous."

    private const val PONYTAIL_LITE =
        "Build what is asked and name the lazier alternative in one line."
    private const val PONYTAIL_FULL =
        "You are a lazy senior developer. Best code is code never written. Before writing code: YAGNI, stdlib, native platform, installed dependency, one line, then minimum working code. No unrequested abstraction or scaffolding. Deletion over addition. Never simplify away validation, security, accessibility or requested behavior."
    private const val PONYTAIL_ULTRA =
        "$PONYTAIL_FULL Choose the shortest correct diff. Add at most one tiny runnable check for non-trivial logic."

    fun cavemanPrompt(level: String): String = when (level.lowercase()) {
        "lite", "wenyan-lite" -> CAVEMAN_LITE
        "ultra", "wenyan-ultra" -> CAVEMAN_ULTRA
        else -> CAVEMAN_FULL
    }

    fun ponytailPrompt(level: String): String = when (level.lowercase()) {
        "lite" -> PONYTAIL_LITE
        "ultra" -> PONYTAIL_ULTRA
        else -> PONYTAIL_FULL
    }

    fun inject(body: JsonObject, format: String, text: String): JsonObject {
        if (text.isBlank()) return body
        return when (format) {
            Formats.OPENAI, Formats.OPENAI_RESPONSES -> injectOpenAi(body, text)
            Formats.CLAUDE -> injectClaude(body, text)
            Formats.GEMINI -> injectGemini(body, text)
            else -> body // binary/protobuf envelopes: never guess their system shape
        }
    }

    private fun injectOpenAi(body: JsonObject, text: String): JsonObject {
        val messages = (body["messages"] as? JsonArray)?.toList().orEmpty().toMutableList()
        val first = messages.firstOrNull() as? JsonObject
        if (first != null && (first["role"] as? JsonPrimitive)?.content == "system") {
            val merged = (first["content"] as? JsonPrimitive)?.content.orEmpty() + "\n\n" + text
            messages[0] = JsonObject(first.toMap() + ("content" to JsonPrimitive(merged)))
        } else {
            messages.add(0, buildJsonObject {
                put("role", "system")
                put("content", text)
            })
        }
        return JsonObject(body.toMap() + ("messages" to JsonArray(messages)))
    }

    private fun injectClaude(body: JsonObject, text: String): JsonObject {
        val existing = when (val sys = body["system"]) {
            is JsonPrimitive -> sys.content
            is JsonArray -> sys.joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }
            else -> ""
        }
        val merged = if (existing.isBlank()) text else "$existing\n\n$text"
        return JsonObject(body.toMap() + ("system" to JsonPrimitive(merged)))
    }

    private fun injectGemini(body: JsonObject, text: String): JsonObject {
        val instruction = buildJsonObject {
            put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        }
        return JsonObject(body.toMap() + ("systemInstruction" to instruction))
    }
}
