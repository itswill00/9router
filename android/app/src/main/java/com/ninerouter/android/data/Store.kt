package com.ninerouter.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.ninerouter.android.core.ConsoleLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Private, file-backed state. Secrets are AES-GCM boxed with the Android Keystore. */
object Store {
    private const val TAG = "9R-Store"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var file: File
    private lateinit var backup: File

    /** Last successfully written payload; identical saves skip the disk entirely. */
    private var lastSaved: String? = null

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
        backup = File(context.filesDir, "appstate.json.bak")
        ConsoleLog.info("[STORE] loading local state")
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
        combos = state.combos.toMutableList(),
        nodes = state.nodes.map { n -> n.copy(apiKey = Crypto.box(n.apiKey)) }.toMutableList(),
        apiKeys = state.apiKeys.map { k -> k.copy(key = Crypto.box(k.key)) }.toMutableList(),
        aliases = state.aliases.toMutableMap(),
        usage = state.usage.toMutableList(),
        // Deep-copy sessions so a concurrent streaming append cannot make the
        // encoder throw ConcurrentModificationException mid-write.
        chatSessions = state.chatSessions.map { session ->
            session.copy(messages = session.messages.toMutableList())
        }.toMutableList(),
    )

    private fun restore(loaded: AppState): AppState = loaded.copy(
        settings = loaded.settings.copy(apiKey = open(loaded.settings.apiKey)),
        connections = loaded.connections.map { c ->
            c.copy(
                apiKey = open(c.apiKey),
                accessToken = open(c.accessToken),
                refreshToken = open(c.refreshToken),
            )
        }.toMutableList(),
        nodes = loaded.nodes.map { n -> n.copy(apiKey = open(n.apiKey)) }.toMutableList(),
        apiKeys = loaded.apiKeys.map { k -> k.copy(key = open(k.key)) }.toMutableList(),
    )

    /** Throws instead of blanking a secret when the Keystore can no longer open
     * it, so a failed decrypt falls back to the backup rather than being saved
     * back as an empty credential. */
    private fun open(value: String): String =
        Crypto.unboxOrNull(value) ?: throw IllegalStateException("encrypted value unavailable")

    private fun load() {
        if (!::file.isInitialized) return
        if (!file.exists() && !backup.exists()) {
            state = AppState(settings = Settings(apiKey = newKey()))
            ConsoleLog.info("[STORE] first run, created a new state file")
            save()
            return
        }
        val fromMain = read(file)
        if (fromMain != null) {
            state = fromMain
            return
        }
        val fromBackup = read(backup)
        if (fromBackup != null) {
            // Keep the damaged file for recovery, then rewrite both slots from
            // the good backup so the next launch does not depend on it.
            quarantine(file)
            state = fromBackup
            save()
            return
        }
        quarantine(file)
        quarantine(backup)
        state = AppState(settings = Settings(apiKey = newKey()))
        save()
    }

    private fun read(source: File): AppState? {
        if (!source.exists()) return null
        return try {
            restore(json.decodeFromString<AppState>(source.readText()))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "state file unreadable: ${source.name}", e)
            ConsoleLog.error("[STORE] ${source.name} is unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun quarantine(source: File) {
        if (!source.exists()) return
        val target = File(source.parentFile, source.name + ".corrupt-" + System.currentTimeMillis())
        runCatching { source.renameTo(target) }
    }

    @Synchronized
    fun save() {
        if (!::file.isInitialized) return
        val encoded = try {
            json.encodeToString(snapshot())
        } catch (e: Exception) {
            android.util.Log.e(TAG, "state encode failed", e)
            ConsoleLog.error("[STORE] state encode failed: ${e.javaClass.simpleName}")
            return
        }
        if (encoded == lastSaved && file.exists()) return
        try {
            val directory = file.parentFile ?: return
            if (!directory.exists()) directory.mkdirs()
            val temp = File(directory, file.name + ".tmp")
            FileOutputStream(temp).use { out ->
                out.write(encoded.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (file.exists()) runCatching { file.copyTo(backup, overwrite = true) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            lastSaved = encoded
            revision += 1
            _version.value = revision
        } catch (e: Exception) {
            // A full disk must not crash the gateway thread. The in-memory state
            // stays authoritative and the next save retries.
            android.util.Log.e(TAG, "state save failed", e)
            ConsoleLog.error("[STORE] state save failed: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun mutate(block: AppState.() -> Unit) {
        block(state)
        save()
    }

    fun addConnection(connection: Connection) = mutate {
        val existing = connections.indexOfFirst {
            it.provider == connection.provider &&
                ((connection.email.isNotBlank() && it.email == connection.email) ||
                    (connection.accountId.isNotBlank() && it.accountId == connection.accountId))
        }
        if (existing >= 0) {
            // Re-login for the same account replaces the old row instead of
            // creating a duplicate routing candidate.
            connections[existing] = connection.copy(id = connections[existing].id)
        } else {
            connections.add(connection)
        }
        ConsoleLog.info("[STORE] saved connection ${connection.provider}")
    }

    fun removeConnection(id: String) = mutate {
        connections.removeAll { it.id == id }
        ConsoleLog.info("[STORE] removed connection $id")
    }

    fun addNode(node: ProviderNode) = mutate {
        nodes.add(node)
        ConsoleLog.info("[STORE] saved custom node ${node.prefix}")
    }

    fun removeNode(id: String) = mutate {
        nodes.removeAll { it.id == id }
        ConsoleLog.info("[STORE] removed custom node $id")
    }

    fun addApiKey(key: ApiKey) = mutate {
        apiKeys.add(key)
        ConsoleLog.info("[STORE] created API key ${key.name.ifBlank { key.id.take(8) }}")
    }

    fun newKey(): String = "sk-9r-" + UUID.randomUUID().toString().replace("-", "")
}
