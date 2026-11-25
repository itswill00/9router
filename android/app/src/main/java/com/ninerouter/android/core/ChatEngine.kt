package com.ninerouter.android.core

import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.Store
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        data class Stream(val response: Response, val targetFormat: String, val sourceFormat: String, val model: String) : Outcome()
        data class Error(val status: Int, val message: String) : Outcome()
    }

    fun handle(clientBody: String, sourceFormat: String, responseFormat: String): Outcome {
        val body = parseObject(clientBody) ?: return Outcome.Error(400, "Invalid JSON body")
        val requestedModel = text(body["model"], "")
        if (requestedModel.isBlank()) return Outcome.Error(400, "Missing model")

        val wantsStream = (body["stream"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        val combo = Store.state.combos.firstOrNull { it.name == requestedModel }
        val members = combo?.models?.ifEmpty { listOf(requestedModel) } ?: listOf(requestedModel)
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
                    if (wantsStream) {
                        return Outcome.Stream(response, providerFormat, responseFormat, requestedModel)
                    }
                    val raw = response.body?.string().orEmpty()
                    val upstreamJson = parseObject(raw) ?: JsonObject(emptyMap())
                    val finalJson = Translator.responseBody(providerFormat, responseFormat, upstreamJson, requestedModel)
                    trackUsage(target.provider, member, upstreamJson, true)
                    return Outcome.Json(json.encodeToString(finalJson))
                }

                val code = response.code
                val errorText = response.body?.string().orEmpty().take(400)
                val decision = Fallback.check(code, errorText)
                if (decision.cooldownMs > 0L) {
                    conn.unavailableUntil = System.currentTimeMillis() + decision.cooldownMs
                    Store.save()
                }
                lastError = Outcome.Error(code, errorText.ifBlank { "Upstream HTTP $code" })
                response.close()
                if (!decision.shouldFallback && !decision.requestScoped) return lastError!!
                if (decision.requestScoped) break // member rejected the request, try next member
            }
        }
        return lastError ?: Outcome.Error(503, "All models unavailable")
    }

    private fun emptyDef(id: String) = com.ninerouter.android.data.ProviderDef(id = id, alias = id)

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

    private fun trackUsage(provider: String, model: String, upstreamJson: JsonObject, ok: Boolean) {
        val usage = upstreamJson["usage"] as? JsonObject ?: upstreamJson["usageMetadata"] as? JsonObject
        fun n(key: String, alt: String) = (usage?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (usage?.get(alt) as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        Store.mutate {
            this.usage.add(
                com.ninerouter.android.data.UsageEntry(
                    ts = System.currentTimeMillis(),
                    provider = provider,
                    model = model,
                    prompt = n("prompt_tokens", "input_tokens").toInt(),
                    completion = n("completion_tokens", "output_tokens").toInt(),
                    ok = ok,
                )
            )
            if (this.usage.size > 500) this.usage.removeAt(0)
        }
    }

    private fun parseObject(raw: String): JsonObject? =
        try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }

    private fun text(el: JsonPrimitive?, fallback: String) = el?.content?.takeIf { it.isNotBlank() } ?: fallback

    private fun text(el: kotlinx.serialization.json.JsonElement?, fallback: String) =
        (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallback
}
