package com.ninerouter.android.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Build-time export of the upstream provider registry (no Node runtime on device). */
object Catalog {
    private val json = Json { ignoreUnknownKeys = true }
    private var byId: Map<String, ProviderDef> = emptyMap()

    fun load(context: Context) {
        val raw = context.assets.open("provider_registry.json").bufferedReader().use { it.readText() }
        val list = json.decodeFromString<List<ProviderDef>>(raw)
        val map = LinkedHashMap<String, ProviderDef>()
        for (p in list) {
            map[p.id] = p
            if (p.alias.isNotBlank() && p.alias != p.id) map.putIfAbsent(p.alias, p)
        }
        byId = map
    }

    fun find(idOrAlias: String): ProviderDef? = byId[idOrAlias]

    fun providers(): List<ProviderDef> = byId.values.distinctBy { it.id }.sortedBy { it.id }

    fun modelIds(): List<String> = providers().flatMap { it.models }.distinct().sorted()
}
