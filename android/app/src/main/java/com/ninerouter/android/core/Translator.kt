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
        // Same dialect: keep the native payload instead of round-tripping it
        // through the OpenAI pivot. This preserves Claude tool_use/tool_result,
        // images, thinking blocks, Responses input items and tool schemas.
        if (source == target) {
            return buildJsonObject {
                put("model", upstreamModel)
                if (target == Formats.GEMINI) {
                    body.forEach { (k, v) -> if (k != "model" && k != "_stream") put(k, v) }
                    put("_stream", wantsStream)
                } else {
                    put("stream", wantsStream)
                    body.forEach { (k, v) ->
                        if (k != "model" && k != "stream" && k != "_stream") put(k, v)
                    }
                }
            }
        }
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
            val role = text(msg["role"], "user")
            val blocks = msg["content"] as? JsonArray
            if (blocks == null) {
                messages += buildJsonObject { put("role", role); put("content", text(msg["content"], "")) }
                return@forEach
            }
            if (role == "assistant") {
                val content = StringBuilder()
                val toolCalls = mutableListOf<JsonObject>()
                blocks.forEach { b ->
                    val obj = b as? JsonObject ?: return@forEach
                    when (text(obj["type"], "")) {
                        "text" -> content.append(text(obj["text"], ""))
                        "tool_use" -> toolCalls += buildJsonObject {
                            put("id", text(obj["id"], ""))
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", text(obj["name"], ""))
                                put("arguments", (obj["input"] ?: JsonObject(emptyMap())).toString())
                            })
                        }
                    }
                }
                messages += buildJsonObject {
                    put("role", "assistant")
                    if (content.isNotBlank()) put("content", content.toString())
                    if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                }
            } else {
                val parts = mutableListOf<JsonElement>()
                blocks.forEach { b ->
                    val obj = b as? JsonObject ?: return@forEach
                    when (text(obj["type"], "")) {
                        "tool_result" -> messages += buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", text(obj["tool_use_id"], ""))
                            put("content", flattenText(obj["content"]))
                        }
                        "image" -> claudeImageToOpenAi(obj)?.let { parts += it }
                        "text" -> text(obj["text"], "").takeIf { it.isNotBlank() }?.let {
                            parts += buildJsonObject { put("type", "text"); put("text", it) }
                        }
                    }
                }
                if (parts.isNotEmpty()) {
                    val onlyText = parts.all {
                        text((it as? JsonObject)?.get("type"), "") == "text"
                    }
                    messages += buildJsonObject {
                        put("role", "user")
                        if (onlyText) {
                            put("content", parts.joinToString("\n") {
                                text((it as? JsonObject)?.get("text"), "")
                            })
                        } else {
                            put("content", JsonArray(parts))
                        }
                    }
                }
            }
        }
        put("messages", JsonArray(messages))
        copyNumber(body, "max_tokens", this)
        copyNumber(body, "temperature", this)
        body["tools"]?.let { tools ->
            put("tools", JsonArray((tools as? JsonArray).orEmpty().map { tool ->
                val t = tool as? JsonObject
                if (text(t?.get("type"), "") == "function") tool else buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", text(t?.get("name"), ""))
                        put("description", text(t?.get("description"), ""))
                        put("parameters", t?.get("input_schema") ?: JsonObject(emptyMap()))
                    })
                }
            }))
        }
        body["tool_choice"]?.let { put("tool_choice", it) }
        body["stream"]?.let { put("stream", it) }
    }

    private fun claudeImageToOpenAi(block: JsonObject): JsonObject? {
        val source = block["source"] as? JsonObject ?: return null
        return when (text(source["type"], "")) {
            "base64" -> {
                val media = text(source["media_type"], "image/jpeg")
                val data = text(source["data"], "")
                if (data.isBlank()) null else buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:$media;base64,$data") })
                }
            }
            "url" -> {
                val url = text(source["url"], "")
                if (url.isBlank()) null else buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", url) })
                }
            }
            else -> null
        }
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
            copyNumber(body, "max_output_tokens", this, "max_tokens")
            body["tools"]?.let { put("tools", it) }
            body["tool_choice"]?.let { put("tool_choice", it) }
            body["stream"]?.let { put("stream", it) }
            (body["reasoning"] as? JsonObject)?.get("effort")?.let { put("reasoning_effort", it) }
            (body["text"] as? JsonObject)?.get("format")?.let { format ->
                val f = format as? JsonObject
                if (f != null && text(f["type"], "") == "json_schema") {
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject {
                            put("name", text(f["name"], "response"))
                            put("schema", f["schema"] ?: JsonObject(emptyMap()))
                            f["strict"]?.let { put("strict", it) }
                        })
                    })
                } else {
                    put("response_format", format)
                }
            }
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
                    put("role", "user"); put("content", openAiUserContentToClaude(m["content"]))
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
                put(
                    "parts",
                    if (role == "user") openAiUserContentToGemini(m["content"])
                    else buildJsonArray {
                        val t = flattenText(m["content"])
                        if (t.isNotBlank()) add(buildJsonObject { put("text", t) })
                    },
                )
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
                put("content", openAiMessageContentToResponses(m["content"], role))
            }
        }
        if (instructions.isNotBlank()) put("instructions", instructions.toString().trim())
        put("input", JsonArray(input))
        (body["max_completion_tokens"] ?: body["max_tokens"])?.let { put("max_output_tokens", it) }
        body["temperature"]?.let { put("temperature", it) }
        body["top_p"]?.let { put("top_p", it) }
        body["tool_choice"]?.let { put("tool_choice", it) }
        val effort = (body["reasoning_effort"] as? JsonPrimitive)?.content
            ?: ((body["reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive)?.content
        effort?.let { value -> put("reasoning", buildJsonObject { put("effort", value) }) }
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
        val toolCalls = mutableListOf<JsonObject>()
        (upstream["output"] as? JsonArray)?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            when (text(o["type"], "")) {
                "message" -> (o["content"] as? JsonArray)?.forEach { c ->
                    val co = c as? JsonObject ?: return@forEach
                    if (text(co["type"], "") == "output_text") content.append(text(co["text"], ""))
                }
                "function_call" -> toolCalls += buildJsonObject {
                    put("id", text(o["call_id"], text(o["id"], "")))
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", text(o["name"], ""))
                        put("arguments", text(o["arguments"], "{}"))
                    })
                }
            }
        }
        val usage = upstream["usage"] as? JsonObject
        val cached = number((usage?.get("input_tokens_details") as? JsonObject)?.get("cached_tokens"), 0L)
        return openAiEnvelope(
            model = model,
            content = content.toString(),
            finish = if (toolCalls.isNotEmpty()) "tool_calls" else "stop",
            prompt = number(usage?.get("input_tokens"), 0L) + cached,
            completion = number(usage?.get("output_tokens"), 0L),
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
            cached = cached,
        )
    }

    /** gemini-cli / antigravity wrap the Gemini payload in `{ response: {...} }`. */
    fun unwrapEnvelope(upstream: JsonObject): JsonObject =
        (upstream["response"] as? JsonObject) ?: upstream

    // ---------- response ----------

    fun responseBody(target: String, source: String, upstream: JsonObject, requestedModel: String): JsonObject {
        // Same dialect: return the native body. Round-tripping Claude thinking,
        // tool_use blocks or Responses items through OpenAI loses fields.
        if (target == source) return upstream
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
        val message = choice?.get("message") as? JsonObject
        val content = text(message?.get("content"), "")
        val usage = openAi["usage"] as? JsonObject
        val output = mutableListOf<JsonObject>()
        if (content.isNotBlank()) {
            output += buildJsonObject {
                put("type", "message")
                put("role", "assistant")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "output_text"); put("text", content) })
                })
            }
        }
        (message?.get("tool_calls") as? JsonArray)?.forEach { raw ->
            val call = raw as? JsonObject ?: return@forEach
            val fn = call["function"] as? JsonObject
            output += buildJsonObject {
                put("type", "function_call")
                put("call_id", text(call["id"], ""))
                put("name", text(fn?.get("name"), ""))
                put("arguments", text(fn?.get("arguments"), "{}"))
            }
        }
        return buildJsonObject {
            put("id", "resp_" + System.currentTimeMillis())
            put("object", "response")
            put("created_at", System.currentTimeMillis() / 1000)
            put("status", if (text(choice?.get("finish_reason"), "") == "length") "incomplete" else "completed")
            put("model", model)
            put("output", JsonArray(output))
            put("usage", buildJsonObject {
                put("input_tokens", number(usage?.get("prompt_tokens"), 0L))
                put("output_tokens", number(usage?.get("completion_tokens"), 0L))
                put("total_tokens", number(usage?.get("total_tokens"), 0L))
                val cached = number((usage?.get("prompt_tokens_details") as? JsonObject)?.get("cached_tokens"), 0L)
                if (cached > 0) {
                    put("input_tokens_details", buildJsonObject { put("cached_tokens", cached) })
                }
            })
        }
    }

    private fun claudeToOpenAiResponse(upstream: JsonObject, model: String): JsonObject {
        val content = StringBuilder()
        val toolCalls = mutableListOf<JsonObject>()
        (upstream["content"] as? JsonArray)?.forEach { b ->
            val obj = b as? JsonObject ?: return@forEach
            when (text(obj["type"], "text")) {
                "text" -> content.append(text(obj["text"], ""))
                "tool_use" -> toolCalls += buildJsonObject {
                    put("id", text(obj["id"], ""))
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", text(obj["name"], ""))
                        put("arguments", (obj["input"] ?: JsonObject(emptyMap())).toString())
                    })
                }
            }
        }
        val usage = upstream["usage"] as? JsonObject
        val cacheRead = number(usage?.get("cache_read_input_tokens"), 0L)
        val cacheCreate = number(usage?.get("cache_creation_input_tokens"), 0L)
        val prompt = number(usage?.get("input_tokens"), 0L) + cacheRead + cacheCreate
        val finish = openAiFinish(text(upstream["stop_reason"], "end_turn"))
        return openAiEnvelope(
            model = model,
            content = content.toString(),
            finish = finish,
            prompt = prompt,
            completion = number(usage?.get("output_tokens"), 0L),
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
            cached = cacheRead,
        )
    }

    private fun geminiToOpenAiResponse(upstream: JsonObject, model: String): JsonObject {
        val candidate = (upstream["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = StringBuilder()
        val toolCalls = mutableListOf<JsonObject>()
        ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray)?.forEach { p ->
            val part = p as? JsonObject ?: return@forEach
            text(part["text"], "").let { if (it.isNotEmpty()) content.append(it) }
            (part["functionCall"] as? JsonObject)?.let { call ->
                toolCalls += buildJsonObject {
                    put("id", "call_" + toolCalls.size)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", text(call["name"], ""))
                        put("arguments", (call["args"] ?: JsonObject(emptyMap())).toString())
                    })
                }
            }
        }
        val meta = upstream["usageMetadata"] as? JsonObject
        return openAiEnvelope(
            model = model,
            content = content.toString(),
            finish = when (text(candidate?.get("finishReason"), "").uppercase()) {
                "MAX_TOKENS" -> "length"
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter"
                else -> if (toolCalls.isNotEmpty()) "tool_calls" else "stop"
            },
            prompt = number(meta?.get("promptTokenCount"), 0L),
            completion = number(meta?.get("candidatesTokenCount"), 0L),
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
            cached = number(meta?.get("cachedContentTokenCount"), 0L),
        )
    }

    private fun openAiEnvelope(
        model: String,
        content: String,
        finish: String,
        prompt: Long,
        completion: Long,
        toolCalls: List<JsonObject>? = null,
        cached: Long = 0L,
    ): JsonObject =
        buildJsonObject {
            put("id", "chatcmpl-" + System.currentTimeMillis())
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                        toolCalls?.let { put("tool_calls", JsonArray(it)) }
                    })
                    put("finish_reason", finish)
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", prompt)
                put("completion_tokens", completion)
                put("total_tokens", prompt + completion)
                if (cached > 0) {
                    put("prompt_tokens_details", buildJsonObject { put("cached_tokens", cached) })
                }
            })
        }

    private fun openAiToClaudeResponse(openAi: JsonObject, model: String): JsonObject {
        val choice = (openAi["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = text(message?.get("content"), "")
        val usage = openAi["usage"] as? JsonObject
        return buildJsonObject {
            put("id", text(openAi["id"], "msg_" + System.currentTimeMillis()))
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", buildJsonArray {
                if (content.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", content) })
                (message?.get("tool_calls") as? JsonArray)?.forEach { raw ->
                    val call = raw as? JsonObject ?: return@forEach
                    val fn = call["function"] as? JsonObject
                    val args = text(fn?.get("arguments"), "{}")
                    val parsed = try { json.parseToJsonElement(args) } catch (_: Exception) { JsonObject(emptyMap()) }
                    add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", text(call["id"], ""))
                        put("name", text(fn?.get("name"), ""))
                        put("input", parsed)
                    })
                }
            })
            put("stop_reason", claudeStop(text(choice?.get("finish_reason"), "stop")))
            put("usage", buildJsonObject {
                put("input_tokens", number(usage?.get("prompt_tokens"), 0L))
                put("output_tokens", number(usage?.get("completion_tokens"), 0L))
                val cached = number((usage?.get("prompt_tokens_details") as? JsonObject)?.get("cached_tokens"), 0L)
                if (cached > 0) put("cache_read_input_tokens", cached)
            })
        }
    }

    private fun openAiToGeminiResponse(openAi: JsonObject, model: String): JsonObject {
        val choice = (openAi["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = text(message?.get("content"), "")
        val usage = openAi["usage"] as? JsonObject
        return buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            if (content.isNotBlank()) add(buildJsonObject { put("text", content) })
                            (message?.get("tool_calls") as? JsonArray)?.forEach { raw ->
                                val call = raw as? JsonObject ?: return@forEach
                                val fn = call["function"] as? JsonObject
                                val args = text(fn?.get("arguments"), "{}")
                                val parsed = try { json.parseToJsonElement(args) } catch (_: Exception) { JsonObject(emptyMap()) }
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", text(fn?.get("name"), ""))
                                        put("args", parsed)
                                    })
                                })
                            }
                        })
                    })
                    put("finishReason", when (text(choice?.get("finish_reason"), "stop")) {
                        "length" -> "MAX_TOKENS"
                        "content_filter" -> "SAFETY"
                        else -> "STOP"
                    })
                })
            })
            put("modelVersion", model)
            usage?.let {
                put("usageMetadata", buildJsonObject {
                    put("promptTokenCount", number(it["prompt_tokens"], 0L))
                    put("candidatesTokenCount", number(it["completion_tokens"], 0L))
                    put("totalTokenCount", number(it["total_tokens"], 0L))
                    val cached = number((it["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens"), 0L)
                    if (cached > 0) put("cachedContentTokenCount", cached)
                })
            }
        }
    }

    private fun openAiFinish(reason: String): String = when (reason) {
        "end_turn", "stop_sequence", "stop" -> "stop"
        "max_tokens", "length" -> "length"
        "tool_use", "tool_calls" -> "tool_calls"
        "refusal", "content_filter" -> "content_filter"
        else -> "stop"
    }

    private fun claudeStop(reason: String): String = when (reason) {
        "length", "max_tokens", "max_output_tokens" -> "max_tokens"
        "tool_calls", "tool_use" -> "tool_use"
        "content_filter", "refusal" -> "refusal"
        else -> "end_turn"
    }

    // ---------- streaming ----------

    fun sseDelta(target: String, rawLine: String, requestedModel: String, cliEnvelope: Boolean = false): String? {
        if (!rawLine.startsWith("data:")) return null
        val data = rawLine.removePrefix("data:").trim()
        if (data == "[DONE]") return "data: [DONE]"
        var obj = try { json.parseToJsonElement(data) as? JsonObject } catch (_: Exception) { null } ?: return null
        if (cliEnvelope) obj = unwrapEnvelope(obj)

        val delta = buildJsonObject {
            when (target) {
                Formats.GEMINI -> {
                    val cand = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val parts = (cand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                    val piece = StringBuilder()
                    val calls = mutableListOf<JsonObject>()
                    parts?.forEachIndexed { index, raw ->
                        val part = raw as? JsonObject ?: return@forEachIndexed
                        text(part["text"], "").let { if (it.isNotEmpty()) piece.append(it) }
                        (part["functionCall"] as? JsonObject)?.let { call ->
                            calls += buildJsonObject {
                                put("index", index)
                                put("id", "call_$index")
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", text(call["name"], ""))
                                    put("arguments", (call["args"] ?: JsonObject(emptyMap())).toString())
                                })
                            }
                        }
                    }
                    if (piece.isNotEmpty()) put("content", piece.toString())
                    if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
                }
                Formats.OPENAI_RESPONSES -> {
                    val type = text(obj["type"], "")
                    when {
                        type == "response.output_text.delta" || type == "response.refusal.delta" -> {
                            text(obj["delta"], "").let { if (it.isNotEmpty()) put("content", it) }
                        }
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            text(obj["delta"], "").let { if (it.isNotEmpty()) put("reasoning_content", it) }
                        }
                        type == "response.output_item.added" -> {
                            val item = obj["item"] as? JsonObject
                            if (text(item?.get("type"), "") == "function_call") {
                                put("tool_calls", buildJsonArray {
                                    add(buildJsonObject {
                                        put("index", number(obj["output_index"], 0L))
                                        put("id", text(item?.get("call_id"), ""))
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", text(item?.get("name"), ""))
                                            put("arguments", "")
                                        })
                                    })
                                })
                            }
                        }
                        type == "response.function_call_arguments.delta" -> {
                            put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("index", number(obj["output_index"], 0L))
                                    put("function", buildJsonObject {
                                        put("arguments", text(obj["delta"], ""))
                                    })
                                })
                            })
                        }
                    }
                }
                Formats.CLAUDE -> {
                    val type = text(obj["type"], "")
                    if (type == "message_start") put("role", "assistant")
                    if (type == "content_block_start") {
                        val block = obj["content_block"] as? JsonObject
                        when (text(block?.get("type"), "")) {
                            "text" -> text(block?.get("text"), "").let { if (it.isNotEmpty()) put("content", it) }
                            "thinking" -> text(block?.get("thinking"), "").let {
                                if (it.isNotEmpty()) put("reasoning_content", it)
                            }
                            "tool_use" -> put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("index", number(obj["index"], 0L))
                                    put("id", text(block?.get("id"), ""))
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", text(block?.get("name"), ""))
                                        put("arguments", "")
                                    })
                                })
                            })
                        }
                    }
                    if (type == "content_block_delta") {
                        val d = obj["delta"] as? JsonObject
                        when (text(d?.get("type"), "")) {
                            "text_delta" -> text(d?.get("text"), "").let {
                                if (it.isNotEmpty()) put("content", it)
                            }
                            "thinking_delta" -> text(d?.get("thinking"), "").let {
                                if (it.isNotEmpty()) put("reasoning_content", it)
                            }
                            "input_json_delta" -> put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("index", number(obj["index"], 0L))
                                    put("function", buildJsonObject {
                                        put("arguments", text(d?.get("partial_json"), ""))
                                    })
                                })
                            })
                        }
                    }
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
                    val piece = text(sourceDelta?.get("content"), text(msg?.get("content"), ""))
                    if (piece.isNotEmpty()) put("content", piece)
                }
            }
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

    /**
     * Best-effort OpenAI-shaped usage for a single upstream SSE data line. The
     * stream writer calls this per line and keeps the last non-null result so
     * native Claude / Responses terminals can report usage too. Returns null
     * when the line carries no real counts (content deltas, placeholders).
     */
    fun sseUsage(target: String, rawLine: String, cliEnvelope: Boolean = false): JsonObject? {
        if (!rawLine.startsWith("data:")) return null
        val data = rawLine.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return null
        var obj = try { json.parseToJsonElement(data) as? JsonObject } catch (_: Exception) { null } ?: return null
        if (cliEnvelope) obj = unwrapEnvelope(obj)

        var prompt = 0L
        var completion = 0L
        var cached = 0L
        (obj["usage"] as? JsonObject)?.let { usage ->
            prompt = maxOf(prompt, number(usage["prompt_tokens"], number(usage["input_tokens"], 0L)))
            completion = maxOf(completion, number(usage["completion_tokens"], number(usage["output_tokens"], 0L)))
            val details = usage["prompt_tokens_details"] as? JsonObject
            cached = maxOf(
                cached,
                number(details?.get("cached_tokens"), number(usage["cache_read_input_tokens"], 0L)),
            )
        }
        when (text(obj["type"], "")) {
            "message_start" -> {
                val usage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
                prompt = maxOf(
                    prompt,
                    number(usage?.get("input_tokens"), 0L) +
                        number(usage?.get("cache_read_input_tokens"), 0L) +
                        number(usage?.get("cache_creation_input_tokens"), 0L),
                )
                cached = maxOf(cached, number(usage?.get("cache_read_input_tokens"), 0L))
            }
            "message_delta" -> {
                val usage = obj["usage"] as? JsonObject
                completion = maxOf(completion, number(usage?.get("output_tokens"), 0L))
            }
        }
        (obj["usageMetadata"] as? JsonObject)?.let { meta ->
            prompt = maxOf(prompt, number(meta["promptTokenCount"], 0L))
            completion = maxOf(completion, number(meta["candidatesTokenCount"], 0L))
            cached = maxOf(cached, number(meta["cachedContentTokenCount"], 0L))
        }
        ((obj["response"] as? JsonObject)?.get("usage") as? JsonObject)?.let { usage ->
            prompt = maxOf(prompt, number(usage["input_tokens"], 0L))
            completion = maxOf(completion, number(usage["output_tokens"], 0L))
            val details = usage["input_tokens_details"] as? JsonObject
            cached = maxOf(cached, number(details?.get("cached_tokens"), 0L))
        }
        if (prompt <= 0L && completion <= 0L) return null
        return buildJsonObject {
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", prompt + completion)
            if (cached > 0L) put("cached_tokens", cached)
        }
    }

    /** Merge partial usage frames (Claude splits input and output across events). */
    fun mergeUsage(a: JsonObject?, b: JsonObject?): JsonObject? {
        if (a == null) return b
        if (b == null) return a
        val prompt = maxOf(number(a["prompt_tokens"], 0L), number(b["prompt_tokens"], 0L))
        val completion = maxOf(number(a["completion_tokens"], 0L), number(b["completion_tokens"], 0L))
        val cached = maxOf(number(a["cached_tokens"], 0L), number(b["cached_tokens"], 0L))
        return buildJsonObject {
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", prompt + completion)
            if (cached > 0L) put("cached_tokens", cached)
        }
    }

    fun terminalSseChunk(
        model: String,
        finishReason: String,
        usage: JsonObject? = null,
    ): String =
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
            usage?.let { u ->
                put("usage", buildJsonObject {
                    put("prompt_tokens", number(u["prompt_tokens"], 0L))
                    put("completion_tokens", number(u["completion_tokens"], 0L))
                    put("total_tokens", number(u["prompt_tokens"], 0L) + number(u["completion_tokens"], 0L))
                    val cached = number(u["cached_tokens"], 0L)
                    if (cached > 0L) {
                        put("prompt_tokens_details", buildJsonObject { put("cached_tokens", cached) })
                    }
                })
            }
        }).let { "data: $it" }

    private fun normalizeFinishReason(reason: String): String = when (reason) {
        "max_tokens", "max_output_tokens", "MAX_TOKENS" -> "length"
        "tool_use", "tool-calls" -> "tool_calls"
        "refusal" -> "content_filter"
        else -> reason.ifBlank { "stop" }
    }


    // ---------- vision / multipart content ----------

    /** OpenAI `content` may be a string or an array of text / image_url parts. */
    private fun openAiUserContentToClaude(content: JsonElement?): JsonElement {
        if (content !is JsonArray) return JsonPrimitive(flattenText(content))
        return buildJsonArray {
            content.forEach { part ->
                val obj = part as? JsonObject
                when (text(obj?.get("type"), "")) {
                    "text" -> text(obj?.get("text"), "").takeIf { it.isNotBlank() }?.let {
                        add(buildJsonObject { put("type", "text"); put("text", it) })
                    }
                    "image_url" -> {
                        val url = text((obj?.get("image_url") as? JsonObject)?.get("url"), "")
                        val parsed = parseDataUrl(url)
                        if (parsed != null) {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", parsed.first)
                                    put("data", parsed.second)
                                })
                            })
                        } else if (url.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject { put("type", "url"); put("url", url) })
                            })
                        }
                    }
                    else -> flattenText(part).takeIf { it.isNotBlank() }?.let {
                        add(buildJsonObject { put("type", "text"); put("text", it) })
                    }
                }
            }
        }
    }

    private fun openAiUserContentToGemini(content: JsonElement?): JsonArray = buildJsonArray {
        if (content !is JsonArray) {
            val t = flattenText(content)
            if (t.isNotBlank()) add(buildJsonObject { put("text", t) })
            return@buildJsonArray
        }
        content.forEach { part ->
            val obj = part as? JsonObject
            when (text(obj?.get("type"), "")) {
                "text" -> text(obj?.get("text"), "").takeIf { it.isNotBlank() }?.let {
                    add(buildJsonObject { put("text", it) })
                }
                "image_url" -> {
                    val url = text((obj?.get("image_url") as? JsonObject)?.get("url"), "")
                    val parsed = parseDataUrl(url)
                    if (parsed != null) {
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", parsed.first)
                                put("data", parsed.second)
                            })
                        })
                    }
                }
                else -> flattenText(part).takeIf { it.isNotBlank() }?.let {
                    add(buildJsonObject { put("text", it) })
                }
            }
        }
    }

    private fun openAiMessageContentToResponses(content: JsonElement?, role: String): JsonArray = buildJsonArray {
        if (content !is JsonArray) {
            add(buildJsonObject {
                put("type", if (role == "assistant") "output_text" else "input_text")
                put("text", flattenText(content))
            })
            return@buildJsonArray
        }
        content.forEach { part ->
            val obj = part as? JsonObject
            when (text(obj?.get("type"), "")) {
                "text" -> text(obj?.get("text"), "").takeIf { it.isNotBlank() }?.let {
                    add(buildJsonObject {
                        put("type", if (role == "assistant") "output_text" else "input_text")
                        put("text", it)
                    })
                }
                "image_url" -> {
                    val url = text((obj?.get("image_url") as? JsonObject)?.get("url"), "")
                    if (url.isNotBlank()) {
                        add(buildJsonObject { put("type", "input_image"); put("image_url", url) })
                    }
                }
                else -> flattenText(part).takeIf { it.isNotBlank() }?.let {
                    add(buildJsonObject {
                        put("type", if (role == "assistant") "output_text" else "input_text")
                        put("text", it)
                    })
                }
            }
        }
    }

    /** Splits `data:image/jpeg;base64,AAAA` into mime + payload. */
    private fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        if (!header.contains(";base64", ignoreCase = true)) return null
        val mime = header.substringBefore(';').ifBlank { "image/jpeg" }
        val data = url.substring(comma + 1)
        if (data.isBlank()) return null
        return mime to data
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
