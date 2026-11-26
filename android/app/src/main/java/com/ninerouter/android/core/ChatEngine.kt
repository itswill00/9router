package com.ninerouter.android.core

import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.Store
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android counterpart of src/sse/handlers/chat.js + open-sse/handlers/chatCore.js.
 * Keeps the same order: model/combo resolve → account pick → translate → RTK →
 * upstream → error classification → response translate → usage.
 */
class ChatEngine(
    private val upstream: Upstream = Upstream(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    sealed class Outcome {
        data class Json(val body: String, val member: String = "") : Outcome()
        data class Stream(
            val response: Response,
            val targetFormat: String,
            val sourceFormat: String,
            val model: String,
            val cliEnvelope: Boolean = false,
            val member: String = "",
        ) : Outcome()
        data class Error(val status: Int, val message: String) : Outcome()
    }

    fun handle(
        clientBody: String,
        sourceFormat: String,
        responseFormat: String,
        endpoint: String = "/v1/chat/completions",
    ): Outcome {
        val startedAt = System.currentTimeMillis()
        val body = parseObject(clientBody)
            ?: return fail(400, "Invalid JSON body", "", "", endpoint, startedAt)
        val requestedModel = text(body["model"], "")
        if (requestedModel.isBlank()) {
            return fail(400, "Missing model", "", "", endpoint, startedAt)
        }

        val wantsStream = (body["stream"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        val combo = Store.state.combos.firstOrNull { it.name == requestedModel }
        val rawMembers = combo?.models?.ifEmpty { listOf(requestedModel) } ?: listOf(requestedModel)
        val required = requiredCapabilities(body)
        val scored = if (required.isEmpty()) rawMembers
        else rawMembers.sortedByDescending { supports(it, required) }
        val members = if (combo?.strategy == "smart" && combo.routerModel.isNotBlank()) {
            smartOrder(body, scored, combo.routerModel) ?: scored
        } else scored
        val strategy = combo?.strategy ?: Store.state.settings.fallbackStrategy
        var lastError: Outcome.Error? = null
        var lastProvider = ""

        for (member in members) {
            val target = ModelResolver.resolve(member) ?: continue
            lastProvider = target.provider
            val def = Catalog.find(target.provider) ?: nodeDef(target.provider)
            val providerFormat = Formats.normalize(def?.format ?: Formats.OPENAI)
            val upstreamModel = ModelResolver.upstreamModel(target.provider, target.model)
            var translated = Translator.requestBody(sourceFormat, providerFormat, body, upstreamModel, wantsStream)
            if (Store.state.settings.rtkEnabled) {
                val (compressed, hits) = Rtk.apply(translated)
                translated = compressed
                if (hits > 0) android.util.Log.d("9R-RTK", "compress $member: $hits blocks")
            }
            val settings = Store.state.settings
            if (settings.cavemanEnabled) {
                translated = PromptInjectors.inject(
                    translated, providerFormat, PromptInjectors.cavemanPrompt(settings.cavemanLevel)
                )
            }
            if (settings.ponytailEnabled) {
                translated = PromptInjectors.inject(
                    translated, providerFormat, PromptInjectors.ponytailPrompt(settings.ponytailLevel)
                )
            }
            if (settings.customPromptEnabled && settings.customPrompt.isNotBlank()) {
                translated = PromptInjectors.inject(translated, providerFormat, settings.customPrompt)
            }

            val excluded = HashSet<String>()
            var accountMisses = 0
            while (true) {
                val conn = pickAccount(target.provider, excluded, strategy)
                if (conn == null) {
                    if (accountMisses == 0) {
                        lastError = Outcome.Error(404, "No active credentials for ${target.provider}")
                    }
                    break
                }
                accountMisses++
                excluded.add(conn.id)

                val response = try {
                    upstream.execute(def ?: emptyDef(target.provider), conn, translated, wantsStream)
                } catch (e: Exception) {
                    lastError = Outcome.Error(502, e.message ?: "Upstream error")
                    continue
                }

                if (response.isSuccessful) {
                    markSuccess(conn)
                    val cliEnvelope = isCliEnvelope(def ?: emptyDef(target.provider))
                    if (wantsStream) {
                        val tracked = trackStream(
                            response = response,
                            provider = target.provider,
                            model = member,
                            requestBody = clientBody,
                            providerFormat = providerFormat,
                            cliEnvelope = cliEnvelope,
                            endpoint = endpoint,
                            startedAt = startedAt,
                        )
                        return Outcome.Stream(tracked, providerFormat, responseFormat, requestedModel, cliEnvelope, member)
                    }
                    val raw = response.body?.string().orEmpty()
                    val parsed = parseObject(raw) ?: JsonObject(emptyMap())
                    val upstreamJson = if (cliEnvelope) Translator.unwrapEnvelope(parsed) else parsed
                    val finalJson = Translator.responseBody(providerFormat, responseFormat, upstreamJson, requestedModel)
                    trackUsage(
                        provider = target.provider,
                        model = member,
                        upstreamJson = upstreamJson,
                        ok = true,
                        requestBody = clientBody,
                        responseBody = raw,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        endpoint = endpoint,
                        stream = false,
                    )
                    return Outcome.Json(json.encodeToString(finalJson), member)
                }

                val code = response.code
                val errorText = response.body?.string().orEmpty().take(400)
                val decision = Fallback.check(code, errorText)
                conn.lastError = errorText.take(160)
                if (decision.cooldownMs > 0L) {
                    conn.unavailableUntil = System.currentTimeMillis() + decision.cooldownMs
                }
                Store.save()
                lastError = Outcome.Error(code, errorText.ifBlank { "Upstream HTTP $code" })
                response.close()
                if (!decision.shouldFallback && !decision.requestScoped) {
                    return fail(code, lastError!!.message, target.provider, requestedModel, endpoint, startedAt)
                }
                if (decision.requestScoped) break // member rejected the request, try next member
            }
        }
        val error = lastError ?: Outcome.Error(503, "All models unavailable")
        return fail(error.status, error.message, lastProvider, requestedModel, endpoint, startedAt)
    }

    private fun emptyDef(id: String) = com.ninerouter.android.data.ProviderDef(id = id, alias = id)

    private fun isCliEnvelope(def: com.ninerouter.android.data.ProviderDef): Boolean =
        def.format == "gemini-cli" || def.format == "antigravity"

    /** Smart Router: ask the configured router model to pick one combo member.
     * The router itself is resolved and translated through the same pipeline, so
     * OpenAI/Claude/Gemini router providers all work. Failure is fail-open: the
     * original order is used when the router is unreachable or ambiguous. */
    private fun smartOrder(body: JsonObject, members: List<String>, routerModel: String): List<String>? {
        if (members.size <= 1) return members
        val target = ModelResolver.resolve(routerModel) ?: return null
        val def = Catalog.find(target.provider) ?: nodeDef(target.provider) ?: return null
        val conn = pickAccount(target.provider, emptySet(), "fill-first") ?: return null
        val lastUser = (body["messages"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.lastOrNull { (it["role"] as? JsonPrimitive)?.content == "user" }
            ?.let { (it["content"] as? JsonPrimitive)?.content }
            .orEmpty()
            .take(2000)
        val prompt = buildJsonObject {
            put("model", target.model)
            put("max_tokens", 16)
            put("stream", false)
            put("messages", JsonArray(listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", "Pick exactly one model id from this list for the user request. Reply with the id only: ${members.joinToString(", ")}")
                },
                buildJsonObject { put("role", "user"); put("content", lastUser.ifBlank { "general coding task" }) },
            )))
        }
        val providerFormat = Formats.normalize(def.format)
        val translated = Translator.requestBody(Formats.OPENAI, providerFormat, prompt, target.model, false)
        val response = try { upstream.execute(def, conn, translated, false) } catch (_: Exception) { return null }
        val raw = response.use { res ->
            if (!res.isSuccessful) return null
            res.body?.string().orEmpty()
        }
        val obj = parseObject(raw) ?: return null
        val unwrapped = if (isCliEnvelope(def)) Translator.unwrapEnvelope(obj) else obj
        val openAi = Translator.responseBody(providerFormat, Formats.OPENAI, unwrapped, target.model)
        val content = ((openAi["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["message"] as? JsonObject }
            ?.let { (it["content"] as? JsonPrimitive)?.content }
            .orEmpty()
        val chosen = members.firstOrNull { content.contains(it, ignoreCase = true) } ?: return null
        return listOf(chosen) + members.filter { it != chosen }
    }

    /** Port of open-sse/services/combo.js detectRequiredCapabilities: only flags
     * capabilities that change routing, not generic text/tool traffic. */
    private fun requiredCapabilities(body: JsonObject): Set<String> {
        val caps = mutableSetOf<String>()
        (body["messages"] as? JsonArray)?.forEach { raw ->
            val content = (raw as? JsonObject)?.get("content") as? JsonArray ?: return@forEach
            content.forEach { part ->
                when ((part as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.content }) {
                    "image_url", "image", "input_image" -> caps.add("vision")
                    "input_audio", "audio_url" -> caps.add("audio")
                }
            }
        }
        if (body["tools"] != null) caps.add("tools")
        if (body["reasoning_effort"] != null || body["thinking"] != null) caps.add("reasoning")
        return caps
    }

    private fun supports(modelStr: String, required: Set<String>): Boolean {
        if (required.isEmpty()) return true
        val target = ModelResolver.resolve(modelStr) ?: return false
        val def = Catalog.find(target.provider) ?: return true
        val declared = def.modelCapabilities[target.model].orEmpty()
        val lowered = declared.map { it.lowercase() }
        val model = target.model.lowercase()
        val inferred = mutableSetOf<String>()
        if (lowered.any { it.contains("vision") } ||
            Regex("gpt-4o|gpt-5|gemini|claude-(3|4|5)|qwen-vl|llava|pixtral").containsMatchIn(model)
        ) inferred.add("vision")
        if (lowered.any { it.contains("audio") } || Regex("audio|whisper|tts").containsMatchIn(model)) {
            inferred.add("audio")
        }
        if (lowered.any { it.contains("reason") } || Regex("o1|o3|o4|thinking|reason").containsMatchIn(model)) {
            inferred.add("reasoning")
        }
        if (!Regex("tts|whisper|embedding|image").containsMatchIn(model)) inferred.add("tools")
        return required.all { need -> need in inferred || lowered.any { it.contains(need) } }
    }

    private fun nodeDef(id: String): com.ninerouter.android.data.ProviderDef? {
        val node = Store.state.nodes.firstOrNull { it.id == id } ?: return null
        val format = when {
            node.type.startsWith("anthropic") -> Formats.CLAUDE
            node.apiType.equals("responses", true) -> Formats.OPENAI_RESPONSES
            else -> Formats.OPENAI
        }
        return com.ninerouter.android.data.ProviderDef(
            id = node.id,
            alias = node.prefix,
            name = node.name,
            format = format,
            baseUrl = NodeProbe.chatUrl(node),
        )
    }

    private fun pickAccount(provider: String, excluded: Set<String>, strategy: String): Connection? {
        val now = System.currentTimeMillis()
        val available = Store.state.connections.filter {
            it.enabled && it.provider == provider && it.id !in excluded && it.unavailableUntil <= now
        }
        if (available.isEmpty()) {
            val node = Store.state.nodes.firstOrNull { it.id == provider && provider !in excluded }
            if (node != null) {
                return Connection(
                    id = "node:${node.id}",
                    provider = provider,
                    name = node.name,
                    apiKey = node.apiKey,
                )
            }
            return null
        }
        if (strategy == "round-robin") {
            val current = available.maxByOrNull { it.lastUsedAt }
            if (current != null && current.consecutiveUseCount < Store.state.settings.stickyLimit) {
                current.consecutiveUseCount += 1
                current.lastUsedAt = now
                Store.save()
                return current
            }
            val oldest = available.minByOrNull { it.lastUsedAt }!!
            oldest.consecutiveUseCount = 1
            oldest.lastUsedAt = now
            Store.save()
            return oldest
        }
        return available.minByOrNull { it.priority }
    }

    private fun markSuccess(conn: Connection) {
        conn.unavailableUntil = 0L
        conn.consecutiveUseCount = 0
        Store.save()
    }

    private fun trackUsage(
        provider: String,
        model: String,
        upstreamJson: JsonObject,
        ok: Boolean,
        requestBody: String = "",
        responseBody: String = "",
        latencyMs: Long = 0L,
        endpoint: String = "",
        stream: Boolean = false,
    ) {
        val usage = upstreamJson["usage"] as? JsonObject ?: upstreamJson["usageMetadata"] as? JsonObject
        // Upstream parity: when a provider omits usage metadata, estimate from
        // the wire bodies rather than recording a misleading zero.
        val prompt = (
            int(usage?.get("prompt_tokens"))
                ?: int(usage?.get("input_tokens"))
                ?: int(usage?.get("promptTokenCount"))
                ?: 0
            ).takeIf { it > 0 } ?: estimateTokens(requestBody)
        val completion = (
            int(usage?.get("completion_tokens"))
                ?: int(usage?.get("output_tokens"))
                ?: int(usage?.get("candidatesTokenCount"))
                ?: 0
            ).takeIf { it > 0 } ?: estimateTokens(responseBody)
        val details = usage?.get("prompt_tokens_details") as? JsonObject
        val cached = (
            int(details?.get("cached_tokens"))
                ?: int(usage?.get("cache_read_input_tokens"))
                ?: int(usage?.get("cachedContentTokenCount"))
                ?: 0
            ).coerceAtMost(prompt)
        recordUsage(
            provider = provider,
            model = model,
            prompt = prompt,
            completion = completion,
            cached = cached,
            ok = ok,
            status = if (ok) 200 else 502,
            latencyMs = latencyMs,
            stream = stream,
            endpoint = endpoint,
        )
    }

    /**
     * Wrap a successful streaming response so usage is recorded even though
     * the body is consumed lazily by the HTTP writer or the in-app chat.
     * Without this, every streamed request vanished from Usage & Logs.
     */
    private fun trackStream(
        response: Response,
        provider: String,
        model: String,
        requestBody: String,
        providerFormat: String,
        cliEnvelope: Boolean,
        endpoint: String,
        startedAt: Long,
    ): Response {
        val original = response.body ?: return response
        val tracked = StreamUsageBody(original) { raw, error ->
            val tokens = streamTokens(providerFormat, cliEnvelope, raw, requestBody)
            recordUsage(
                provider = provider,
                model = model,
                prompt = tokens.prompt,
                completion = tokens.completion,
                cached = tokens.cached,
                ok = error == null,
                status = if (error == null) 200 else 502,
                latencyMs = System.currentTimeMillis() - startedAt,
                stream = true,
                endpoint = endpoint,
                error = error?.message?.take(300).orEmpty(),
            )
        }
        return response.newBuilder().body(tracked).build()
    }

    private data class StreamTokens(val prompt: Int, val completion: Int, val cached: Int)

    /** Pull real token counts out of SSE frames when the provider sends them,
     * and estimate from the streamed text when it does not. */
    private fun streamTokens(
        providerFormat: String,
        cliEnvelope: Boolean,
        raw: String,
        requestBody: String,
    ): StreamTokens {
        var prompt = 0
        var completion = 0
        var cached = 0
        val text = StringBuilder()
        for (line in raw.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val obj = parseObject(data) ?: continue
            val inner = if (cliEnvelope) Translator.unwrapEnvelope(obj) else obj

            (inner["usage"] as? JsonObject)?.let { usage ->
                prompt = maxOf(prompt, int(usage["prompt_tokens"]) ?: int(usage["input_tokens"]) ?: 0)
                completion = maxOf(completion, int(usage["completion_tokens"]) ?: int(usage["output_tokens"]) ?: 0)
                val details = usage["prompt_tokens_details"] as? JsonObject
                cached = maxOf(
                    cached,
                    int(details?.get("cached_tokens")) ?: int(usage["cache_read_input_tokens"]) ?: 0,
                )
            }

            // Claude reports input tokens on message_start and output on message_delta.
            when (text(inner["type"], "")) {
                "message_start" -> {
                    val usage = (inner["message"] as? JsonObject)?.get("usage") as? JsonObject
                    prompt = maxOf(prompt, int(usage?.get("input_tokens")) ?: 0)
                    cached = maxOf(cached, int(usage?.get("cache_read_input_tokens")) ?: 0)
                }
                "message_delta" -> {
                    val usage = inner["usage"] as? JsonObject
                    completion = maxOf(completion, int(usage?.get("output_tokens")) ?: 0)
                }
            }

            // Gemini repeats cumulative usageMetadata on each chunk.
            (inner["usageMetadata"] as? JsonObject)?.let { meta ->
                prompt = maxOf(prompt, int(meta["promptTokenCount"]) ?: 0)
                completion = maxOf(completion, int(meta["candidatesTokenCount"]) ?: 0)
                cached = maxOf(cached, int(meta["cachedContentTokenCount"]) ?: 0)
            }

            // Responses API sends usage inside the completed event.
            ((inner["response"] as? JsonObject)?.get("usage") as? JsonObject)?.let { usage ->
                prompt = maxOf(prompt, int(usage["input_tokens"]) ?: 0)
                completion = maxOf(completion, int(usage["output_tokens"]) ?: 0)
            }

            // Reuse the translator so every upstream dialect contributes text.
            val converted = Translator.sseDelta(providerFormat, line, "", cliEnvelope) ?: continue
            val chunk = parseObject(converted.removePrefix("data:").trim()) ?: continue
            val choices = chunk["choices"] as? JsonArray
            val delta = (choices?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject
            (delta?.get("content") as? JsonPrimitive)?.content?.let { text.append(it) }
        }
        val finalPrompt = prompt.takeIf { it > 0 } ?: estimateTokens(requestBody)
        val finalCompletion = completion.takeIf { it > 0 } ?: estimateTokens(text.toString())
        return StreamTokens(finalPrompt, finalCompletion, cached.coerceAtMost(finalPrompt))
    }

    private fun recordUsage(
        provider: String,
        model: String,
        prompt: Int,
        completion: Int,
        cached: Int,
        ok: Boolean,
        status: Int,
        latencyMs: Long,
        stream: Boolean,
        endpoint: String,
        error: String = "",
    ) {
        val cost = if (ok) com.ninerouter.android.data.Pricing.cost(model, prompt, completion, cached) else 0.0
        Store.mutate {
            usage.add(
                com.ninerouter.android.data.UsageEntry(
                    ts = System.currentTimeMillis(),
                    provider = provider,
                    model = model,
                    prompt = prompt,
                    completion = completion,
                    cached = cached,
                    cost = cost,
                    ok = ok,
                    latencyMs = latencyMs,
                    status = status,
                    stream = stream,
                    endpoint = endpoint,
                    error = error,
                )
            )
            if (usage.size > 500) usage.removeAt(0)
        }
    }

    private fun fail(
        status: Int,
        message: String,
        provider: String,
        model: String,
        endpoint: String,
        startedAt: Long,
    ): Outcome.Error {
        recordUsage(
            provider = provider,
            model = model,
            prompt = 0,
            completion = 0,
            cached = 0,
            ok = false,
            status = status,
            latencyMs = System.currentTimeMillis() - startedAt,
            stream = false,
            endpoint = endpoint,
            error = message.take(300),
        )
        return Outcome.Error(status, message)
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun int(el: kotlinx.serialization.json.JsonElement?): Int? =
        (el as? JsonPrimitive)?.content?.toIntOrNull()

    private fun parseObject(raw: String): JsonObject? =
        try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }

    private fun text(el: JsonPrimitive?, fallback: String) = el?.content?.takeIf { it.isNotBlank() } ?: fallback

    private fun text(el: kotlinx.serialization.json.JsonElement?, fallback: String) =
        (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * Delegating response body that captures SSE bytes while they are read and
 * reports them once, at EOF or close. Keeps streaming zero-copy-ish for the
 * client while making usage accounting possible.
 */
private class StreamUsageBody(
    private val delegate: ResponseBody,
    private val onDone: (String, Throwable?) -> Unit,
) : ResponseBody() {
    private val finished = AtomicBoolean(false)
    private val capture = Buffer()
    private var buffered: BufferedSource? = null

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source(): BufferedSource {
        buffered?.let { return it }
        val forwarding = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = try {
                    super.read(sink, byteCount)
                } catch (t: Throwable) {
                    finish(t)
                    throw t
                }
                if (read == -1L) {
                    finish(null)
                } else if (read > 0) {
                    synchronized(this@StreamUsageBody) {
                        val room = MAX_CAPTURE - capture.size
                        if (room > 0) sink.copyTo(capture, sink.size - read, minOf(read, room))
                    }
                }
                return read
            }
        }
        return forwarding.buffer().also { buffered = it }
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            finish(null)
        }
    }

    private fun finish(error: Throwable?) {
        val raw = synchronized(this) {
            if (!finished.compareAndSet(false, true)) return
            try { capture.readUtf8() } catch (_: Exception) { "" }
        }
        onDone(raw, error)
    }

    private companion object {
        const val MAX_CAPTURE = 512L * 1024L
    }
}
