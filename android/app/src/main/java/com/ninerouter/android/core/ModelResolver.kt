package com.ninerouter.android.core

import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Store

object ModelResolver {
    data class Target(val provider: String, val model: String)

    fun resolve(modelStr: String): Target? {
        val trimmed = modelStr.trim()
        if (trimmed.indexOf('/') >= 0) {
            val cut = trimmed.indexOf('/')
            val prefix = trimmed.substring(0, cut)
            val model = trimmed.substring(cut + 1)
            if (prefix.isEmpty() || model.isEmpty()) return null
            val node = Store.state.nodes.firstOrNull { it.prefix.equals(prefix, ignoreCase = true) }
            if (node != null) return Target(node.id, model)
            val provider = Catalog.find(prefix)?.id ?: prefix
            return Target(provider, model)
        }

        // Bare model: prefer a configured connection that advertises it.
        val activeProviders = Store.state.connections.filter { it.enabled }.map { it.provider }.toSet()
        val hit = Catalog.providers().firstOrNull { p ->
            p.id in activeProviders && p.models.any { it.equals(trimmed, ignoreCase = true) }
        }
        if (hit != null) return Target(hit.id, trimmed)

        val lower = trimmed.lowercase()
        val inferred = when {
            lower.startsWith("claude-") -> if ("claude" in activeProviders) "claude" else "anthropic"
            lower.startsWith("gemini-") -> "gemini"
            lower.startsWith("gpt-5") || lower.startsWith("codex-") -> if ("codex" in activeProviders) "codex" else "openai"
            lower.startsWith("gpt-") || lower.matches(Regex("o[134].*")) -> "openai"
            lower.startsWith("deepseek-") -> "deepseek"
            lower.startsWith("glm-") -> "glm"
            else -> "openai"
        }
        return Target(inferred, trimmed)
    }

    fun upstreamModel(provider: String, model: String): String {
        // Config-driven per-provider id rewrites can be added here (upstream's
        // PROVIDER_MODELS / getModelUpstreamId equivalent). Keep identity for MVP.
        return model
    }
}
