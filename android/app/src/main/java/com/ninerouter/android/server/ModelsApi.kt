package com.ninerouter.android.server

import com.ninerouter.android.data.AppState
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.ProviderDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure model catalog projection: testable without sockets or Android APIs. */
internal object ModelsApi {
    val KIND_SLUGS = mapOf(
        "image" to "image",
        "tts" to "tts",
        "stt" to "stt",
        "embedding" to "embedding",
        "image-to-text" to "imageToText",
        "web" to "web",
        "video" to "video",
    )

    fun list(state: AppState, kind: String? = null): List<JsonObject> =
        list(state, Catalog.providers(), kind)

    internal fun list(state: AppState, providers: List<ProviderDef>, kind: String? = null): List<JsonObject> =
        buildList {
            providers.forEach { provider ->
                val active = kind == "web" || state.connections.isEmpty() ||
                    state.connections.any { it.enabled && it.provider == provider.id }
                if (!active) return@forEach
                if (kind == "web") {
                    if (provider.serviceKinds.contains("webSearch")) {
                        add(modelEntry("${provider.alias}/search", provider.alias, "webSearch"))
                    }
                    if (provider.serviceKinds.contains("webFetch")) {
                        add(modelEntry("${provider.alias}/fetch", provider.alias, "webFetch"))
                    }
                    return@forEach
                }
                provider.models.forEach { model ->
                    val modelKind = modelKind(provider, model)
                    if (accepts(kind, modelKind, provider, model)) {
                        add(modelEntry("${provider.alias}/$model", provider.alias, modelKind, provider, model))
                    }
                }
            }
            if (kind == null || kind == "llm") {
                state.nodes.forEach { node ->
                    node.models.forEach { model ->
                        add(buildJsonObject {
                            put("id", "${node.prefix}/$model")
                            put("object", "model")
                            put("created", 0L)
                            put("owned_by", node.prefix)
                        })
                    }
                }
                state.combos.forEach { combo ->
                    add(buildJsonObject {
                        put("id", combo.name)
                        put("object", "model")
                        put("created", 0L)
                        put("owned_by", "combo")
                    })
                }
            }
        }.distinctBy { (it["id"] as? JsonPrimitive)?.content }

    fun find(state: AppState, id: String, kind: String? = null): JsonObject? =
        find(state, Catalog.providers(), id, kind)

    internal fun find(state: AppState, providers: List<ProviderDef>, id: String, kind: String? = null): JsonObject? =
        list(state, providers, kind).firstOrNull { (it["id"] as? JsonPrimitive)?.content == id }

    fun info(state: AppState, id: String, kind: String? = null): JsonObject? =
        info(state, Catalog.providers(), id, kind)

    internal fun info(
        state: AppState,
        providers: List<ProviderDef>,
        id: String,
        kind: String? = null,
    ): JsonObject? {
        val found = find(state, providers, id, kind) ?: return null
        val ownedBy = (found["owned_by"] as? JsonPrimitive)?.content.orEmpty()
        val provider = providers.firstOrNull { it.alias == ownedBy || it.id == ownedBy }
        val capabilities = provider?.serviceKinds.orEmpty().filter { it != "llm" }
        return buildJsonObject {
            found.forEach { (key, value) -> put(key, value) }
            if (capabilities.isNotEmpty()) {
                put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
            }
            put("created", 0L)
        }
    }

    private fun accepts(kind: String?, modelKind: String, provider: ProviderDef, model: String): Boolean = when (kind) {
        null, "llm" -> modelKind == "llm" || modelKind == "imageToText"
        "image-to-text" -> provider.serviceKinds.contains("imageToText") && VISION.containsMatchIn(model.lowercase())
        "web" -> false
        else -> modelKind == kind
    }

    private fun modelKind(provider: ProviderDef, model: String): String {
        val name = model.lowercase()
        if ("embed" in name) return "embedding"
        if (provider.serviceKinds == listOf("tts") || provider.id in TTS_ONLY) return "tts"
        if (provider.serviceKinds == listOf("stt") || provider.id in STT_ONLY) return "stt"
        if (provider.serviceKinds == listOf("image") || provider.id in IMAGE_ONLY) return "image"
        if (provider.serviceKinds == listOf("video") || provider.id in VIDEO_ONLY) return "video"
        if (Regex("tts|speech|voice").containsMatchIn(name) && "native-audio" !in name) return "tts"
        if (Regex("whisper|transcrib|scribe|universal-[23]|^nova-").containsMatchIn(name)) return "stt"
        if (Regex("dall-e|gpt-image|flux|stable-diffusion|sdxl|imagen|seedream|nano-banana|recraft|grok-2-image|image").containsMatchIn(name)) {
            return "image"
        }
        if (Regex("veo|sora|seedance|gen-?[34]|runway|video").containsMatchIn(name)) return "video"
        if (provider.serviceKinds.contains("imageToText") && VISION.containsMatchIn(name)) return "imageToText"
        return "llm"
    }

    private fun modelEntry(
        id: String,
        owner: String,
        kind: String,
        provider: ProviderDef? = null,
        rawModel: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "model")
        put("created", 0L)
        put("owned_by", owner)
        put("kind", kind)
        if (!rawModel.isNullOrBlank() && provider != null) put("root", "${provider.id}/$rawModel")
    }

    private val TTS_ONLY = setOf("aws-polly", "cartesia", "coqui", "edge-tts", "fish-audio", "google-tts", "inworld", "playht", "tortoise", "local-device")
    private val STT_ONLY = setOf("assemblyai", "deepgram", "selfhosted-stt")
    private val IMAGE_ONLY = setOf("black-forest-labs", "comfyui", "fal-ai", "nanobanana", "recraft", "runwayml", "sdwebui", "stability-ai", "topaz")
    private val VIDEO_ONLY = setOf("veoaifree-web")
    private val VISION = Regex("gpt-4o|gpt-5|gemini|claude-(3|4|5)|qwen-vl|llava|pixtral|vision|omni|image")
}
