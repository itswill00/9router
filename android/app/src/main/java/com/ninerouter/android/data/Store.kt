package com.ninerouter.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Private, file-backed state. Secrets are AES-GCM boxed with the Android Keystore. */
object Store {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private lateinit var file: File

    var state: AppState = AppState()
        private set

    /** Compose snapshot revision. Every save bumps it so switches/toggles and
     * list edits recompose without abusing mutable list identity. */
    var revision by mutableIntStateOf(0)
        private set

    /** Observable mirror of [revision] for screens that need an explicit,
     * thread-safe live subscription instead of reading the mutable state. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "appstate.json")
        load()
    }

    private fun snapshot(): AppState = state.copy(
        settings = state.settings.copy(apiKey = Crypto.box(state.settings.apiKey)),
        connections = state.connections.map { c ->
            c.copy(
                apiKey = Crypto.box(c.apiKey),
                accessToken = Crypto.box(c.accessToken),
                refreshToken = Crypto.box(c.refreshToken),
            )
        }.toMutableList(),
        nodes = state.nodes.map { n -> n.copy(apiKey = Crypto.box(n.apiKey)) }.toMutableList(),
        apiKeys = state.apiKeys.map { k -> k.copy(key = Crypto.box(k.key)) }.toMutableList(),
    )

    private fun restore(loaded: AppState): AppState = loaded.copy(
            settings = loaded.settings.copy(apiKey = Crypto.unbox(loaded.settings.apiKey)),
            connections = loaded.connections.map { c ->
                c.copy(
                    apiKey = Crypto.unbox(c.apiKey),
                    accessToken = Crypto.unbox(c.accessToken),
                    refreshToken = Crypto.unbox(c.refreshToken),
                )
            }.toMutableList(),
            nodes = loaded.nodes.map { n -> n.copy(apiKey = Crypto.unbox(n.apiKey)) }.toMutableList(),
            apiKeys = loaded.apiKeys.map { k -> k.copy(key = Crypto.unbox(k.key)) }.toMutableList(),
        )

    private fun load() {
        if (!::file.isInitialized || !file.exists()) {
            state = AppState(settings = Settings(apiKey = newKey()))
            save()
            return
        }
        state = try {
            restore(json.decodeFromString<AppState>(file.readText()))
        } catch (_: Exception) {
            AppState(settings = Settings(apiKey = newKey()))
        }
    }

    @Synchronized
    fun save() {
        if (!::file.isInitialized) return
        file.writeText(json.encodeToString(snapshot()))
        revision += 1
        _version.value = revision
    }

    @Synchronized
    fun mutate(block: AppState.() -> Unit) {
        block(state)
        save()
    }

    fun newKey(): String = "sk-9r-" + UUID.randomUUID().toString().replace("-", "")
}
