package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ninerouter.android.GatewayService
import com.ninerouter.android.R
import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.Translator
import com.ninerouter.android.update.UpdateManager
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Combo
import com.ninerouter.android.data.Store
import com.ninerouter.android.server.GatewayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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
    var revealKey by remember { mutableStateOf(false) }

    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (running) NineRouterColors.Success else NineRouterColors.TextSubtle, size = 9.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Gateway running" else "Gateway stopped",
                        style = MaterialTheme.typography.titleMedium,
                        color = NineRouterColors.TextMain,
                    )
                    Text(
                        if (running) "Serving OpenAI-compatible traffic on loopback"
                        else "Start it to serve requests",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
                Pill(":${GatewayState.PORT}")
            }
            Spacer(Modifier.height(NineRouterSpacing.md))
            CopyRow("OpenAI base URL", "${GatewayState.BASE_URL}/v1") { copy("Base URL", "${GatewayState.BASE_URL}/v1") }
            Spacer(Modifier.height(6.dp))
            CopyRow("Dashboard", GatewayState.BASE_URL) { copy("URL", GatewayState.BASE_URL) }
            if (gatewayError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                NoticeBanner(gatewayError, NineRouterColors.Danger)
            }
            Spacer(Modifier.height(NineRouterSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
                PrimaryButton(
                    if (running) "Shutdown" else "Start gateway",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (running) GatewayService.stop(context) else GatewayService.start(context)
                        refresh()
                    },
                )
                SecondaryButton("Open dashboard", modifier = Modifier.weight(1f)) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GatewayState.BASE_URL))
                        )
                    }.onFailure {
                        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(NineRouterSpacing.sm))
            SecondaryButton(
                if (testing) "Testing…" else "Test connection",
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                            if (code == 200) "OK · $body" else "HTTP $code · ${body.take(160)}"
                        }.getOrElse { it.message ?: it.javaClass.simpleName }
                    }
                    testing = false
                    testResult = result
                }
            }
            if (testResult.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    testResult,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (testResult.startsWith("OK")) NineRouterColors.Success else NineRouterColors.Warning,
                )
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Browser access", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                Spacer(Modifier.weight(1f))
                Pill("loopback only")
            }
            Spacer(Modifier.height(NineRouterSpacing.sm))
            Bullet("Open http://127.0.0.1:${GatewayState.PORT} on this phone — include http:// and the port.")
            Bullet("Make sure the gateway shows running above, then use Open dashboard if typing fails.")
            Bullet("From a computer run adb reverse tcp:${GatewayState.PORT} tcp:${GatewayState.PORT}, then open http://localhost:${GatewayState.PORT}.")
            Bullet("The server binds to loopback by design and is never exposed to the local network.")
            Spacer(Modifier.height(NineRouterSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
                SecondaryButton("Restart gateway", modifier = Modifier.weight(1f)) {
                    GatewayService.restart(context)
                    Toast.makeText(context, "Restarting gateway…", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                SecondaryButton("Battery settings", modifier = Modifier.weight(1f)) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }
                }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API key", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                Spacer(Modifier.weight(1f))
                Pill(if (revealKey) "visible" else "hidden")
            }
            Spacer(Modifier.height(NineRouterSpacing.sm))
            Surface(
                color = NineRouterColors.SurfaceSunken,
                shape = NineRouterShapes.small,
                border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(
                        if (revealKey) Store.state.settings.apiKey else "••••••••••••••••••••••",
                        color = NineRouterColors.Brand,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { revealKey = !revealKey }) {
                        Text(if (revealKey) "Hide" else "Show", color = NineRouterColors.TextMuted)
                    }
                    IconButton(
                        onClick = { copy("API key", Store.state.settings.apiKey) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(CopyIcon, "Copy API key", tint = NineRouterColors.TextSubtle, modifier = Modifier.size(15.dp))
                    }
                }
            }
            Spacer(Modifier.height(NineRouterSpacing.sm))
            SecondaryButton("Regenerate key", modifier = Modifier.fillMaxWidth()) {
                Store.mutate { settings = settings.copy(apiKey = Store.newKey()) }
                refresh()
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Client configuration", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    copy(
                        "Config",
                        "Base URL: ${GatewayState.BASE_URL}/v1\nAPI key: ${Store.state.settings.apiKey}\nModel: openai/gpt-5-mini",
                    )
                }) { Text("Copy", color = NineRouterColors.TextMuted) }
            }
            Spacer(Modifier.height(NineRouterSpacing.sm))
            Surface(
                color = NineRouterColors.SurfaceSunken,
                shape = NineRouterShapes.small,
                border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
            ) {
                Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MonoText("base_url  ${GatewayState.BASE_URL}/v1")
                    MonoText("api_key   ${Store.state.settings.apiKey.take(8)}…")
                    MonoText("model     openai/gpt-5-mini")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Pair these with any OpenAI-compatible client after pointing it at the gateway.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
            )
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
            Spacer(Modifier.height(1.dp))
            MonoText(value)
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(CopyIcon, contentDescription = "Copy $label", tint = NineRouterColors.TextSubtle, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(Modifier.padding(top = 7.dp).size(4.dp).background(NineRouterColors.BrandOrange, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted, modifier = Modifier.weight(1f))
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
    val strategies = listOf("fallback", "round-robin", "smart")

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            SectionHeader(
                "Combos",
                "Try models in order with automatic failover",
                action = { Pill("${Store.state.combos.size}") },
            )
            Spacer(Modifier.height(NineRouterSpacing.sm))
            PrimaryButton("Add combo", modifier = Modifier.fillMaxWidth(), onClick = { showDialog = true })
        }
        if (Store.state.combos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                EmptyState(
                    title = "No combos yet",
                    body = "Group model ids into a combo so requests fall back automatically when a provider is unavailable.",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(Store.state.combos, key = { it.name }, contentType = { "combo" }) { combo ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                combo.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = NineRouterColors.TextMain,
                                modifier = Modifier.weight(1f),
                            )
                            Pill(
                                combo.strategy,
                                color = NineRouterColors.BrandOrange,
                                background = NineRouterColors.BrandOrange.copy(alpha = 0.14f),
                            )
                        }
                        Spacer(Modifier.height(NineRouterSpacing.sm))
                        combo.models.forEachIndexed { index, model ->
                            if (index > 0) DividerLine(Modifier.padding(vertical = 2.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Pill("${index + 1}")
                                Spacer(Modifier.width(8.dp))
                                MonoText(model, modifier = Modifier.weight(1f))
                            }
                        }
                        if (combo.routerModel.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            MonoText("router · ${combo.routerModel}", color = NineRouterColors.TextSubtle)
                        }
                        Spacer(Modifier.height(NineRouterSpacing.xs))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                Store.state.combos.removeAll { it.name == combo.name }
                                Store.save()
                                refresh()
                            }) { Text("Remove", color = NineRouterColors.Danger) }
                        }
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("Combo name") },
                        singleLine = true,
                        shape = NineRouterShapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        models,
                        { models = it },
                        label = { Text("Models, comma separated") },
                        shape = NineRouterShapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column {
                        Text("Strategy", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            strategies.forEach { option ->
                                ChoiceChip(option, strategy == option) { strategy = option }
                            }
                        }
                    }
                    if (strategy == "smart") {
                        OutlinedTextField(
                            routerModel,
                            { routerModel = it },
                            label = { Text("Router model") },
                            singleLine = true,
                            shape = NineRouterShapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = models.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (name.isNotBlank() && list.isNotEmpty()) {
                        Store.state.combos.add(Combo(name.trim(), list, strategy, routerModel.trim()))
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

private data class UiMessage(val role: String, val content: String, val id: Long = System.nanoTime())

@Composable
fun ChatScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { ChatEngine() }
    val parser = remember { Json { ignoreUnknownKeys = true } }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val running by GatewayState.running.collectAsState()
    var model by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<UiMessage>()) }
    var busy by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var dotFrame by remember { mutableStateOf(0) }
    var generation by remember { mutableStateOf(0) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val activeResponse = remember { mutableStateOf<okhttp3.Response?>(null) }
    val listState = rememberLazyListState()
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision

    val options = remember(revision) {
        buildList {
            Store.state.combos.forEach { add(it.name) }
            Store.state.connections.forEach { c ->
                Catalog.find(c.provider)?.models?.forEach { add("${c.provider}/$it") }
            }
            Store.state.nodes.forEach { n -> n.models.forEach { add("${n.prefix}/$it") } }
        }.distinct()
    }

    LaunchedEffect(Unit) {
        model = Store.state.combos.firstOrNull()?.name
            ?: options.firstOrNull()
            ?: "openai/gpt-5-mini"
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    LaunchedEffect(busy) {
        while (busy) {
            delay(420)
            dotFrame = (dotFrame + 1) % 3
        }
    }

    fun updateAssistant(id: Long, value: String) {
        messages = messages.map { if (it.id == id) it.copy(content = value) else it }
    }

    fun stop() {
        generation += 1
        runCatching { activeResponse.value?.close() }
        activeResponse.value = null
        streamJob?.cancel()
        streamJob = null
        busy = false
        messages = messages.filterNot { it.role == "assistant" && it.content.isBlank() }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || model.isBlank() || busy) return
        input = ""
        busy = true
        generation += 1
        val requestId = generation
        val history = messages + UiMessage("user", text)
        val placeholder = UiMessage("assistant", "")
        messages = history + placeholder
        streamJob = scope.launch(Dispatchers.IO) {
            val request = buildJsonObject {
                put("model", model)
                put("messages", JsonArray(history.map { m ->
                    buildJsonObject { put("role", m.role); put("content", m.content) }
                }))
                put("stream", true)
            }
            try {
                when (val out = engine.handle(request.toString(), Formats.OPENAI, Formats.OPENAI)) {
                    is ChatEngine.Outcome.Json -> {
                        val answer = contentOf(parser, out.body).ifBlank { "(empty response)" }
                        withContext(Dispatchers.Main) {
                            if (generation == requestId) updateAssistant(placeholder.id, answer)
                        }
                    }
                    is ChatEngine.Outcome.Stream -> {
                        activeResponse.value = out.response
                        val answer = StringBuilder()
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
                                            if (generation == requestId) updateAssistant(placeholder.id, snapshot)
                                        }
                                    }
                                }
                            }
                        } finally {
                            activeResponse.value = null
                            out.response.close()
                        }
                        if (answer.isBlank()) {
                            withContext(Dispatchers.Main) {
                                if (generation == requestId) updateAssistant(placeholder.id, "(no content returned)")
                            }
                        }
                    }
                    is ChatEngine.Outcome.Error -> withContext(Dispatchers.Main) {
                        if (generation == requestId) {
                            updateAssistant(placeholder.id, "[error ${out.status}] ${out.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (generation == requestId) {
                        updateAssistant(placeholder.id, "[error] ${e.message ?: "request failed"}")
                    }
                }
            } finally {
                runCatching {
                    withContext(Dispatchers.Main) {
                        if (generation == requestId) {
                            busy = false
                            streamJob = null
                            messages = messages.filterNot { it.role == "assistant" && it.content.isBlank() }
                        }
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { showPicker = true },
                color = NineRouterColors.Surface2,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, NineRouterColors.Border),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(if (running) NineRouterColors.Success else NineRouterColors.TextSubtle)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "MODEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextSubtle,
                        )
                        Text(
                            model.ifBlank { "Select a model" },
                            style = MaterialTheme.typography.bodySmall,
                            color = NineRouterColors.TextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Choose model",
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = {
                    stop()
                    messages = emptyList()
                    input = ""
                },
                color = NineRouterColors.Surface2,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, NineRouterColors.Border),
                modifier = Modifier.size(44.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.brand_mark),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "How can I help?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = NineRouterColors.TextMain,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Chat streams through your local 9Router gateway. Pick a model above, then send a prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    ChatIdeas { prompt -> input = prompt }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }, contentType = { it.role }) { m ->
                    MessageBubble(
                        message = m,
                        thinking = busy && m.role == "assistant" && m.content.isBlank(),
                        dotFrame = dotFrame,
                        onCopy = {
                            clipboard.setText(AnnotatedString(m.content))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }

        Surface(
            color = NineRouterColors.Surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, NineRouterColors.Border),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NineRouterColors.TextMain),
                    cursorBrush = SolidColor(NineRouterColors.BrandOrange),
                    maxLines = 5,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (input.isBlank()) {
                                Text(
                                    "Message ${model.substringAfter('/').ifBlank { "…" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NineRouterColors.TextSubtle,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank() && model.isNotBlank()
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            when {
                                busy -> NineRouterColors.Danger
                                canSend -> NineRouterColors.BrandOrange
                                else -> NineRouterColors.Surface3
                            },
                            CircleShape,
                        )
                        .clickable(enabled = busy || canSend) { if (busy) stop() else send() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        Box(Modifier.size(11.dp).background(NineRouterColors.Bg, RoundedCornerShape(3.dp)))
                    } else {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (canSend) NineRouterColors.Bg else NineRouterColors.TextSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            options = options,
            selected = model,
            onPick = { model = it; showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

private data class ChatIdea(val title: String, val prompt: String)

@Composable
private fun ChatIdeas(onPick: (String) -> Unit) {
    val ideas = remember {
        listOf(
            ChatIdea("Summarize", "Summarize the following in five crisp bullet points:\n\n"),
            ChatIdea("Explain code", "Explain what this code does and flag any bugs:\n\n```\n\n```"),
            ChatIdea("Draft a reply", "Draft a short, friendly reply to this message:\n\n"),
            ChatIdea("Debug an error", "Help me find the root cause of this error:\n\n"),
        )
    }
    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ideas.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { idea ->
                    Surface(
                        onClick = { onPick(idea.prompt) },
                        color = NineRouterColors.Surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, NineRouterColors.Border),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                            Text(
                                idea.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = NineRouterColors.TextMain,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                idea.prompt.lineSequence().first(),
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: UiMessage,
    thinking: Boolean,
    dotFrame: Int,
    onCopy: () -> Unit,
) {
    val isUser = message.role == "user"
    val accent = if (isUser) NineRouterColors.BrandOrange else NineRouterColors.Success
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) NineRouterColors.BrandOrange.copy(alpha = 0.14f) else NineRouterColors.Surface,
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(accent, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isUser) "YOU" else "9ROUTER",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
                Spacer(Modifier.height(5.dp))
                if (thinking) {
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { i ->
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(
                                        NineRouterColors.TextMuted.copy(alpha = if (dotFrame == i) 1f else 0.35f),
                                        CircleShape,
                                    )
                            )
                            if (i < 2) Spacer(Modifier.width(4.dp))
                        }
                    }
                } else {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NineRouterColors.TextMain,
                    )
                }
                if (!isUser && !thinking && message.content.isNotBlank()) {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.align(Alignment.End).size(26.dp),
                    ) {
                        Icon(
                            CopyIcon,
                            contentDescription = "Copy message",
                            tint = NineRouterColors.TextSubtle,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) {
        if (query.isBlank()) options else options.filter { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Choose a model", color = NineRouterColors.TextMain) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search models and combos") },
                    singleLine = true,
                    shape = NineRouterShapes.small,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = NineRouterColors.TextSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (filtered.isEmpty()) {
                    EmptyState(
                        title = "Nothing to chat with",
                        body = "Add a connection or combo on the Providers tab first.",
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it }) { option ->
                            val isSelected = option == selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(option) }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusDot(
                                    if (isSelected) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                                    size = 6.dp,
                                )
                                Spacer(Modifier.width(9.dp))
                                MonoText(
                                    option,
                                    color = if (isSelected) NineRouterColors.TextMain else NineRouterColors.TextMuted,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Text(
                                        "current",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NineRouterColors.BrandOrange,
                                    )
                                }
                            }
                            DividerLine()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = NineRouterColors.BrandOrange) }
        },
    )
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

