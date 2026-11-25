package com.ninerouter.android.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Rates from open-sse/providers/pricing.js, exported at build time. */
object Pricing {
    private val json = Json { ignoreUnknownKeys = true }
    private var rates: Map<String, Rate> = emptyMap()

    data class Rate(val input: Double, val output: Double, val cached: Double)

    fun load(context: Context) {
        rates = try {
            val raw = context.assets.open("pricing.json").bufferedReader().use { it.readText() }
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return
            obj.mapNotNull { (model, value) ->
                val v = value as? JsonObject ?: return@mapNotNull null
                fun n(key: String) = (v[key] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                model to Rate(n("input"), n("output"), n("cached"))
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun cost(model: String, prompt: Int, completion: Int, cached: Int = 0): Double {
        val bare = model.substringAfterLast('/')
        // Thinking/effort suffixes share the base model's rate.
        val rate = rates[model] ?: rates[bare]
            ?: rates.entries.firstOrNull { (k, _) -> bare.startsWith(k) }?.value
            ?: return 0.0
        val nonCached = (prompt - cached).coerceAtLeast(0)
        return nonCached * rate.input / 1_000_000.0 +
            cached * (rate.cached.takeIf { it > 0 } ?: rate.input) / 1_000_000.0 +
            completion * rate.output / 1_000_000.0
    }
}
