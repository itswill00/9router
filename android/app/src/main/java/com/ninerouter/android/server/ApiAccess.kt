package com.ninerouter.android.server

import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.AppState
import com.ninerouter.android.data.Exposure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Shared local-gateway authentication and per-key model authorization. */
internal object ApiAccess {
    fun isAuthorized(headers: Map<String, String>, state: AppState): Boolean {
        val settings = state.settings
        // Network mode always requires a key, regardless of the local toggle.
        // A blank key would otherwise leave the gateway open to the LAN/tailnet.
        if (Exposure.enabled(settings)) {
            if (settings.apiKey.isBlank()) return false
            val presented = presentedKey(headers)
            if (constantTimeEquals(presented, settings.apiKey)) return true
            return state.apiKeys.any { it.active && constantTimeEquals(presented, it.key) }
        }
        if (!settings.requireApiKey) return true
        // Fail closed: a required key that is blank is a broken install, not an
        // invitation to call the gateway anonymously.
        if (settings.apiKey.isBlank()) return false
        val presented = presentedKey(headers)
        if (constantTimeEquals(presented, settings.apiKey)) return true
        return state.apiKeys.any { it.active && constantTimeEquals(presented, it.key) }
    }

    /** Only the primary gateway key may change keys, providers or settings. */
    fun isPrimaryKey(headers: Map<String, String>, state: AppState): Boolean {
        val settings = state.settings
        if (!settings.requireApiKey && !Exposure.enabled(settings)) return true
        if (settings.apiKey.isBlank()) return false
        return constantTimeEquals(presentedKey(headers), settings.apiKey)
    }

    fun deniedModel(headers: Map<String, String>, body: JsonObject, state: AppState): String? {
        val apiKey = findApiKey(headers, state) ?: return null
        if (apiKey.allowedModels.isEmpty()) return null
        val model = (body["model"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return "Missing model"
        return if (isModelAllowed(model, apiKey, state)) null else "Model not allowed"
    }

    fun filterModels(
        headers: Map<String, String>,
        models: List<JsonObject>,
        state: AppState,
    ): List<JsonObject> {
        val apiKey = findApiKey(headers, state) ?: return models
        if (apiKey.allowedModels.isEmpty()) return models
        return models.filter { model ->
            val id = (model["id"] as? JsonPrimitive)?.content ?: return@filter false
            val isCombo = state.combos.any { it.name == id }
            apiKey.allowedModels.any { allowed ->
                if (state.combos.any { it.name == allowed }) isCombo && id == allowed
                else !isCombo && resolve(id, state) == resolve(allowed, state)
            }
        }
    }

    private fun isModelAllowed(model: String, apiKey: ApiKey, state: AppState): Boolean {
        val resolvedModel = resolve(model, state)
        // An explicit model grant always wins, even when that model also
        // happens to be a member of some combo.
        val explicitlyGranted = apiKey.allowedModels.any { allowed ->
            state.combos.none { it.name == allowed } && resolve(allowed, state) == resolvedModel
        }
        if (explicitlyGranted) return true
        if (model in apiKey.allowedModels && state.combos.any { it.name == model }) return true
        // A combo grant exposes the combo id, not its members as direct models.
        if (resolvedModel in allComboMembers(state)) return false
        return apiKey.allowedModels.any { allowed ->
            val allowedCombo = state.combos.firstOrNull { it.name == allowed }
            if (allowedCombo != null) {
                allowedCombo.models.any { resolve(it, state) == resolvedModel }
            } else {
                resolve(allowed, state) == resolvedModel
            }
        }
    }

    private fun comboMembers(name: String, state: AppState): List<String> =
        state.combos.firstOrNull { it.name == name }?.models.orEmpty()

    private fun allComboMembers(state: AppState): Set<String> =
        state.combos.flatMap { it.models }.map { resolve(it, state) }.toSet()

    private fun resolve(model: String, state: AppState): String {
        var current = model.trim()
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val next = state.aliases[current] ?: break
            current = next.trim()
        }
        return current
    }

    private fun findApiKey(headers: Map<String, String>, state: AppState): ApiKey? {
        val presented = presentedKey(headers)
        if (presented.isBlank() || constantTimeEquals(presented, state.settings.apiKey)) return null
        return state.apiKeys.firstOrNull { it.active && constantTimeEquals(presented, it.key) }
    }

    private fun presentedKey(headers: Map<String, String>): String {
        val normalized = headers.entries.associate { it.key.lowercase() to it.value.trim() }
        val authorization = normalized["authorization"].orEmpty()
        val bearer = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substringAfter(' ').trim()
        } else ""
        return bearer.ifBlank {
            normalized["x-api-key"].orEmpty().ifBlank { normalized["api-key"].orEmpty() }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
