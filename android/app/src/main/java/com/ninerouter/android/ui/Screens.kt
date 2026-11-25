package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.ninerouter.android.GatewayService
import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.Translator
import com.ninerouter.android.update.UpdateManager
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Combo
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
    val scope = rememberCoroutineScope()
    val running by GatewayState.running.collectAsState()
    val gatewayError by GatewayState.error.collectAsState()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader("Endpoint", "Local OpenAI-compatible API")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (running) NineRouterColors.Success else NineRouterColors.TextSubtle,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (running) "Gateway running" else "Gateway stopped",
                    style = MaterialTheme.typography.titleSmall,
                    color = NineRouterColors.TextMain,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "port ${GatewayState.PORT}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
            }
            Spacer(Modifier.height(10.dp))
            StatusRow("Dashboard", GatewayState.BASE_URL)
            Spacer(Modifier.height(6.dp))
            StatusRow("OpenAI base URL", "${GatewayState.BASE_URL}/v1")
            if (gatewayError.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(gatewayError, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Danger)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryButton(if (running) "Shutdown" else "Start gateway", onClick = {
                    if (running) GatewayService.stop(context) else GatewayService.start(context)
                    refresh()
                })
                SecondaryButton("Open dashboard") {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GatewayState.BASE_URL))
                        )
                    }.onFailure {
                        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(if (testing) "Testing…" else "Test connection") {
                    if (testing) return@SecondaryButton
                    testing = true
                    testResult = ""
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val conn = java.net.URL("${GatewayState.BASE_URL}/api/health")
                                    .openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 3000
                                conn.readTimeout = 3000
                                val code = conn.responseCode
                                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                                conn.disconnect()
                                if (code == 200) {
                                    "OK · $body"
                                } else {
                                    "HTTP $code · ${body.take(160)}"
                                }
                            }.getOrElse { it.message ?: it.javaClass.simpleName }
                        }
                        testing = false
                        testResult = result
                    }
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(GatewayState.BASE_URL))
                    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy URL") }
            }
            if (testResult.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    testResult,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (testResult.startsWith("OK")) NineRouterColors.Success else NineRouterColors.Warning,
                )
            }
        }
        Panel {
            Text("Browser access", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
            Spacer(Modifier.height(6.dp))
            Text(
                "• On this phone open http://127.0.0.1:20128 — include http:// and the port.\n" +
                    "• The gateway must show “running” above. Use Open dashboard if typing fails.\n" +
                    "• From a computer run adb reverse tcp:20128 tcp:20128, then open http://localhost:20128.\n" +
                    "• The server is loopback-only by design; it is never exposed to the local network.",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If the page still does not load, restart the gateway and test again.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = {
                    GatewayService.restart(context)
                    Toast.makeText(context, "Restarting gateway…", Toast.LENGTH_SHORT).show()
                    refresh()
                }) { Text("Restart gateway", color = NineRouterColors.Brand) }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }
                }) { Text("Battery settings") }
            }
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
fun UpdatePanel(state: UpdateManager.State) {
    val context = LocalContext.current
    val current = remember { UpdateManager.currentVersion(context) }

    fun openReleasePage() {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.RELEASES_PAGE)))
        }
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "App updates",
                style = MaterialTheme.typography.titleMedium,
                color = NineRouterColors.TextMain,
                modifier = Modifier.weight(1f),
            )
            Text(
                "v$current",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))

        when (state) {
            is UpdateManager.State.Idle -> {
                Text(
                    "Checks the GitHub release channel for a newer APK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Check for updates") { UpdateManager.check(context) }
            }

            is UpdateManager.State.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Checking GitHub…", style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
            }

            is UpdateManager.State.UpToDate -> {
                Text(
                    "You're on the latest build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.Success,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { UpdateManager.check(context) }) {
                    Text("Check again", color = NineRouterColors.Brand)
                }
            }

            is UpdateManager.State.Available -> {
                Text(
                    "${state.release.version} is available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineRouterColors.BrandOrange,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "What's new",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
                Text(
                    state.release.notes.trim().ifBlank { "No release notes." }.take(900),
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                    maxLines = 10,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Download & install", onClick = { UpdateManager.download(context) })
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = { openReleasePage() }) {
                    Text("Full release notes on GitHub", color = NineRouterColors.TextMuted)
                }
            }

            is UpdateManager.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Downloading ${state.release.version} · ${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            }

            is UpdateManager.State.Ready -> {
                Text(
                    "${state.release.version} downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.Success,
                )
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Install update", onClick = {
                    val error = UpdateManager.install(context, state.file)
                    if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                })
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android will ask you to confirm the install. The app stays signed with the " +
                        "same debug key, so the update replaces this build in place.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
            }

            is UpdateManager.State.Failed -> {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.Danger,
                )
                Spacer(Modifier.height(6.dp))
                SecondaryButton("Try again") { UpdateManager.check(context) }
            }
        }

        if (state !is UpdateManager.State.Available && state !is UpdateManager.State.Downloading) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { openReleasePage() }) {
                Text("Open releases on GitHub", color = NineRouterColors.TextMuted)
            }
        }

        val lastChecked = remember(state) { UpdateManager.lastChecked(context) }
        if (lastChecked > 0) {
            Text(
                "Last checked ${relativeAgo(lastChecked)}",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
            )
        }
    }
}

private fun relativeAgo(timestamp: Long): String {
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} h ago"
        else -> "${diff / 86_400_000} d ago"
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        messages = messages.dropLast(1) +
                                            UiMessage("assistant", "[error] ${e.message ?: "stream failed"}")
                                    }
                                } finally {
                                    out.response.close()
                                    withContext(Dispatchers.Main) { busy = false }
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

