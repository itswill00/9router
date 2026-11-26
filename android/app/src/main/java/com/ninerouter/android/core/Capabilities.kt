package com.ninerouter.android.core

import com.ninerouter.android.data.Catalog

/**
 * Capability rules shared by routing and the combo editor. The router uses them
 * to order candidates and to decide whether a capacity-adapter pool is needed.
 */
object Capabilities {
    fun supports(modelStr: String, required: Set<String>): Boolean {
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
}
