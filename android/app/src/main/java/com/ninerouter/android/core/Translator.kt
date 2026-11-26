package com.ninerouter.android.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * First Android port of the upstream translator registry. OpenAI is the pivot
 * format; a handful of direct routes are handled explicitly where the bridge
 * would lose thinking / tool ids. Unsupported native envelopes stay in their
 * executor rather than being force-round-tripped through the bridge.
 */
object Translator {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // ---------- request ----------

    fun requestBody(
        source: String,
        target: String,
        body: JsonObject,
        upstreamModel: String,
        wantsStream: Boolean,
    ): JsonObject {
        val normalized = if (source == Formats.CLAUDE) claudeToOpenAi(body)
        else if (source == Formats.GEMINI) geminiToOpenAi(body)
        else if (source == Formats.OPENAI_RESPONSES) responsesToOpenAi(body)
        else body

        return when (target) {
            Formats.OPENAI, Formats.OPENAI_RESPONSES -> {
                if (target == Formats.OPENAI_RESPONSES) {
                    openAiToResponses(normalized, upstreamModel, wantsStream)
                } else {
                    buildJsonObject {
                        put("model", upstreamModel)
                        put("stream", wantsStream)
                        normalized.forEach { (k, v) -> if (k != "model" && k != "stream") put(k, v) }
                    }
                }
            }
            Formats.CLAUDE -> openAiToClaude(normalized, upstreamModel, wantsStream)
            Formats.GEMINI -> openAiToGemini(normalized, upstreamModel, wantsStream)
            else -> buildJsonObject {
                put("model", upstreamModel)
                normalized.forEach { (k, v) -> if (k != "model") put(k, v) }
            }
        }
    }

    private fun claudeToOpenAi(body: JsonObject): JsonObject = buildJsonObject {
        put("model", text(body["model"], "claude"))
        val messages = mutableListOf<JsonObject>()
        val system = StringBuilder()
        val systemBlocks = body["system"] as? JsonArray
        if (systemBlocks != null) {
            systemBlocks.forEach { block ->
                val t = text((block as? JsonObject)?.get("text"), "")
                if (t.isNotBlank()) system.appendLine(t)
            }
        } else {
            system.append(text(body["system"], ""))
        }
        if (system.isNotBlank()) {
            messages += buildJsonObject { put("role", "system"); put("content", system.toString().trim()) }
        }
        (body["messages"] as? JsonArray)?.forEach { raw ->
            val msg = raw as? JsonObject ?: return@forEach
            val role = when (text(msg["role"], "")) {
                "assistant" -> "assistant"
                "tool" -> "tool"
                else -> "user"
            }
            val parts = StringBuilder()
            val blocks = msg["content"] as? JsonArray
            if (blocks != null) {
                blocks.forEach { b ->
                    val obj = b as? JsonObject ?: return@forEach
                    when (text(obj["type"], "")) {
                        "tool_result" -> {
                            val id = text(obj["tool_use_id"], "")
                            parts.appendLine("[tool result $id] ${text(obj["content"], "")}")
                        }
                        "tool_use" -> {
                            val args = obj["input"]?.toString() ?: "{}"
                            parts.appendLine("[tool_use ${text(obj["name"], "")}] $args")
                        }
                        else -> parts.appendLine(text(obj["text"], ""))
                    }
                }
            } else parts.append(text(msg["content"], ""))
            messages += buildJsonObject { put("role", role); put("content", parts.toString().trim()) }
        }
        put("messages", JsonArray(messages))
        copyNumber(body, "max_tokens", this)
        copyNumber(body, "temperature", this)
        body["tools"]?.let { put("tools", it) }
        body["stream"]?.let { put("stream", it) }
    }

