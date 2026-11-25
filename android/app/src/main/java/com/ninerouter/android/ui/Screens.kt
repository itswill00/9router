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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val running by GatewayState.running.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader("Endpoint", "API endpoint configuration")
        Panel {
            StatusRow("Base URL", "${GatewayState.BASE_URL}/v1")
            Spacer(Modifier.height(6.dp))
            StatusRow("Dashboard", GatewayState.BASE_URL)
            Spacer(Modifier.height(6.dp))
            StatusRow("Status", if (running) "Running" else "Stopped")
            Spacer(Modifier.height(12.dp))
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

@Composable
fun ProvidersScreen(refresh: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showNodeDialog by remember { mutableStateOf(false) }
    var deviceChallenge by remember { mutableStateOf<OAuthFlow.DeviceChallenge?>(null) }
    var deviceProvider by remember { mutableStateOf<ProviderDef?>(null) }
    var deviceStatus by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var oauth by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    var nodePrefix by remember { mutableStateOf("") }
    var nodeName by remember { mutableStateOf("") }
    var nodeUrl by remember { mutableStateOf("") }
    var nodeKey by remember { mutableStateOf("") }

    val providers = Catalog.providers()
    val filtered = providers.filter {
        query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)
    }
    val groups = listOf(
        "OAuth Providers" to filtered.filter { it.category.equals("oauth", true) },
        "Free Providers" to filtered.filter { it.category.contains("free", true) },
        "API Key Providers" to filtered.filter { it.category.equals("apikey", true) },
        "API Key Compatible Providers" to filtered.filter {
            !it.category.equals("oauth", true) && !it.category.contains("free", true) && !it.category.equals("apikey", true)
        },
    )

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SectionHeader("Providers", "Manage your AI provider connections")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                query, { query = it },
                label = { Text("Search providers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                PrimaryButton("Add connection", onClick = { showAdd = true })
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Add node") { showNodeDialog = true }
            }
            val loginProviders = providers.filter {
                it.deviceCodeUrl.isNotBlank() || it.authorizeUrl.isNotBlank()
            }.take(4)
            if (loginProviders.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Panel {
                    Text("OAuth login", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                    Spacer(Modifier.height(4.dp))
                    loginProviders.forEach { def ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ProviderIcon(def.id, def.name, 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(def.name, modifier = Modifier.weight(1f), color = NineRouterColors.TextMain)
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
                                            deviceStatus = if (challenge != null) "Waiting for authorization..." else "Device flow unavailable"
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
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { (heading, list) ->
                if (list.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(4.dp))
                            SectionHeader(
                                heading,
                                action = {
                                    TextButton(onClick = {
                                        Toast.makeText(context, "Connections are tested on first use.", Toast.LENGTH_SHORT).show()
                                    }) { Text("Test All") }
                                },
                            )
                        }
                    }
                    items(list, key = { "p-${it.id}" }) { p ->
                        val count = Store.state.connections.count { c -> c.provider == p.id }
                        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProviderIcon(p.id, p.name)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.bodyLarge, color = NineRouterColors.TextMain)
                                        Spacer(Modifier.height(3.dp))
                                        ConnectedBadge(count)
                                    }
                                    TextButton(onClick = {
                                        provider = p.id
                                        label = p.name
                                        showAdd = true
                                    }) { Text("Connect") }
                                }
                            }
                        }
                    }
                }
            }
            if (Store.state.nodes.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Compatible nodes", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                    }
                }
                items(Store.state.nodes, key = { it.id }) { node ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProviderIcon("openai", node.name)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(node.name, color = NineRouterColors.TextMain)
                                    Text("${node.prefix}/…", style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
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
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Your connections", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                }
            }
            items(Store.state.connections, key = { it.id }) { c ->
                androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProviderIcon(c.provider, c.name.ifBlank { c.provider })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { c.provider }, color = NineRouterColors.TextMain)
                                Text("${c.provider} · ${c.authType}", style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
                            }
                            Switch(c.enabled, {
                                c.enabled = it
                                Store.save()
                                refresh()
                            })
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
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Add connection", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(provider, { provider = it }, label = { Text("Provider id or alias") }, singleLine = true)
                    OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(oauth, { oauth = it })
                        Text(if (oauth) "OAuth token" else "API key", color = NineRouterColors.TextMain)
                    }
                    OutlinedTextField(
                        secret, { secret = it },
                        label = { Text(if (oauth) "Access token" else "API key") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    )
                    if (oauth) {
                        OutlinedTextField(
                            refreshToken, { refreshToken = it },
                            label = { Text("Refresh token (optional)") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
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
                                provider = provider.trim(), name = label.trim(),
                                authType = if (oauth) "oauth" else "apikey",
                                apiKey = if (oauth) "" else secret.trim(),
                                accessToken = if (oauth) secret.trim() else "",
                                refreshToken = refreshToken.trim(),
                            )
                        )
                        Store.save()
                        provider = ""; label = ""; secret = ""; refreshToken = ""
                        showAdd = false
                        refresh()
                    }
                }) { Text("Save", color = NineRouterColors.Brand) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }

    if (showNodeDialog) {
        AlertDialog(
            onDismissRequest = { showNodeDialog = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Add compatible node", color = NineRouterColors.TextMain) },
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
                                prefix = nodePrefix.trim(), name = nodeName.ifBlank { nodePrefix.trim() },
                                baseUrl = nodeUrl.trim(), apiKey = nodeKey.trim(),
                            )
                        )
                        Store.save()
                        nodePrefix = ""; nodeName = ""; nodeUrl = ""; nodeKey = ""
                        showNodeDialog = false
                        refresh()
                    }
                }) { Text("Save", color = NineRouterColors.Brand) }
            },
            dismissButton = { TextButton(onClick = { showNodeDialog = false }) { Text("Cancel") } },
        )
    }

    deviceChallenge?.let { challenge ->
        AlertDialog(
            onDismissRequest = { deviceChallenge = null },
            containerColor = NineRouterColors.Surface,
            title = { Text("Device login · ${deviceProvider?.name ?: ""}", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(challenge.userCode, style = MaterialTheme.typography.titleLarge, color = NineRouterColors.Brand)
                    Text("Open ${challenge.verificationUri} and enter this code.", color = NineRouterColors.TextMuted)
                    Text(deviceStatus, style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (challenge.verificationUri.isNotBlank()) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(challenge.verificationUri)))
                    }
                }) { Text("Open link", color = NineRouterColors.Brand) }
            },
            dismissButton = { TextButton(onClick = { deviceChallenge = null }) { Text("Close") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Combos
// ---------------------------------------------------------------------------

@Composable
fun CombosScreen(refresh: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("fallback") }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("Combos", "Model combos with fallback")
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Add combo", onClick = { showDialog = true })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(Store.state.combos, key = { it.name }) { combo ->
                androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
                    Panel {
                        Text(combo.name, style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                        Spacer(Modifier.height(4.dp))
                        Text(combo.models.joinToString(" → "), color = NineRouterColors.TextMuted)
                        Text("strategy: ${combo.strategy}", style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextSubtle)
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
                    OutlinedTextField(strategy, { strategy = it }, label = { Text("Strategy: fallback | round-robin") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = models.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (name.isNotBlank() && list.isNotEmpty()) {
                        Store.state.combos.add(Combo(name.trim(), list, strategy.trim()))
                        Store.save()
                        name = ""; models = ""; strategy = "fallback"
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

@Composable
fun UsageScreen(refresh: () -> Unit) {
    val usage = Store.state.usage
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("Usage & Analytics", "Monitor your API usage, token consumption, and request logs")
            Spacer(Modifier.height(12.dp))
            Panel {
                StatusRow("Requests", usage.size.toString())
                Spacer(Modifier.height(6.dp))
                StatusRow("Prompt tokens", usage.sumOf { it.prompt }.toString())
                Spacer(Modifier.height(6.dp))
                StatusRow("Completion tokens", usage.sumOf { it.completion }.toString())
                Spacer(Modifier.height(6.dp))
                StatusRow("Failed", usage.count { !it.ok }.toString())
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(usage.takeLast(100).asReversed()) { u ->
                androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
                    Panel {
                        Text("${u.provider} · ${u.model}", color = NineRouterColors.TextMain)
                        Text(
                            "${java.util.Date(u.ts)} · ${u.prompt}+${u.completion} tokens · ${if (u.ok) "ok" else "failed"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NineRouterColors.TextMuted,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(refresh: () -> Unit) {
    val s = Store.state.settings
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyName by remember { mutableStateOf("") }
    var keyAllowed by remember { mutableStateOf("") }
    var createdKey by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
            Spacer(Modifier.height(12.dp))
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
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

// ---------------------------------------------------------------------------
// Secondary screens
// ---------------------------------------------------------------------------

@Composable
fun InfoScreen(title: String) {
    val body = when (title) {
        "Console Log" -> "Request logs are written to the local gateway process. On-device log capture is not enabled in this preview."
        "Quota Tracker" -> "Usage grouped by provider is available on the Usage tab. Live provider quota polling is not part of this build."
        "MITM" -> "TLS interception and certificate injection are intentionally disabled on Android. Use provider OAuth or API keys instead."
        "CLI Tools" -> "Point your client at ${GatewayState.BASE_URL}/v1 with the API key from the Endpoint tab."
        "Proxy Pools" -> "Per-connection proxy settings are used by the routing engine. A dedicated proxy-pool editor is not included in this preview."
        "Token Saver" -> "RTK compresses tool_result content in place. Toggle it on the Settings tab."
        "Translator" -> "Supported wire formats: OpenAI Chat, OpenAI Responses, Claude, Gemini, Kiro, Cursor and commandcode (partial)."
        "Skills" -> "Agent skills are documented in skills/README.md in the repository."
        else -> "This section is available in the desktop dashboard."
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionHeader(title, null)
        Spacer(Modifier.height(12.dp))
        Panel { Text(body, color = NineRouterColors.TextMuted) }
    }
}
