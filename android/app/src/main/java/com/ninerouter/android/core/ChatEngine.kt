package com.ninerouter.android.core

import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Combo
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.Redact
import com.ninerouter.android.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

    /** Dedicated pool: fusion blocks inside each panel call, so running panel
     * children on Dispatchers.IO can deadlock when all 64 workers are blocked. */
    private val fusionDispatcher = java.util.concurrent.Executors.newFixedThreadPool(16)
        .asCoroutineDispatcher()

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
        headers: Map<String, String> = emptyMap(),
        depth: Int = 0,
    ): Outcome {
        val startedAt = System.currentTimeMillis()
        val body = parseObject(clientBody)
            ?: return fail(400, "Invalid JSON body", "", "", endpoint, startedAt)
        val requestedModel = text(body["model"], "")
        if (requestedModel.isBlank()) {
            return fail(400, "Missing model", "", "", endpoint, startedAt)
        }

        if (depth > 3) {
            return fail(400, "Combo nesting is too deep", "", requestedModel, endpoint, startedAt)
        }

        val wantsStream = (body["stream"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        val combo = Store.state.combos.firstOrNull { it.name == requestedModel }
        val strategy = combo?.strategy ?: Store.state.settings.fallbackStrategy

        if (combo != null && strategy == "fusion") {
            return handleFusion(
                body = body,
                sourceFormat = sourceFormat,
                responseFormat = responseFormat,
                endpoint = endpoint,
                headers = headers,
                depth = depth,
                combo = combo,
                wantsStream = wantsStream,
                startedAt = startedAt,
            )
        }

        val rawMembers = if (combo == null) {
            listOf(requestedModel)
        } else {
            combo.models.filter { it.isNotBlank() && it != combo.name }
                .ifEmpty { listOf(requestedModel) }
        }
        val required = requiredCapabilities(body)
        val sessionId = resolveSessionId(headers, body)
        var candidates = rawMembers
        if (combo != null && strategy == "round-robin") {
            candidates = ComboRotation.order(
                candidates,
                "combo:${combo.name}",
                Store.state.settings.comboStickyRoundRobinLimit,
            )
        }
        if (combo != null && strategy == "smart") {
            candidates = smartSelection(body, candidates, combo, sessionId) ?: candidates
        }
        if (required.isNotEmpty()) {
            // Stable: equal-capability members keep the chosen rotation/order.
            candidates = candidates.sortedByDescending { Capabilities.supports(it, required) }
        }
        val hard = required.intersect(setOf("vision", "audio"))
        if (hard.isNotEmpty() && candidates.none { Capabilities.supports(it, hard) }) {
            val pool = adapterPool(hard)
            if (pool.isNotEmpty()) candidates = pool + candidates
        }
        val members = candidates
        val accountStrategy = Store.state.settings.fallbackStrategy
        var lastError: Outcome.Error? = null
        var lastProvider = ""
        ConsoleLog.info("[CHAT] $endpoint ${requestedModel.ifBlank { "(no model)" }} stream=$wantsStream")

        for (member in members) {
            val target = ModelResolver.resolve(member) ?: continue
            lastProvider = target.provider
            ConsoleLog.info("[ROUTE] ${target.provider}/${target.model} as $member")
            val def = Catalog.find(target.provider) ?: nodeDef(target.provider)
            val providerFormat = Formats.normalize(def?.format ?: Formats.OPENAI)
            val upstreamModel = ModelResolver.upstreamModel(target.provider, target.model)
            var translated = Translator.requestBody(sourceFormat, providerFormat, body, upstreamModel, wantsStream)
            if (Store.state.settings.rtkEnabled) {
                val (compressed, hits) = Rtk.apply(translated)
                translated = compressed
                if (hits > 0) {
                    android.util.Log.d("9R-RTK", "compress $member: $hits blocks")
                    ConsoleLog.debug("[RTK] $member compressed $hits block(s)")
                }
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
            while (excluded.size < 32) {
                val conn = pickAccount(target.provider, excluded, accountStrategy)
                if (conn == null) {
                    if (lastError == null) {
                        lastError = Outcome.Error(404, "No active credentials for ${target.provider}")
                    }
                    break
                }
                accountMisses++
                excluded.add(conn.id)
                ConsoleLog.debug("[AUTH] ${target.provider} account ${conn.name.ifBlank { conn.id }}")

                val activeKey = ActiveRequests.begin(
                    target.provider,
                    member,
                    conn.name.ifBlank { conn.id },
                )
                val response = try {
                    upstream.execute(def ?: emptyDef(target.provider), conn, translated, wantsStream)
                } catch (e: Exception) {
                    ActiveRequests.end(activeKey, error = true)
                    ConsoleLog.error("[ROUTE] $member failed: ${e.message ?: e.javaClass.simpleName}")
                    lastError = Outcome.Error(502, e.message ?: "Upstream error")
                    continue
                }

                if (response.isSuccessful) {
                    val cliEnvelope = isCliEnvelope(def ?: emptyDef(target.provider))
                    if (wantsStream) {
                        // Some OAuth upstreams ignore stream=true and return a normal
                        // JSON body. Consume it instead of reading zero SSE lines.
                        val contentType = response.body?.contentType()?.toString().orEmpty()
                        if (contentType.contains("application/json", ignoreCase = true)) {
                            val raw = response.body?.string().orEmpty()
                            val parsed = parseObject(raw)
                            if (parsed == null) {
                                ActiveRequests.end(activeKey, error = true)
                                ConsoleLog.warn("[ROUTE] $member returned non-JSON for a streaming request")
                                lastError = Outcome.Error(502, "Upstream returned a non-JSON body")
                                response.close()
                                continue
                            }
                            val upstreamJson = if (cliEnvelope) Translator.unwrapEnvelope(parsed) else parsed
                            val finalJson = Translator.responseBody(providerFormat, responseFormat, upstreamJson, requestedModel)
                            markSuccess(conn)
                            ActiveRequests.end(activeKey)
                            ConsoleLog.info("[ROUTE] ok $member ${System.currentTimeMillis() - startedAt}ms")
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
                        markSuccess(conn)
                        ConsoleLog.info("[ROUTE] stream $member")
                        val tracked = trackStream(
                            response = response,
                            provider = target.provider,
                            model = member,
                            requestBody = clientBody,
                            providerFormat = providerFormat,
                            cliEnvelope = cliEnvelope,
                            endpoint = endpoint,
                            startedAt = startedAt,
                            activeKey = activeKey,
                        )
                        return Outcome.Stream(tracked, providerFormat, responseFormat, requestedModel, cliEnvelope, member)
                    }
                    val raw = response.body?.string().orEmpty()
                    val parsed = parseObject(raw)
                    if (parsed == null) {
                        ActiveRequests.end(activeKey, error = true)
                        ConsoleLog.warn("[ROUTE] $member returned non-JSON")
                        lastError = Outcome.Error(502, "Upstream returned a non-JSON body")
                        response.close()
                        continue
                    }
                    val upstreamJson = if (cliEnvelope) Translator.unwrapEnvelope(parsed) else parsed
                    val finalJson = Translator.responseBody(providerFormat, responseFormat, upstreamJson, requestedModel)
                    markSuccess(conn)
                    ActiveRequests.end(activeKey)
                    ConsoleLog.info("[ROUTE] ok $member ${System.currentTimeMillis() - startedAt}ms")
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
                val errorText = Redact.text(response.body?.string().orEmpty(), 400)
                ActiveRequests.end(activeKey, error = true)
                ConsoleLog.warn("[ROUTE] $member HTTP $code: ${errorText.take(220)}")
                val decision = Fallback.check(code, errorText)
                conn.lastError = Redact.text(errorText, 160)
                if (decision.cooldownMs > 0L) {
                    conn.unavailableUntil = System.currentTimeMillis() + decision.cooldownMs
                    ConsoleLog.warn("[COOLDOWN] ${target.provider} for ${decision.cooldownMs / 1000}s")
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
        ConsoleLog.error("[CHAT] failed ${error.status}: ${error.message}")
        return fail(error.status, error.message, lastProvider, requestedModel, endpoint, startedAt)
    }

    private fun emptyDef(id: String) = com.ninerouter.android.data.ProviderDef(id = id, alias = id)

    private fun isCliEnvelope(def: com.ninerouter.android.data.ProviderDef): Boolean =
        def.format == "gemini-cli" || def.format == "antigravity"

    private data class SmartPick(val choice: String, val confidence: Double?)

    /** Smart Router with the four upstream policies. A session-sticky choice
     * keeps the warm prompt cache; Smart Switch only changes it when the router
     * reports enough confidence, Strict Lock never re-asks, and Per-Turn asks
     * on every request. Router failures always fall back to the combo order. */
    private fun smartSelection(
        body: JsonObject,
        members: List<String>,
        combo: Combo,
        sessionId: String?,
    ): List<String>? {
        if (members.size <= 1) return members
        val policy = combo.smartPolicy.ifBlank { "smart" }
        val perTurn = policy == "per-turn" || !combo.sessionSticky || sessionId.isNullOrBlank()
        var existing = if (!perTurn && sessionId != null) SmartSessions.get(combo.name, sessionId) else null
        if (existing != null && existing !in members) {
            if (sessionId != null) SmartSessions.clear(combo.name, sessionId)
            existing = null
        }
        if (policy == "strict" && existing != null) return orderByChoice(members, existing)

        val pick = callSmartRouter(body, members, combo.routerModel)
        val threshold = when (policy) {
            "custom" -> combo.confidenceThreshold.toDouble()
            "strict" -> 0.0
            else -> 0.8
        }
        val choice = pick?.choice?.takeIf { it in members }
        val confidence = pick?.confidence
        if (choice != null) {
            ConsoleLog.debug("[SMART] ${combo.name} -> $choice confidence=${confidence ?: "n/a"}")
        }

        if (existing != null) {
            val confidentSwitch = choice != null && choice != existing &&
                confidence != null && confidence >= threshold
            if (confidentSwitch) {
                if (sessionId != null) SmartSessions.put(combo.name, sessionId, choice!!)
                return orderByChoice(members, choice!!)
            }
            return orderByChoice(members, existing)
        }

        if (choice != null && (threshold <= 0.0 || confidence == null || confidence >= threshold)) {
            if (!perTurn && sessionId != null) SmartSessions.put(combo.name, sessionId, choice)
            return orderByChoice(members, choice)
        }
        return null
    }

    private fun orderByChoice(members: List<String>, choice: String): List<String> =
        listOf(choice) + members.filter { it != choice }

    private fun callSmartRouter(body: JsonObject, members: List<String>, routerModel: String): SmartPick? {
        if (routerModel.isBlank()) return null
        val target = ModelResolver.resolve(routerModel) ?: return null
        val def = Catalog.find(target.provider) ?: nodeDef(target.provider) ?: return null
        val conn = pickAccount(target.provider, emptySet(), "fill-first") ?: return null
        val lastUser = lastUserText(body).ifBlank { "general coding task" }
        val prompt = buildJsonObject {
            put("model", target.model)
            put("max_tokens", 64)
            put("stream", false)
            put("messages", JsonArray(listOf(
                buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "Pick exactly one model id from this list for the user request. " +
                            "Reply with JSON only: {\"model\":\"<id>\",\"confidence\":0.0}. " +
                            "confidence is 0 to 1. Models: ${members.joinToString(", ")}",
                    )
                },
                buildJsonObject { put("role", "user"); put("content", lastUser) },
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
            ?.let { contentText(it["content"]) }
            .orEmpty()
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val payload = parseObject(cleaned)
        val choice = payload?.let { text(it["model"], "") }
            ?.takeIf { it in members }
            ?: members.firstOrNull { content.contains(it, ignoreCase = true) }
            ?: return null
        val confidence = payload?.let { (it["confidence"] as? JsonPrimitive)?.content?.toDoubleOrNull() }
            ?.coerceIn(0.0, 1.0)
        return SmartPick(choice, confidence)
    }

    private fun lastUserText(body: JsonObject): String {
        val fromMessages = (body["messages"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.lastOrNull { (it["role"] as? JsonPrimitive)?.content == "user" }
            ?.let { contentText(it["content"]) }
        if (!fromMessages.isNullOrBlank()) return fromMessages.take(2000)
        val fromInput = (body["input"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.lastOrNull { (it["role"] as? JsonPrimitive)?.content == "user" }
            ?.let { contentText(it["content"]) }
        if (!fromInput.isNullOrBlank()) return fromInput.take(2000)
        val fromContents = (body["contents"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.lastOrNull()
            ?.let { contentText(it["parts"]) }
        return fromContents.orEmpty().take(2000)
    }

    private fun contentText(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString("\n") { part ->
            when (part) {
                is JsonPrimitive -> part.content
                is JsonObject -> contentText(part["text"] ?: part["content"])
                else -> ""
            }
        }
        is JsonObject -> contentText(value["text"] ?: value["content"])
        else -> ""
    }

    /** Fusion: fan the request out to every panel model in parallel, then let a
     * judge synthesize one answer. Each panel call is a normal non-streaming
     * routing call, so usage, fallback and capability handling still apply. */
    private fun handleFusion(
        body: JsonObject,
        sourceFormat: String,
        responseFormat: String,
        endpoint: String,
        headers: Map<String, String>,
        depth: Int,
        combo: Combo,
        wantsStream: Boolean,
        startedAt: Long,
    ): Outcome {
        val panel = combo.models.filter { it.isNotBlank() && it != combo.name }.distinct()
        if (panel.isEmpty()) {
            return fail(400, "Fusion combo has no models", "", combo.name, endpoint, startedAt)
        }
        val judge = combo.judgeModel.ifBlank { panel.first() }
            .takeIf { it.isNotBlank() && it != combo.name } ?: panel.first()
        ConsoleLog.info("[FUSION] ${combo.name} panel=${panel.size} judge=$judge")

        val answers = runBlocking(fusionDispatcher) {
            panel.map { member ->
                async {
                    val panelBody = JsonObject(body.toMutableMap().apply {
                        remove("tools")
                        remove("tool_choice")
                        remove("stream_options")
                        put("model", JsonPrimitive(member))
                        put("stream", JsonPrimitive(false))
                    }).let { flattenToolHistory(it) }
                    val result = runCatching {
                        handle(
                            clientBody = json.encodeToString(panelBody),
                            sourceFormat = sourceFormat,
                            responseFormat = responseFormat,
                            endpoint = endpoint,
                            headers = headers,
                            depth = depth + 1,
                        )
                    }.getOrNull()
                    if (result is Outcome.Json) {
                        val answer = parseObject(result.body)
                            ?.let { extractText(it, responseFormat) }
                            .orEmpty()
                            .trim()
                        if (answer.isNotEmpty()) member to answer else null
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (answers.isEmpty()) {
            ConsoleLog.error("[FUSION] ${combo.name} panel failed")
            return fail(503, "All fusion panel models failed", "", combo.name, endpoint, startedAt)
        }
        val judgeBody = appendJudgeTurn(body, answers, judge, wantsStream)
        return handle(
            clientBody = json.encodeToString(judgeBody),
            sourceFormat = sourceFormat,
            responseFormat = responseFormat,
            endpoint = endpoint,
            headers = headers,
            depth = depth + 1,
        )
    }

    private fun flattenToolHistory(body: JsonObject): JsonObject {
        val messages = body["messages"] as? JsonArray ?: return body
        val flat = messages.map { raw ->
            val msg = raw as? JsonObject ?: return@map raw
            when ((msg["role"] as? JsonPrimitive)?.content) {
                "tool", "function" -> buildJsonObject {
                    put("role", "assistant")
                    put("content", "[Tool result: " + contentText(msg["content"]) + "]")
                }
                "assistant" -> {
                    val calls = msg["tool_calls"] as? JsonArray
                    if (calls.isNullOrEmpty()) {
                        msg
                    } else {
                        val names = calls.mapNotNull { call ->
                            ((call as? JsonObject)?.get("function") as? JsonObject)
                                ?.let { text(it["name"], "") }
                                ?.takeIf { it.isNotBlank() }
                        }
                        val base = contentText(msg["content"])
                        buildJsonObject {
                            put("role", "assistant")
                            put(
                                "content",
                                base + (if (base.isNotBlank()) "\n" else "") +
                                    "[Called tools: " + names.joinToString(", ") + "]",
                            )
                        }
                    }
                }
                else -> msg
            }
        }
        return JsonObject(body + ("messages" to JsonArray(flat)))
    }

    private fun appendJudgeTurn(
        body: JsonObject,
        answers: List<Pair<String, String>>,
        judge: String,
        wantsStream: Boolean,
    ): JsonObject {
        val prompt = buildJudgePrompt(answers)
        val updated: JsonObject = when {
            body["messages"] is JsonArray -> {
                val list = (body["messages"] as JsonArray) +
                    buildJsonObject { put("role", "user"); put("content", prompt) }
                JsonObject(body + ("messages" to JsonArray(list)))
            }
            body["input"] is JsonArray -> {
                val list = (body["input"] as JsonArray) +
                    buildJsonObject { put("role", "user"); put("content", prompt) }
                JsonObject(body + ("input" to JsonArray(list)))
            }
            body["contents"] is JsonArray -> {
                val list = (body["contents"] as JsonArray) + buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                }
                JsonObject(body + ("contents" to JsonArray(list)))
            }
            else -> JsonObject(
                body + ("messages" to buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", prompt) })
                })
            )
        }
        return JsonObject(updated.toMutableMap().apply {
            put("model", JsonPrimitive(judge))
            put("stream", JsonPrimitive(wantsStream))
        })
    }

    private fun buildJudgePrompt(answers: List<Pair<String, String>>): String {
        val panel = answers.mapIndexed { index, (_, text) ->
            "[Source ${index + 1}]\n$text"
        }.joinToString("\n\n")
        return """
            You are the JUDGE in a model-fusion panel. ${answers.size} expert models independently answered the user's most recent request. Their responses are below, anonymized by source.

            Do NOT mention that multiple models were used, and do NOT refer to the sources. Produce ONE authoritative final answer addressed directly to the user.

            First, internally analyze the panel for consensus, contradictions, partial coverage, unique insights and blind spots. Then write the best possible final answer grounded in that analysis.

            === PANEL RESPONSES ===
            $panel
            === END PANEL RESPONSES ===

            Now write the final answer to the user's original request.
        """.trimIndent()
    }

    private fun extractText(json: JsonObject, format: String): String = when (format) {
        Formats.CLAUDE -> {
            val blocks = json["content"] as? JsonArray
            if (blocks != null) {
                blocks.joinToString("") { block ->
                    (block as? JsonObject)?.let { text(it["text"], "") }.orEmpty()
                }
            } else {
                contentText(json["content"])
            }
        }
        Formats.OPENAI_RESPONSES -> (json["output"] as? JsonArray)?.joinToString("") { item ->
            ((item as? JsonObject)?.get("content") as? JsonArray)?.joinToString("") { part ->
                (part as? JsonObject)?.let { text(it["text"], "") }.orEmpty()
            }.orEmpty()
        }.orEmpty()
        Formats.GEMINI -> ((json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["content"] as? JsonObject }
            ?.let { it["parts"] as? JsonArray }
            ?.joinToString("") { part -> (part as? JsonObject)?.let { text(it["text"], "") }.orEmpty() }
            .orEmpty()
        else -> ((json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["message"] as? JsonObject }
            ?.let { contentText(it["content"]) }
            .orEmpty()
    }

    private fun adapterPool(hard: Set<String>): List<String> {
        val adapter = Store.state.settings.capacityAdapter
        val pools = buildList {
            if ("vision" in hard) add("vision" to adapter.vision)
            if ("audio" in hard) add("audio" to adapter.audio)
        }
        val out = mutableListOf<String>()
        for ((key, pool) in pools) {
            if (!pool.enabled) continue
            var models = pool.models.distinct()
            if (pool.roundRobin) models = ComboRotation.order(models, "adapter:$key", 1)
            out += models
        }
        return out.distinct().filter { Capabilities.supports(it, hard) }
    }

    private fun resolveSessionId(headers: Map<String, String>, body: JsonObject): String? {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 256 }

        header("x-session-id")?.let { return it }
        header("session-id")?.let { return it }
        header("session_id")?.let { return it }
        header("x-amp-thread-id")?.let { return it }
        header("x-client-request-id")?.let { return it }
        header("x-claude-code-session-id")?.let { return "claude:$it" }

        val fromBody = (body["session_id"] as? JsonPrimitive)?.content
            ?: (body["conversation_id"] as? JsonPrimitive)?.content
            ?: (body["prompt_cache_key"] as? JsonPrimitive)?.content
            ?: ((body["metadata"] as? JsonObject)?.get("user_id") as? JsonPrimitive)?.content
        return fromBody?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
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
            // A provider node is a synthetic account. Custom nodes can carry
            // several keys; each key gets its own synthetic connection so the
            // existing retry loop falls through to the next key on failure.
            val node = Store.state.nodes.firstOrNull { it.id == provider } ?: return null
            val keys = node.effectiveKeys()
            if (keys.isEmpty()) {
                val id = "node:${node.id}"
                if (id in excluded) return null
                return Connection(id = id, provider = provider, name = node.name)
            }
            val candidates = keys.mapIndexed { index, key ->
                index to Connection(
                    id = "node:${node.id}#$index",
                    provider = provider,
                    name = if (keys.size == 1) node.name else "${node.name} · ${index + 1}",
                    apiKey = key,
                    priority = index,
                )
            }.filter { it.second.id !in excluded }
            if (candidates.isEmpty()) return null
            val chosen = if (strategy == "round-robin") {
                val start = node.keyCursor.coerceAtLeast(0) % keys.size
                candidates.firstOrNull { it.first >= start } ?: candidates.first()
            } else {
                candidates.minByOrNull { it.first }!!
            }
            if (strategy == "round-robin") {
                node.keyCursor = (chosen.first + 1) % keys.size
                Store.save()
            }
            return chosen.second
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
        // Do not reset consecutiveUseCount here: the sticky round-robin counter
        // is what makes the next request rotate after stickyLimit successes.
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
        val promptBase = (
            int(usage?.get("prompt_tokens"))
                ?: int(usage?.get("input_tokens"))
                ?: int(usage?.get("promptTokenCount"))
                ?: 0
            )
        val cacheRead = int(usage?.get("cache_read_input_tokens")) ?: 0
        val cacheCreate = int(usage?.get("cache_creation_input_tokens")) ?: 0
        val prompt = (promptBase + cacheRead + cacheCreate)
            .takeIf { it > 0 } ?: estimateTokens(requestBody)
        val completion = (
            int(usage?.get("completion_tokens"))
                ?: int(usage?.get("output_tokens"))
                ?: int(usage?.get("candidatesTokenCount"))
                ?: 0
            ).takeIf { it > 0 } ?: estimateTokens(responseBody)
        val details = usage?.get("prompt_tokens_details") as? JsonObject
        val cached = if (cacheRead > 0 || cacheCreate > 0) {
            cacheRead
        } else {
            int(details?.get("cached_tokens"))
                ?: int(usage?.get("cachedContentTokenCount"))
                ?: 0
        }.coerceAtMost(prompt)
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
        activeKey: String,
    ): Response {
        val original = response.body
        if (original == null) {
            ActiveRequests.end(activeKey)
            return response
        }
        val tracked = StreamUsageBody(original) { raw, error ->
            ActiveRequests.end(activeKey, error = error != null)
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
                error = Redact.text(error?.message.orEmpty(), 300),
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
                val details = usage["prompt_tokens_details"] as? JsonObject
                prompt = maxOf(
                    prompt,
                    (int(usage["prompt_tokens"]) ?: int(usage["input_tokens"]) ?: 0) +
                        (int(usage["cache_read_input_tokens"]) ?: 0) +
                        (int(usage["cache_creation_input_tokens"]) ?: 0),
                )
                completion = maxOf(completion, int(usage["completion_tokens"]) ?: int(usage["output_tokens"]) ?: 0)
                cached = maxOf(
                    cached,
                    int(details?.get("cached_tokens"))
                        ?: int(usage["cache_read_input_tokens"])
                        ?: int(usage["cachedContentTokenCount"])
                        ?: 0,
                )
            }

            // Claude reports input tokens on message_start and output on message_delta.
            when (text(inner["type"], "")) {
                "message_start" -> {
                    val usage = (inner["message"] as? JsonObject)?.get("usage") as? JsonObject
                    prompt = maxOf(
                        prompt,
                        (int(usage?.get("input_tokens")) ?: 0) +
                            (int(usage?.get("cache_read_input_tokens")) ?: 0) +
                            (int(usage?.get("cache_creation_input_tokens")) ?: 0),
                    )
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
            error = Redact.text(message, 300),
        )
        return Outcome.Error(status, Redact.text(message, 400))
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
    private val tail = Buffer()
    private var truncated = false
    private var eof = false
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
                    eof = true
                    finish(null)
                } else if (read > 0) {
                    synchronized(this@StreamUsageBody) {
                        val room = MAX_CAPTURE - capture.size
                        if (room > 0) sink.copyTo(capture, sink.size - read, minOf(read, room))
                        if (read > room) truncated = true
                        // Usage usually arrives in the final event; keep a bounded
                        // tail so a long answer cannot hide the terminal counts.
                        val tailBytes = minOf(read, MAX_TAIL)
                        sink.copyTo(tail, sink.size - tailBytes, tailBytes)
                        if (tail.size > MAX_TAIL) tail.skip(tail.size - MAX_TAIL)
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
            // A close before EOF means the client cancelled or the upstream
            // truncated the answer; do not record that as a successful stream.
            finish(if (eof) null else java.io.IOException("stream closed before completion"))
        }
    }

    private fun finish(error: Throwable?) {
        val raw = synchronized(this) {
            if (!finished.compareAndSet(false, true)) return
            try {
                if (truncated) tail.readUtf8() else capture.readUtf8()
            } catch (_: Exception) { "" }
        }
        onDone(raw, error)
    }

    private companion object {
        const val MAX_CAPTURE = 512L * 1024L
        const val MAX_TAIL = 64L * 1024L
    }
}
