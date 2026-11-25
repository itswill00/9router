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
        data class Json(val body: String) : Outcome()
        data class Stream(
            val response: Response,
            val targetFormat: String,
            val sourceFormat: String,
            val model: String,
            val cliEnvelope: Boolean = false,
        ) : Outcome()
        data class Error(val status: Int, val message: String) : Outcome()
    }

    fun handle(clientBody: String, sourceFormat: String, responseFormat: String): Outcome {
        val body = parseObject(clientBody) ?: return Outcome.Error(400, "Invalid JSON body")
        val requestedModel = text(body["model"], "")
        if (requestedModel.isBlank()) return Outcome.Error(400, "Missing model")

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

        for (member in members) {
            val target = ModelResolver.resolve(member) ?: continue
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
                        return Outcome.Stream(response, providerFormat, responseFormat, requestedModel, cliEnvelope)
                    }
                    val raw = response.body?.string().orEmpty()
                    val parsed = parseObject(raw) ?: JsonObject(emptyMap())
                    val upstreamJson = if (cliEnvelope) Translator.unwrapEnvelope(parsed) else parsed
                    val finalJson = Translator.responseBody(providerFormat, responseFormat, upstreamJson, requestedModel)
                    trackUsage(target.provider, member, upstreamJson, true, clientBody, raw)
                    return Outcome.Json(json.encodeToString(finalJson))
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
                if (!decision.shouldFallback && !decision.requestScoped) return lastError!!
                if (decision.requestScoped) break // member rejected the request, try next member
            }
        }
        return lastError ?: Outcome.Error(503, "All models unavailable")
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
        return com.ninerouter.android.data.ProviderDef(
            id = node.id,
            alias = node.id,
            name = node.name,
            format = if (node.type.startsWith("anthropic")) Formats.CLAUDE else Formats.OPENAI,
            baseUrl = node.baseUrl,
        )
    }

    private fun pickAccount(provider: String, excluded: Set<String>, strategy: String): Connection? {
        val now = System.currentTimeMillis()
        val available = Store.state.connections.filter {
            it.enabled && it.provider == provider && it.id !in excluded && it.unavailableUntil <= now
        }
        if (available.isEmpty()) {
            val node = Store.state.nodes.firstOrNull { it.id == provider && it.apiKey.isNotBlank() }
            if (node != null && provider !in excluded) {
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
    ) {
        val usage = upstreamJson["usage"] as? JsonObject ?: upstreamJson["usageMetadata"] as? JsonObject
        fun n(key: String, alt: String) = (usage?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (usage?.get(alt) as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        // Upstream parity: when a provider omits usage metadata, estimate from
        // the wire bodies rather than recording a misleading zero.
        val prompt = n("prompt_tokens", "input_tokens").toInt()
            .takeIf { it > 0 } ?: estimateTokens(requestBody)
        val completion = n("completion_tokens", "output_tokens").toInt()
            .takeIf { it > 0 } ?: estimateTokens(responseBody)
        val details = usage?.get("prompt_tokens_details") as? JsonObject
        val cached = ((details?.get("cached_tokens") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (usage?.get("cache_read_input_tokens") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: 0).coerceAtMost(prompt)
        val cost = com.ninerouter.android.data.Pricing.cost(model, prompt, completion, cached)
        Store.mutate {
            this.usage.add(
                com.ninerouter.android.data.UsageEntry(
                    ts = System.currentTimeMillis(),
                    provider = provider,
                    model = model,
                    prompt = prompt,
                    completion = completion,
                    cached = cached,
                    cost = cost,
                    ok = ok,
                )
            )
            if (this.usage.size > 500) this.usage.removeAt(0)
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun parseObject(raw: String): JsonObject? =
        try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }

    private fun text(el: JsonPrimitive?, fallback: String) = el?.content?.takeIf { it.isNotBlank() } ?: fallback

    private fun text(el: kotlinx.serialization.json.JsonElement?, fallback: String) =
        (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallback
}
