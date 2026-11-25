package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ninerouter.android.GatewayService
import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.OAuthFlow
import com.ninerouter.android.core.Translator
import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Combo
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderDef
import com.ninerouter.android.data.ProviderNode
import com.ninerouter.android.data.Store
import com.ninerouter.android.server.GatewayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// ---------------------------------------------------------------------------
// Endpoint
// ---------------------------------------------------------------------------

@Composable
fun EndpointScreen(refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val running by GatewayState.running.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader("Endpoint", "API endpoint configuration")
        Panel {
            StatusRow("Base URL", "${GatewayState.BASE_URL}/v1")
            Spacer(Modifier.height(6.dp))
            StatusRow("Dashboard", GatewayState.BASE_URL)
            Spacer(Modifier.height(6.dp))
            StatusRow("Status", if (running) "Running" else "Stopped")
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (running) "Shutdown" else "Start gateway", onClick = {
                val intent = Intent(context, GatewayService::class.java)
                if (running) context.stopService(intent) else context.startForegroundService(intent)
                refresh()
            })
            Spacer(Modifier.height(10.dp))
            Text(
                "Service is running on this device. Shutdown will stop the local gateway and disconnect all clients.",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
        }
        Panel {
            Text("API key", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
            Spacer(Modifier.height(8.dp))
            Text(Store.state.settings.apiKey, color = NineRouterColors.Brand)
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(Store.state.settings.apiKey))
                    Toast.makeText(context, "API key copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                TextButton(onClick = {
                    Store.mutate { settings = settings.copy(apiKey = Store.newKey()) }
                    refresh()
                }) { Text("Regenerate") }
            }
        }
        Panel {
            Text("Client configuration", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
            Spacer(Modifier.height(8.dp))
            Text(
                "Base URL: ${GatewayState.BASE_URL}/v1\nAPI key: ${Store.state.settings.apiKey}\nModel: openai/gpt-5-mini",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Combos
// ---------------------------------------------------------------------------

@Composable
fun CombosScreen(refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("fallback") }
    var routerModel by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            SectionHeader("Combos", "Model combos with fallback")
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Add combo", onClick = { showDialog = true })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(Store.state.combos, key = { it.name }) { combo ->
                androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 12.dp)) {
                    Panel {
                        Text(combo.name, style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                        Spacer(Modifier.height(4.dp))
                        Text(combo.models.joinToString(" → "), color = NineRouterColors.TextMuted)
                        Text(
                            "strategy: ${combo.strategy}" + if (combo.routerModel.isNotBlank()) " · router: ${combo.routerModel}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = NineRouterColors.TextSubtle,
                        )
                        TextButton(onClick = {
                            Store.state.combos.removeAll { it.name == combo.name }
                            Store.save()
                            refresh()
                        }) { Text("Remove") }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Add combo", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Combo name") }, singleLine = true)
                    OutlinedTextField(models, { models = it }, label = { Text("Models, comma separated") })
                    OutlinedTextField(strategy, { strategy = it }, label = { Text("Strategy: fallback | round-robin | smart") }, singleLine = true)
                    OutlinedTextField(routerModel, { routerModel = it }, label = { Text("Router model (smart only)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = models.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (name.isNotBlank() && list.isNotEmpty()) {
                        Store.state.combos.add(Combo(name.trim(), list, strategy.trim(), routerModel.trim()))
                        Store.save()
                        name = ""; models = ""; strategy = "fallback"; routerModel = ""
                        showDialog = false
                        refresh()
                    }
                }) { Text("Save", color = NineRouterColors.Brand) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Usage
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    val s = Store.state.settings
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyName by remember { mutableStateOf("") }
    var keyAllowed by remember { mutableStateOf("") }
    var createdKey by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader("Settings", "Manage your preferences")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.requireApiKey, { Store.mutate { settings = settings.copy(requireApiKey = it) }; refresh() })
                Spacer(Modifier.width(10.dp))
                Text("Require API key", color = NineRouterColors.TextMain)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.rtkEnabled, { Store.mutate { settings = settings.copy(rtkEnabled = it) }; refresh() })
                Spacer(Modifier.width(10.dp))
                Text("RTK token saver", color = NineRouterColors.TextMain)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.cavemanEnabled, { Store.mutate { settings = settings.copy(cavemanEnabled = it) }; refresh() })
                Spacer(Modifier.width(10.dp))
                Text("Caveman prompt", color = NineRouterColors.TextMain, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    s.cavemanLevel,
                    { Store.mutate { settings = settings.copy(cavemanLevel = it) }; refresh() },
                    singleLine = true, modifier = Modifier.width(84.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.ponytailEnabled, { Store.mutate { settings = settings.copy(ponytailEnabled = it) }; refresh() })
                Spacer(Modifier.width(10.dp))
                Text("Ponytail prompt", color = NineRouterColors.TextMain, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    s.ponytailLevel,
                    { Store.mutate { settings = settings.copy(ponytailLevel = it) }; refresh() },
                    singleLine = true, modifier = Modifier.width(84.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.customPromptEnabled, { Store.mutate { settings = settings.copy(customPromptEnabled = it) }; refresh() })
                Spacer(Modifier.width(10.dp))
                Text("Custom system prompt", color = NineRouterColors.TextMain)
            }
            if (s.customPromptEnabled) {
                OutlinedTextField(
                    s.customPrompt,
                    { Store.mutate { settings = settings.copy(customPrompt = it) }; refresh() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                s.fallbackStrategy,
                { Store.mutate { settings = settings.copy(fallbackStrategy = it) }; refresh() },
                label = { Text("Account strategy: fill-first | round-robin") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                s.stickyLimit.toString(),
                { v -> v.toIntOrNull()?.let { Store.mutate { settings = settings.copy(stickyLimit = it) }; refresh() } },
                label = { Text("Sticky round-robin limit") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("API keys", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain, modifier = Modifier.weight(1f))
                TextButton(onClick = { showKeyDialog = true }) { Text("Add", color = NineRouterColors.Brand) }
            }
            Store.state.apiKeys.forEach { k ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.name.ifBlank { k.id }, color = NineRouterColors.TextMain)
                        Text(
                            if (k.allowedModels.isEmpty()) "all models" else "only: ${k.allowedModels.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted,
                        )
                    }
                    Checkbox(k.active, {
                        k.active = it
                        Store.save()
                        refresh()
                    })
                    TextButton(onClick = {
                        Store.state.apiKeys.removeAll { it.id == k.id }
                        Store.save()
                        refresh()
                    }) { Text("Remove") }
                }
            }
            if (createdKey.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("New key: $createdKey", style = MaterialTheme.typography.bodySmall, color = NineRouterColors.Brand)
            }
        }
        Panel {
            Text("Secrets are encrypted with the Android Keystore before being written to disk.", style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Clear usage history") {
                Store.mutate { usage.clear() }
                refresh()
            }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Create API key", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(keyName, { keyName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(keyAllowed, { keyAllowed = it }, label = { Text("Allowed models (comma separated, blank = all)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = Store.newKey()
                    val allowed = keyAllowed.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    Store.state.apiKeys.add(
                        ApiKey(
                            id = UUID.randomUUID().toString(), name = keyName.trim(),
                            key = value, allowedModels = allowed,
                        )
                    )
                    Store.save()
                    createdKey = value
                    keyName = ""; keyAllowed = ""
                    showKeyDialog = false
                    refresh()
                }) { Text("Create", color = NineRouterColors.Brand) }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------

