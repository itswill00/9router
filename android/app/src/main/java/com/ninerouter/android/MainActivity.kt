package com.ninerouter.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.OAuthFlow
import com.ninerouter.android.core.Translator
import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.Catalog
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        handleOAuthCallback()
        setContent { RootScreen() }
    }

    private fun handleOAuthCallback() {
        val uri = intent.data ?: return
        if (uri.scheme != "ninerouter" || uri.host != "oauth") return
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val providerId = OAuthFlow.providerFor(state) ?: return
        val def = Catalog.find(providerId) ?: return
        val tokens = OAuthFlow.exchangeCode(def, code, state)
        if (tokens != null) {
            Store.state.connections.add(
                Connection(
                    id = UUID.randomUUID().toString(),
                    provider = def.id,
                    name = def.name,
                    authType = "oauth",
                    accessToken = tokens.access,
                    refreshToken = tokens.refresh,
                    expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000L,
                )
            )
            Store.save()
            Toast.makeText(this, "Connected ${def.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "OAuth exchange failed for ${def.name}", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen() {
    var tab by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val running by GatewayState.running.collectAsState()
    val refresh = { tick += 1 }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text("9Router · Android") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Chat", "Overview", "Providers", "Combos", "Usage", "Settings").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> ChatTab()
                1 -> OverviewTab(running, refresh)
                2 -> ProvidersTab(tick, refresh)
                3 -> CombosTab(tick, refresh)
                4 -> UsageTab(tick, refresh)
                5 -> SettingsTab(tick, refresh)
            }
        }
    }
}

private data class UiMessage(val role: String, val content: String)

@Composable
fun ChatTab() {
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            model, { model = it },
            label = { Text("Model / combo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.role, style = MaterialTheme.typography.labelSmall)
                        Text(m.content)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && input.isNotBlank() && model.isNotBlank(), onClick = {
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
            }) { Text(if (busy) "…" else "Send") }
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

@Composable
fun OverviewTab(running: Boolean, refresh: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state = Store.state
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Gateway", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(if (running) "● Running" else "○ Stopped")
                Text("Endpoint: ${GatewayState.BASE_URL}/v1")
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    val intent = Intent(context, GatewayService::class.java)
                    if (running) context.stopService(intent) else context.startForegroundService(intent)
                    refresh()
                }) {
                    Text(if (running) "Stop gateway" else "Start gateway")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("API key", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(state.settings.apiKey)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(state.settings.apiKey))
                        Toast.makeText(context, "API key copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        Store.mutate { settings = settings.copy(apiKey = Store.newKey()) }
                        refresh()
                    }) { Text("Regenerate") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("State", style = MaterialTheme.typography.titleMedium)
                Text("${state.connections.size} connections · ${state.combos.size} combos")
                Text("${state.usage.size} usage records kept")
                Text("Provider definitions: ${Catalog.providers().size}")
            }
        }
    }
}