    private fun responsesToOpenAi(body: JsonObject): JsonObject {
        if (body["messages"] != null) return body
        val messages = mutableListOf<JsonObject>()
        val instructions = text(body["instructions"], "")
        if (instructions.isNotBlank()) {
            messages += buildJsonObject { put("role", "system"); put("content", instructions) }
        }
        when (val input = body["input"]) {
            is JsonPrimitive -> messages += buildJsonObject { put("role", "user"); put("content", input.content) }
            is JsonArray -> input.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val type = text(obj["type"], "")
                val role = if (text(obj["role"], "") == "assistant") "assistant" else "user"
                val content = when {
                    type == "message" -> flattenText(obj["content"])
                    type == "function_call" -> "[function_call ${text(obj["name"], "")}] ${text(obj["arguments"], "")}"
                    type == "function_call_output" -> "[tool result] ${flattenText(obj["output"])}"
                    else -> flattenText(obj["content"])
                }
                if (content.isNotBlank()) messages += buildJsonObject { put("role", role); put("content", content) }
            }
            else -> Unit
        }
        return buildJsonObject {
            put("model", text(body["model"], "openai"))
            put("messages", JsonArray(messages))
            copyNumber(body, "temperature", this)
            copyNumber(body, "top_p", this)
            body["tools"]?.let { put("tools", it) }
            body["stream"]?.let { put("stream", it) }
        }
    }

    private fun geminiToOpenAi(body: JsonObject): JsonObject = buildJsonObject {
        put("model", text(body["model"], "gemini"))
        val messages = mutableListOf<JsonObject>()
        (body["contents"] as? JsonArray)?.forEach { raw ->
            val c = raw as? JsonObject ?: return@forEach
            val role = if (text(c["role"], "user") == "model") "assistant" else "user"
            val parts = StringBuilder()
            (c["parts"] as? JsonArray)?.forEach { p ->
                text((p as? JsonObject)?.get("text"), "").let { if (it.isNotBlank()) parts.appendLine(it) }
            }
            messages += buildJsonObject { put("role", role); put("content", parts.toString().trim()) }
        }
        put("messages", JsonArray(messages))
        (body["generationConfig"] as? JsonObject)?.let { gc ->
            copyNumber(gc, "temperature", this)
            copyNumber(gc, "maxOutputTokens", this, "max_tokens")
        }
        body["stream"]?.let { put("stream", it) }
    }

    private fun openAiToClaude(body: JsonObject, model: String, stream: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", number(body, "max_tokens", number(body, "max_completion_tokens", 4096L)))
        val system = StringBuilder()
        val messages = mutableListOf<JsonObject>()
        (body["messages"] as? JsonArray)?.forEach { raw ->
            val m = raw as? JsonObject ?: return@forEach
            when (text(m["role"], "")) {
                "system" -> system.appendLine(flattenText(m["content"]))
                "tool" -> messages += buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", text(m["tool_call_id"], ""))
                            put("content", flattenText(m["content"]))
                        })
                    })
                }
                "assistant" -> {
                    val blocks = mutableListOf<JsonObject>()
                    val content = flattenText(m["content"])
                    if (content.isNotBlank()) blocks += buildJsonObject {
                        put("type", "text"); put("text", content)
                    }
                    (m["tool_calls"] as? JsonArray)?.forEach { tc ->
                        val call = tc as? JsonObject ?: return@forEach
                        val fn = call["function"] as? JsonObject
                        val args = text(fn?.get("arguments"), "{}")
                        val parsed = try { json.parseToJsonElement(args) } catch (_: Exception) { JsonObject(emptyMap()) }
                        blocks += buildJsonObject {
                            put("type", "tool_use")
                            put("id", text(call["id"], ""))
                            put("name", text(fn?.get("name"), ""))
                            put("input", parsed)
                        }
                    }
                    messages += buildJsonObject { put("role", "assistant"); put("content", JsonArray(blocks)) }
                }
                else -> messages += buildJsonObject {
                    put("role", "user"); put("content", flattenText(m["content"]))
                }
            }
        }
        if (system.isNotBlank()) put("system", system.toString().trim())
        put("messages", JsonArray(messages))
        (body["tools"] as? JsonArray)?.let { tools ->
            put("tools", JsonArray(tools.map { t ->
                val fn = (t as? JsonObject)?.get("function") as? JsonObject
                buildJsonObject {
                    put("name", text(fn?.get("name"), ""))
                    put("description", text(fn?.get("description"), ""))
                    put("input_schema", fn?.get("parameters") ?: JsonObject(emptyMap()))
                }
            }))
        }
        copyNumber(body, "temperature", this)
        copyNumber(body, "top_p", this)
        put("stream", stream)
    }

    private fun openAiToGemini(body: JsonObject, model: String, stream: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        val contents = mutableListOf<JsonObject>()
        (body["messages"] as? JsonArray)?.forEach { raw ->
            val m = raw as? JsonObject ?: return@forEach
            val role = when (text(m["role"], "")) {
                "assistant" -> "model"
                "system" -> {
                    contents += buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", flattenText(m["content"])) }) })
                    }
                    return@forEach
                }
                else -> "user"
            }
            contents += buildJsonObject {
                put("role", role)
                put("parts", buildJsonArray {
                    val t = flattenText(m["content"])
                    if (t.isNotBlank()) add(buildJsonObject { put("text", t) })
                })
            }
        }
        put("contents", JsonArray(contents))
        put("generationConfig", buildJsonObject {
            copyNumber(body, "temperature", this)
            copyNumber(body, "top_p", this, "topP")
            put("maxOutputTokens", number(body, "max_tokens", number(body, "max_completion_tokens", 4096L)))
        })
        (body["tools"] as? JsonArray)?.let { tools ->
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", JsonArray(tools.map { t ->
                        val fn = (t as? JsonObject)?.get("function") as? JsonObject
                        buildJsonObject {
                            put("name", text(fn?.get("name"), ""))
                            put("description", text(fn?.get("description"), ""))
                            put("parameters", fn?.get("parameters") ?: JsonObject(emptyMap()))
                        }
                    }))
                })
            })
        }
        put("_stream", stream) // stripped by Upstream and appended to the URL
    }

    /** Chat Completions → Responses API request (Codex, Grok CLI shared proxy). */
    private fun openAiToResponses(body: JsonObject, model: String, stream: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", stream)
        put("store", false)
        val instructions = StringBuilder()
        val input = mutableListOf<JsonObject>()
        (body["messages"] as? JsonArray)?.forEach { raw ->
            val m = raw as? JsonObject ?: return@forEach
            val role = text(m["role"], "user")
            if (role == "system" || role == "developer") {
                val t = flattenText(m["content"])
                if (t.isNotBlank()) instructions.appendLine(t)
                return@forEach
            }
            input += buildJsonObject {
                put("type", "message")
                put("role", role)
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", if (role == "assistant") "output_text" else "input_text")
                        put("text", flattenText(m["content"]))
                    })
                })
            }
        }
        if (instructions.isNotBlank()) put("instructions", instructions.toString().trim())
        put("input", JsonArray(input))
        (body["max_completion_tokens"] ?: body["max_tokens"])?.let { put("max_output_tokens", it) }
        body["temperature"]?.let { put("temperature", it) }
        body["top_p"]?.let { put("top_p", it) }
        (body["tools"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { tools ->
            put("tools", JsonArray(tools.mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                val fn = o["function"] as? JsonObject ?: return@mapNotNull null
                buildJsonObject {
                    put("type", "function")
                    put("name", text(fn["name"], ""))
                    put("description", text(fn["description"], ""))
                    put("parameters", fn["parameters"] ?: JsonObject(emptyMap()))
                }
            }))
        }
    }

    /** Responses API JSON → OpenAI envelope (used when the upstream target is Responses). */
    private fun responsesApiToOpenAi(upstream: JsonObject, model: String): JsonObject {
        val content = StringBuilder()
        (upstream["output"] as? JsonArray)?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            if (text(o["type"], "") != "message") return@forEach
            (o["content"] as? JsonArray)?.forEach { c ->
                val co = c as? JsonObject ?: return@forEach
                if (text(co["type"], "") == "output_text") content.append(text(co["text"], ""))
            }
        }
        val usage = upstream["usage"] as? JsonObject
        return openAiEnvelope(
            model,
            content.toString(),
            "stop",
            number(usage?.get("input_tokens"), 0L),
            number(usage?.get("output_tokens"), 0L),
        )
    }

    /** gemini-cli / antigravity wrap the Gemini payload in `{ response: {...} }`. */
    fun unwrapEnvelope(upstream: JsonObject): JsonObject =
        (upstream["response"] as? JsonObject) ?: upstream

    // ---------- response ----------

    fun responseBody(target: String, source: String, upstream: JsonObject, requestedModel: String): JsonObject {
        val openAi = when (target) {
            Formats.CLAUDE -> claudeToOpenAiResponse(upstream, requestedModel)
            Formats.GEMINI -> geminiToOpenAiResponse(upstream, requestedModel)
            Formats.OPENAI_RESPONSES -> responsesApiToOpenAi(upstream, requestedModel)
            else -> upstream
        }
        return when (source) {
            Formats.CLAUDE -> openAiToClaudeResponse(openAi, requestedModel)
            Formats.GEMINI -> openAiToGeminiResponse(openAi, requestedModel)
            Formats.OPENAI_RESPONSES -> openAiToResponsesResponse(openAi, requestedModel)
            else -> openAi
        }
    }

    private fun openAiToResponsesResponse(openAi: JsonObject, model: String): JsonObject {
        val choice = (openAi["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = text((choice?.get("message") as? JsonObject)?.get("content"), "")
        val usage = openAi["usage"] as? JsonObject
        return buildJsonObject {
            put("id", "resp_" + System.currentTimeMillis())
            put("object", "response")
            put("created_at", System.currentTimeMillis() / 1000)
            put("status", "completed")
            put("model", model)
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "output_text"); put("text", content) })
                    })
                })
            })
            put("usage", buildJsonObject {
                put("input_tokens", number(usage?.get("prompt_tokens"), 0L))
                put("output_tokens", number(usage?.get("completion_tokens"), 0L))
                put("total_tokens", number(usage?.get("total_tokens"), 0L))
            })
        }
    }

    private fun claudeToOpenAiResponse(upstream: JsonObject, model: String): JsonObject {
        val content = StringBuilder()
        (upstream["content"] as? JsonArray)?.forEach { b ->
            val obj = b as? JsonObject ?: return@forEach
            if (text(obj["type"], "text") == "text") content.append(text(obj["text"], ""))
        }
        val usage = upstream["usage"] as? JsonObject
        return openAiEnvelope(model, content.toString(), "stop",
            number(usage?.get("input_tokens"), 0L), number(usage?.get("output_tokens"), 0L))
    }

    private fun geminiToOpenAiResponse(upstream: JsonObject, model: String): JsonObject {
        val candidate = (upstream["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = StringBuilder()
        ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray)?.forEach { p ->
            text((p as? JsonObject)?.get("text"), "").let { content.append(it) }
        }
        val meta = upstream["usageMetadata"] as? JsonObject
        return openAiEnvelope(model, content.toString(), "stop",
            number(meta?.get("promptTokenCount"), 0L), number(meta?.get("candidatesTokenCount"), 0L))
    }

    private fun openAiEnvelope(model: String, content: String, finish: String, prompt: Long, completion: Long): JsonObject =
        buildJsonObject {
            put("id", "chatcmpl-" + System.currentTimeMillis())
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
                    put("finish_reason", finish)
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", prompt)
                put("completion_tokens", completion)
                put("total_tokens", prompt + completion)
            })
        }

    private fun openAiToClaudeResponse(openAi: JsonObject, model: String): JsonObject {
        val choice = (openAi["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = text((choice?.get("message") as? JsonObject)?.get("content"), "")
        val usage = openAi["usage"] as? JsonObject
        return buildJsonObject {
            put("id", text(openAi["id"], "msg_" + System.currentTimeMillis()))
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", content) }) })
            put("stop_reason", text(choice?.get("finish_reason"), "end_turn"))
            put("usage", buildJsonObject {
                put("input_tokens", number(usage?.get("prompt_tokens"), 0L))
                put("output_tokens", number(usage?.get("completion_tokens"), 0L))
            })
        }
    }

    private fun openAiToGeminiResponse(openAi: JsonObject, model: String): JsonObject {
        val choice = (openAi["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = text((choice?.get("message") as? JsonObject)?.get("content"), "")
        return buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", content) }) })
                    })
                    put("finishReason", "STOP")
                })
            })
            put("modelVersion", model)
        }
    }

    // ---------- streaming ----------

    fun sseDelta(target: String, rawLine: String, requestedModel: String, cliEnvelope: Boolean = false): String? {
        if (!rawLine.startsWith("data:")) return null
        val data = rawLine.removePrefix("data:").trim()
        if (data == "[DONE]") return "data: [DONE]"
        var obj = try { json.parseToJsonElement(data) as? JsonObject } catch (_: Exception) { null } ?: return null
        if (cliEnvelope) obj = unwrapEnvelope(obj)

        val delta = buildJsonObject {
            val piece = when (target) {
                Formats.GEMINI -> {
                    val cand = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val parts = (cand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                    parts?.map { text((it as? JsonObject)?.get("text"), "") }?.joinToString("") ?: ""
                }
                Formats.OPENAI_RESPONSES -> when (text(obj["type"], "")) {
                    "response.output_text.delta" -> text(obj["delta"], "")
                    "response.refusal.delta" -> text(obj["delta"], "")
                    else -> ""
                }
                Formats.CLAUDE -> when (text(obj["type"], "")) {
                    "content_block_delta" -> text((obj["delta"] as? JsonObject)?.get("text"), "")
                    "content_block_start" -> text(((obj["content_block"] as? JsonObject)?.get("text")), "")
                    else -> ""
                }
                else -> {
                    val choices = obj["choices"] as? JsonArray
                    val first = choices?.firstOrNull() as? JsonObject
                    val sourceDelta = first?.get("delta") as? JsonObject
                    val msg = first?.get("message") as? JsonObject
                    sourceDelta?.get("reasoning_content")?.let { put("reasoning_content", it) }
                    sourceDelta?.get("reasoning")?.let { put("reasoning", it) }
                    sourceDelta?.get("tool_calls")?.let { put("tool_calls", it) }
                    sourceDelta?.get("function_call")?.let { put("function_call", it) }
                    sourceDelta?.get("role")?.let { put("role", it) }
                    text(sourceDelta?.get("content"), text(msg?.get("content"), ""))
                }
            }
            if (piece.isNotEmpty()) put("content", piece)
        }
        if (delta.isEmpty()) return null
        return json.encodeToString(buildJsonObject {
            put("id", "chatcmpl-stream")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", requestedModel)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", JsonNull)
                })
            })
        }).let { "data: $it" }
    }

    /** Returns the upstream terminal reason normalized to OpenAI chat semantics. */
    fun sseFinishReason(target: String, rawLine: String, cliEnvelope: Boolean = false): String? {
        if (!rawLine.startsWith("data:")) return null
        val data = rawLine.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return null
        var obj = try { json.parseToJsonElement(data) as? JsonObject } catch (_: Exception) { null } ?: return null
        if (cliEnvelope) obj = unwrapEnvelope(obj)
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val finishValue = choice?.get("finish_reason")
        val direct = if (finishValue == null || finishValue == JsonNull) "" else text(finishValue, "")
        if (direct.isNotBlank()) return normalizeFinishReason(direct)

        return when (target) {
            Formats.GEMINI -> {
                val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                when (text(candidate?.get("finishReason"), "").uppercase()) {
                    "MAX_TOKENS" -> "length"
                    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter"
                    "STOP" -> "stop"
                    else -> null
                }
            }
            Formats.CLAUDE -> {
                val reason = text((obj["delta"] as? JsonObject)?.get("stop_reason"), "")
                when (reason) {
                    "end_turn", "stop_sequence" -> "stop"
                    "max_tokens" -> "length"
                    "tool_use" -> "tool_calls"
                    "refusal" -> "content_filter"
                    else -> null
                }
            }
            Formats.OPENAI_RESPONSES -> when (text(obj["type"], "")) {
                "response.completed" -> "stop"
                "response.incomplete" -> {
                    val response = obj["response"] as? JsonObject
                    val reason = text((response?.get("incomplete_details") as? JsonObject)?.get("reason"), "")
                    if (reason == "max_output_tokens") "length" else "stop"
                }
                else -> null
            }
            else -> null
        }
    }

    fun terminalSseChunk(model: String, finishReason: String): String =
        json.encodeToString(buildJsonObject {
            put("id", "chatcmpl-stream")
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {})
                    put("finish_reason", normalizeFinishReason(finishReason))
                })
            })
        }).let { "data: $it" }

    private fun normalizeFinishReason(reason: String): String = when (reason) {
        "max_tokens", "max_output_tokens", "MAX_TOKENS" -> "length"
        "tool_use", "tool-calls" -> "tool_calls"
        "refusal" -> "content_filter"
        else -> reason.ifBlank { "stop" }
    }

    // ---------- helpers ----------

    private fun text(el: JsonElement?, fallback: String): String =
        (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallback

    private fun number(el: JsonElement?, fallback: Long): Long =
        (el as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

    private fun number(body: JsonObject, key: String, fallback: Long, alias: String = key): Long =
        number(body[key] ?: body[alias], fallback)

    private fun copyNumber(body: JsonObject, key: String, into: kotlinx.serialization.json.JsonObjectBuilder, alias: String = key) {
        body[key]?.let { into.put(alias, it) }
    }

    private fun flattenText(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> el.content
        is JsonArray -> el.joinToString("\n") { flattenText(it) }
        is JsonObject -> text(el["text"], "")
        else -> ""
    }
}