private data class UiMessage(val role: String, val content: String)

@Composable
fun ChatScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { ChatEngine() }
    val parser = remember { Json { ignoreUnknownKeys = true } }
    var model by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<UiMessage>()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        model = Store.state.combos.firstOrNull()?.name
            ?: Store.state.connections.firstOrNull()?.let { c ->
                Catalog.find(c.provider)?.models?.firstOrNull()?.let { "${c.provider}/$it" }
            } ?: "openai/gpt-5-mini"
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SectionHeader("Basic Chat", "Streaming chat through the local gateway")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(model, { model = it }, label = { Text("Model / combo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { m ->
                Panel {
                    Text(m.role.uppercase(), style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Brand)
                    Spacer(Modifier.height(4.dp))
                    Text(m.content, color = NineRouterColors.TextMain)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, label = { Text("Message") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Button(
                enabled = !busy && input.isNotBlank() && model.isNotBlank(),
                onClick = {
                    val prompt = input.trim()
                    input = ""
                    busy = true
                    val history = messages + UiMessage("user", prompt)
                    messages = history
                    scope.launch(Dispatchers.IO) {
                        val request = buildJsonObject {
                            put("model", model)
                            put("messages", JsonArray(history.map { m ->
                                buildJsonObject { put("role", m.role); put("content", m.content) }
                            }))
                            put("stream", true)
                        }
                        when (val out = engine.handle(request.toString(), Formats.OPENAI, Formats.OPENAI)) {
                            is ChatEngine.Outcome.Json -> {
                                val answer = contentOf(parser, out.body)
                                withContext(Dispatchers.Main) {
                                    messages = messages + UiMessage("assistant", answer)
                                    busy = false
                                }
                            }
                            is ChatEngine.Outcome.Stream -> {
                                val answer = StringBuilder()
                                withContext(Dispatchers.Main) {
                                    messages = messages + UiMessage("assistant", "")
                                    busy = false
                                }
                                try {
                                    out.response.body?.source()?.use { src ->
                                        while (true) {
                                            val line = src.readUtf8Line() ?: break
                                            val delta = Translator.sseDelta(Formats.OPENAI, line, model)
                                            if (delta == null || delta.contains("[DONE]")) continue
                                            val piece = deltaOf(parser, delta.removePrefix("data:").trim())
                                            if (piece.isNotBlank()) {
                                                answer.append(piece)
                                                val snapshot = answer.toString()
                                                withContext(Dispatchers.Main) {
                                                    messages = messages.dropLast(1) + UiMessage("assistant", snapshot)
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    out.response.close()
                                }
                            }
                            is ChatEngine.Outcome.Error -> withContext(Dispatchers.Main) {
                                messages = messages + UiMessage("assistant", "[error ${out.status}] ${out.message}")
                                busy = false
                            }
                        }
                    }
                },
            ) { Text(if (busy) "…" else "Send") }
        }
    }
}

private fun contentOf(parser: Json, raw: String): String {
    val obj = try { parser.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
    val choice = (obj?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject
    val content = message?.get("content") as? JsonPrimitive
    return content?.content ?: raw.take(500)
}

private fun deltaOf(parser: Json, data: String): String {
    val obj = try { parser.parseToJsonElement(data) as? JsonObject } catch (_: Exception) { null }
    val choice = (obj?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
    val delta = choice?.get("delta") as? JsonObject
    val content = delta?.get("content") as? JsonPrimitive
    return content?.content ?: ""
}