@Composable
fun ProvidersTab(tick: Int, refresh: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var oauth by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var deviceChallenge by remember { mutableStateOf<OAuthFlow.DeviceChallenge?>(null) }
    var deviceProvider by remember { mutableStateOf<ProviderDef?>(null) }
    var deviceStatus by remember { mutableStateOf("") }
    var showNodeDialog by remember { mutableStateOf(false) }
    var nodePrefix by remember { mutableStateOf("") }
    var nodeName by remember { mutableStateOf("") }
    var nodeUrl by remember { mutableStateOf("") }
    var nodeKey by remember { mutableStateOf("") }
    val oauthProviders = remember {
        Catalog.providers().filter { it.authorizeUrl.isNotBlank() || it.deviceCodeUrl.isNotBlank() }.take(10)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Connections", style = MaterialTheme.typography.titleMedium)
            Row {
                OutlinedButton(onClick = { showNodeDialog = true }) { Text("Add node") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { showDialog = true }) { Text("Add") }
            }
        }
        Text("Popular: openai · anthropic · claude · gemini · openrouter · deepseek", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("OAuth login", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (oauthProviders.isEmpty()) {
                    Text("No login-capable providers in the exported registry.", style = MaterialTheme.typography.bodySmall)
                }
                oauthProviders.forEach { def ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(def.name, style = MaterialTheme.typography.bodyMedium)
                            Text(def.id, style = MaterialTheme.typography.bodySmall)
                        }
                        if (def.authorizeUrl.isNotBlank()) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OAuthFlow.authorizeUrl(def))))
                            }) { Text("Browser") }
                        }
                        if (def.deviceCodeUrl.isNotBlank()) {
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val challenge = OAuthFlow.startDevice(def)
                                    withContext(Dispatchers.Main) {
                                        deviceProvider = def
                                        deviceChallenge = challenge
                                        deviceStatus = if (challenge != null) "Waiting for authorization…" else "Device flow unavailable"
                                    }
                                    if (challenge != null) {
                                        while (System.currentTimeMillis() < challenge.expiresAt) {
                                            val tokens = OAuthFlow.pollDevice(def, challenge)
                                            if (tokens != null) {
                                                withContext(Dispatchers.Main) {
                                                    Store.state.connections.add(
                                                        Connection(
                                                            id = UUID.randomUUID().toString(),
                                                            provider = def.id,
                                                            name = def.name,
                                                            authType = "oauth",
                                                            accessToken = tokens.access,
                                                            refreshToken = tokens.refresh,
                                                            expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000L,
                                                        )
                                                    )
                                                    Store.save()
                                                    deviceChallenge = null
                                                    deviceStatus = ""
                                                    refresh()
                                                }
                                                break
                                            }
                                            delay(challenge.interval.coerceAtLeast(2) * 1000L)
                                        }
                                    }
                                }
                            }) { Text("Device") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (Store.state.nodes.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Custom nodes", style = MaterialTheme.typography.titleSmall)
                    Store.state.nodes.forEach { node ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(node.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${node.prefix}/… → ${node.baseUrl}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                Store.state.nodes.removeAll { it.id == node.id }
                                Store.save()
                                refresh()
                            }) { Text("Remove") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Store.state.connections, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { c.provider }, style = MaterialTheme.typography.bodyLarge)
                            Text("${c.provider} · ${c.authType} · ${if (c.enabled) "active" else "disabled"}")
                        }
                        TextButton(onClick = {
                            c.enabled = !c.enabled
                            Store.save()
                            refresh()
                        }) { Text(if (c.enabled) "Disable" else "Enable") }
                        TextButton(onClick = {
                            Store.state.connections.removeAll { it.id == c.id }
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
            title = { Text("Add connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(provider, { provider = it }, label = { Text("Provider id or alias") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Label (optional)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(oauth, { oauth = it })
                        Text(if (oauth) "OAuth token" else "API key")
                    }
                    OutlinedTextField(
                        secret, { secret = it },
                        label = { Text(if (oauth) "Access token" else "API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (oauth) {
                        OutlinedTextField(
                            refreshToken, { refreshToken = it },
                            label = { Text("Refresh token (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (provider.isNotBlank() && secret.isNotBlank()) {
                        Store.state.connections.add(
                            Connection(
                                id = UUID.randomUUID().toString(),
                                provider = provider.trim(),
                                name = name.trim(),
                                authType = if (oauth) "oauth" else "apikey",
                                apiKey = if (oauth) "" else secret.trim(),
                                accessToken = if (oauth) secret.trim() else "",
                                refreshToken = refreshToken.trim(),
                            )
                        )
                        Store.save()
                        showDialog = false
                        provider = ""; name = ""; secret = ""; refreshToken = ""
                        refresh()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }

    if (showNodeDialog) {
        AlertDialog(
            onDismissRequest = { showNodeDialog = false },
            title = { Text("Add compatible node") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nodePrefix, { nodePrefix = it }, label = { Text("Prefix (e.g. local)") }, singleLine = true)
                    OutlinedTextField(nodeName, { nodeName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(nodeUrl, { nodeUrl = it }, label = { Text("Base URL (chat/completions)") }, singleLine = true)
                    OutlinedTextField(nodeKey, { nodeKey = it }, label = { Text("API key") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (nodePrefix.isNotBlank() && nodeUrl.isNotBlank()) {
                        Store.state.nodes.add(
                            ProviderNode(
                                id = "node-" + UUID.randomUUID().toString().take(8),
                                prefix = nodePrefix.trim(),
                                name = nodeName.ifBlank { nodePrefix.trim() },
                                baseUrl = nodeUrl.trim(),
                                apiKey = nodeKey.trim(),
                            )
                        )
                        Store.save()
                        showNodeDialog = false
                        nodePrefix = ""; nodeName = ""; nodeUrl = ""; nodeKey = ""
                        refresh()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showNodeDialog = false }) { Text("Cancel") } },
        )
    }

    deviceChallenge?.let { challenge ->
        AlertDialog(
            onDismissRequest = { deviceChallenge = null },
            title = { Text("Device login · ${deviceProvider?.name ?: ""}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Code: ${challenge.userCode}", style = MaterialTheme.typography.titleLarge)
                    Text("Open ${challenge.verificationUri} and enter the code.")
                    Text(deviceStatus, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (challenge.verificationUri.isNotBlank()) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(challenge.verificationUri)))
                    }
                }) { Text("Open link") }
            },
            dismissButton = { TextButton(onClick = { deviceChallenge = null }) { Text("Close") } },
        )
    }
}

@Composable
fun CombosTab(tick: Int, refresh: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("fallback") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Combos", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { showDialog = true }) { Text("Add") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Store.state.combos, key = { it.name }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(c.models.joinToString(" → "))
                        Text("strategy: ${c.strategy}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            Store.state.combos.removeAll { it.name == c.name }
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
            title = { Text("Add combo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Combo name") }, singleLine = true)
                    OutlinedTextField(models, { models = it }, label = { Text("Models, comma separated") })
                    OutlinedTextField(strategy, { strategy = it }, label = { Text("Strategy: fallback | round-robin") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = models.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (name.isNotBlank() && list.isNotEmpty()) {
                        Store.state.combos.add(com.ninerouter.android.data.Combo(name.trim(), list, strategy.trim()))
                        Store.save()
                        showDialog = false
                        name = ""; models = ""; strategy = "fallback"
                        refresh()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun UsageTab(tick: Int, refresh: () -> Unit) {
    val usage = Store.state.usage
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Usage", style = MaterialTheme.typography.titleMedium)
                Text("${usage.size} requests · ${usage.sumOf { it.prompt }} prompt tokens · ${usage.sumOf { it.completion }} completion tokens")
                Text("${usage.count { !it.ok }} failed", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(usage.takeLast(100).asReversed()) { u ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${u.provider} · ${u.model}")
                        Text("${java.util.Date(u.ts)} · ${u.prompt}+${u.completion} tokens · ${if (u.ok) "ok" else "failed"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(tick: Int, refresh: () -> Unit) {
    val s = Store.state.settings
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyName by remember { mutableStateOf("") }
    var keyAllowed by remember { mutableStateOf("") }
    var createdKey by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.requireApiKey, { Store.mutate { settings = settings.copy(requireApiKey = it) }; refresh() })
            Text("Require API key")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.rtkEnabled, { Store.mutate { settings = settings.copy(rtkEnabled = it) }; refresh() })
            Text("RTK token saver")
        }
        OutlinedTextField(
            s.fallbackStrategy,
            { Store.mutate { settings = settings.copy(fallbackStrategy = it) }; refresh() },
            label = { Text("Account strategy: fill-first | round-robin") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            s.stickyLimit.toString(),
            { v -> v.toIntOrNull()?.let { Store.mutate { settings = settings.copy(stickyLimit = it) }; refresh() } },
            label = { Text("Sticky round-robin limit") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("API keys", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { showKeyDialog = true }) { Text("Add") }
        }
        Store.state.apiKeys.forEach { k ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.name.ifBlank { k.id })
                        Text(
                            if (k.allowedModels.isEmpty()) "all models" else "only: ${k.allowedModels.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        k.active = !k.active
                        Store.save()
                        refresh()
                    }) { Text(if (k.active) "Disable" else "Enable") }
                    TextButton(onClick = {
                        Store.state.apiKeys.removeAll { it.id == k.id }
                        Store.save()
                        refresh()
                    }) { Text("Remove") }
                }
            }
        }
        if (createdKey.isNotBlank()) {
            Text("New key: $createdKey", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("Secrets are AES-GCM encrypted with the Android Keystore before touching disk.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            Store.mutate { usage.clear() }
            refresh()
        }) { Text("Clear usage history") }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Create API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(keyName, { keyName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        keyAllowed, { keyAllowed = it },
                        label = { Text("Allowed models (comma separated, blank = all)") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = Store.newKey()
                    val allowed = keyAllowed.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    Store.state.apiKeys.add(
                        ApiKey(
                            id = UUID.randomUUID().toString(),
                            name = keyName.trim(),
                            key = value,
                            allowedModels = allowed,
                        )
                    )
                    Store.save()
                    createdKey = value
                    keyName = ""; keyAllowed = ""
                    showKeyDialog = false
                    refresh()
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") } },
        )
    }
}
