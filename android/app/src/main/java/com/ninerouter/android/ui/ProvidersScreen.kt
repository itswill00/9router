package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.GatewayService
import com.ninerouter.android.core.OAuthFlow
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderAuth
import com.ninerouter.android.data.ProviderDef
import com.ninerouter.android.data.ProviderNode
import com.ninerouter.android.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Composable
fun ProvidersScreen(refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    var detailId by remember { mutableStateOf<String?>(null) }
    val detail = detailId?.let { Catalog.find(it) }
    if (detail != null) {
        ProviderDetail(def = detail, onBack = { detailId = null }, refresh = refresh)
    } else {
        ProviderCatalog(onOpen = { detailId = it.id }, refresh = refresh)
    }
}

// ---------------------------------------------------------------------------
// Catalog — the same grouping the desktop dashboard uses
// ---------------------------------------------------------------------------

private val SECTION_ORDER = listOf("oauth", "free", "freetier", "apikey", "cookie")

private fun sectionFor(def: ProviderDef): String = when {
    def.category.equals("webCookie", true) -> "cookie"
    def.category.equals("oauth", true) -> "oauth"
    def.category.equals("freeTier", true) -> "freetier"
    def.category.equals("free", true) -> "free"
    else -> "apikey"
}

private fun sectionTitle(section: String): String = when (section) {
    "oauth" -> "OAuth Providers"
    "free" -> "Free Providers"
    "freetier" -> "Free Tier Providers"
    "cookie" -> "Web Cookie Providers"
    else -> "API Key Providers"
}

@Composable
private fun ProviderCatalog(onOpen: (ProviderDef) -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var showAll by remember { mutableStateOf(false) }
    var showNodeDialog by remember { mutableStateOf(false) }
    var nodeType by remember { mutableStateOf("openai-compatible") }
    var nodePrefix by remember { mutableStateOf("") }
    var nodeName by remember { mutableStateOf("") }
    var nodeUrl by remember { mutableStateOf("") }
    var nodeKey by remember { mutableStateOf("") }

    val connections = Store.state.connections
    fun stats(def: ProviderDef): Triple<Int, Int, Int> {
        val list = connections.filter { it.provider == def.id }
        val active = list.count { it.enabled }
        val errors = list.count { it.enabled && it.lastError.isNotBlank() }
        return Triple(active, errors, list.size)
    }

    val providers = Catalog.providers()
        .filter { !it.hidden }
        .filter { def ->
            val q = query.trim()
            q.isBlank() || def.name.contains(q, true) || def.id.contains(q, true) || def.alias.contains(q, true)
        }
        .filter { def ->
            val (active, errors, total) = stats(def)
            when (statusFilter) {
                "connected" -> total > 0
                "active" -> active > 0
                "error" -> errors > 0
                "disconnected" -> total == 0
                else -> true
            }
        }

    val grouped = SECTION_ORDER.associateWith { section ->
        providers.filter { sectionFor(it) == section }
            .sortedWith(
                compareBy(
                    { it.priority },
                    { -stats(it).first },
                    { it.name.lowercase() },
                )
            )
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            SectionHeader("Providers", "Connect accounts and API keys")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                query, { query = it },
                label = { Text("Search providers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "all" to "All",
                    "connected" to "Connected",
                    "active" to "Enabled",
                    "error" to "Errors",
                ).forEach { (value, label) ->
                    FilterChip(label, statusFilter == value) { statusFilter = value }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader(
                    "Custom Providers",
                    "OpenAI / Anthropic compatible endpoints",
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SecondaryButton("OpenAI compatible") {
                        nodeType = "openai-compatible"
                        showNodeDialog = true
                    }
                    SecondaryButton("Anthropic compatible") {
                        nodeType = "anthropic-compatible"
                        showNodeDialog = true
                    }
                }
            }
            if (Store.state.nodes.isEmpty()) {
                item {
                    EmptyHint("No custom endpoints yet — add one above.", Modifier.padding(horizontal = 12.dp))
                }
            } else {
                items(Store.state.nodes, key = { "n-${it.id}" }) { node ->
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProviderIcon("openai", node.name, 30.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(node.name, color = NineRouterColors.TextMain)
                                    Text(
                                        "${node.type} · ${node.prefix}/…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NineRouterColors.TextMuted,
                                    )
                                }
                                TextButton(onClick = {
                                    Store.state.nodes.removeAll { it.id == node.id }
                                    Store.save()
                                    refresh()
                                }) { Text("×") }
                            }
                        }
                    }
                }
            }

            SECTION_ORDER.forEach { section ->
                val list = grouped[section].orEmpty()
                if (list.isNotEmpty()) {
                    item {
                        SectionHeader(
                            sectionTitle(section),
                            "${list.size} providers",
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
                        )
                    }
                    val visible = if (showAll || query.isNotBlank() || statusFilter != "all") {
                        list
                    } else {
                        list.take(if (section == "apikey") 12 else 10)
                    }
                    items(visible.chunked(2), key = { row -> row.joinToString("|") { it.id } }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { def ->
                                val (active, errors, total) = stats(def)
                                ProviderCard(
                                    def = def,
                                    active = active,
                                    errors = errors,
                                    total = total,
                                    modifier = Modifier.weight(1f),
                                    onOpen = { onOpen(def) },
                                    onToggle = { enabled ->
                                        connections.filter { it.provider == def.id }.forEach { it.enabled = enabled }
                                        Store.save()
                                        refresh()
                                    },
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (visible.size < list.size) {
                        item {
                            TextButton(
                                onClick = { showAll = true },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) { Text("Show all ${list.size} ${sectionTitle(section).lowercase()}") }
                        }
                    }
                }
            }

            if (grouped.values.all { it.isEmpty() }) {
                item { EmptyHint("No providers match your search or filter.", Modifier.padding(12.dp)) }
            }
        }
    }

    if (showNodeDialog) {
        var type by remember { mutableStateOf(nodeType) }
        AlertDialog(
            onDismissRequest = { showNodeDialog = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Add compatible endpoint", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip("OpenAI", type == "openai-compatible") { type = "openai-compatible" }
                        FilterChip("Anthropic", type == "anthropic-compatible") { type = "anthropic-compatible" }
                    }
                    OutlinedTextField(nodePrefix, { nodePrefix = it }, label = { Text("Prefix (e.g. local)") }, singleLine = true)
                    OutlinedTextField(nodeName, { nodeName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(nodeUrl, { nodeUrl = it }, label = { Text("Base URL") }, singleLine = true)
                    OutlinedTextField(nodeKey, { nodeKey = it }, label = { Text("API key (optional)") }, singleLine = true)
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
                                type = type,
                                baseUrl = nodeUrl.trim(),
                                apiKey = nodeKey.trim(),
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
}

@Composable
private fun ProviderCard(
    def: ProviderDef,
    active: Int,
    errors: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onOpen),
        color = NineRouterColors.Surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(def.id, def.name, 28.dp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f))
                Switch(
                    checked = active > 0,
                    onCheckedChange = onToggle,
                    enabled = total > 0,
                    modifier = Modifier.size(34.dp, 20.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                def.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = NineRouterColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        total == 0 -> "No connections"
                        errors > 0 -> "$active active · $errors error"
                        else -> "$active/$total active"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        errors > 0 -> NineRouterColors.Danger
                        active > 0 -> NineRouterColors.Success
                        else -> NineRouterColors.TextSubtle
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (ProviderAuth.browserOAuth(def) || ProviderAuth.deviceOAuth(def)) {
                    Text("OAuth", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.BrandOrange)
                } else if (ProviderAuth.apiKey(def)) {
                    Text("Key", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Provider detail — Connections + Models, matching the desktop page
// ---------------------------------------------------------------------------

@Composable
private fun ProviderDetail(def: ProviderDef, onBack: () -> Unit, refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    val context = LocalContext.current
    var showApiKey by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showCookie by remember { mutableStateOf(false) }
    var showOAuth by remember { mutableStateOf(false) }
    var showDevice by remember { mutableStateOf(false) }
    var showAllModels by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }

    val connections = Store.state.connections.filter { it.provider == def.id }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("‹ Back", color = NineRouterColors.Brand)
                }
                Spacer(Modifier.weight(1f))
                if (def.deprecated) BadgeText("Deprecated", NineRouterColors.Warning)
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderIcon(def.id, def.name, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(def.name, style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                        Text(
                            "${def.category.ifBlank { "provider" }} · ${def.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                        if (def.website.isNotBlank()) {
                            Text(
                                def.website,
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.Info,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(def.website))) }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ProviderAuth.browserOAuth(def)) {
                        PrimaryButton("OAuth login", onClick = { showOAuth = true })
                    }
                    if (ProviderAuth.deviceOAuth(def)) {
                        SecondaryButton("Device code") { showDevice = true }
                    }
                    if (ProviderAuth.apiKey(def)) {
                        SecondaryButton("API key") { showApiKey = true }
                    }
                }
                if (ProviderAuth.customOAuth(def)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This provider needs a custom login flow. Paste an access token instead.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.Warning,
                    )
                    Spacer(Modifier.height(4.dp))
                    SecondaryButton("Paste token") { showToken = true }
                } else if (ProviderAuth.browserOAuth(def) || ProviderAuth.deviceOAuth(def)) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showToken = true }) { Text("Paste token manually", color = NineRouterColors.TextMuted) }
                }
                if (ProviderAuth.cookie(def)) {
                    Spacer(Modifier.height(4.dp))
                    SecondaryButton("Paste cookie") { showCookie = true }
                }
            }
        }

        item {
            SectionHeader("Connections", "${connections.size} saved", modifier = Modifier.padding(top = 2.dp))
        }
        if (connections.isEmpty()) {
            item { EmptyHint("No connections yet. Use the buttons above to add one.") }
        } else {
            items(connections, key = { "c-${it.id}" }) { conn ->
                @Suppress("UNUSED_VARIABLE") val revision = Store.revision
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(30.dp).background(NineRouterColors.Surface2, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when (conn.authType.lowercase()) {
                                    "oauth" -> "🔒"
                                    "cookie" -> "🍪"
                                    else -> "🔑"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                conn.name.ifBlank { conn.provider },
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineRouterColors.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${conn.authType} · priority ${conn.priority}" +
                                    (if (conn.email.isNotBlank()) " · ${conn.email}" else "") +
                                    if (conn.lastError.isNotBlank()) " · ${conn.lastError.take(40)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (conn.lastError.isNotBlank()) NineRouterColors.Danger else NineRouterColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(conn.enabled, {
                            conn.enabled = it
                            Store.save()
                            refresh()
                        })
                        TextButton(onClick = {
                            Store.state.connections.removeAll { it.id == conn.id }
                            Store.save()
                            refresh()
                        }) { Text("×") }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Priority ${conn.priority}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextSubtle,
                        )
                        TextButton(
                            onClick = {
                                conn.priority = (conn.priority - 1).coerceAtLeast(0)
                                Store.save()
                                refresh()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("▲", color = NineRouterColors.TextMuted) }
                        TextButton(
                            onClick = {
                                conn.priority += 1
                                Store.save()
                                refresh()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("▼", color = NineRouterColors.TextMuted) }
                    }
                }
            }
        }

        item {
            SectionHeader("Models", "${def.models.size} available", modifier = Modifier.padding(top = 6.dp))
        }
        if (def.models.isEmpty()) {
            item { EmptyHint("This provider has no model catalog on device.") }
        } else {
            val filtered = if (modelQuery.isBlank()) {
                def.models
            } else {
                def.models.filter { model ->
                    model.contains(modelQuery, true) ||
                        def.modelNames[model].orEmpty().contains(modelQuery, true)
                }
            }
            item {
                OutlinedTextField(
                    modelQuery, { modelQuery = it },
                    label = { Text("Search ${def.models.size} models") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val visible = if (showAllModels || modelQuery.isNotBlank()) filtered else filtered.take(10)
            items(visible, key = { "m-$it" }) { model ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                def.modelNames[model] ?: model,
                                style = MaterialTheme.typography.bodySmall,
                                color = NineRouterColors.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (def.modelNames[model] != null) {
                                Text(
                                    model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NineRouterColors.TextSubtle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val caps = def.modelCapabilities[model].orEmpty()
                        if (caps.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                caps.take(3).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextSubtle,
                            )
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                item { EmptyHint("No models match \u201c$modelQuery\u201d.") }
            }
            if (!showAllModels && modelQuery.isBlank() && filtered.size > 10) {
                item {
                    TextButton(onClick = { showAllModels = true }) {
                        Text("Show all ${filtered.size} models")
                    }
                }
            }
        }
    }

    if (showApiKey) {
        ApiKeyDialog(def, onDismiss = { showApiKey = false }, onSaved = {
            showApiKey = false
            refresh()
            Toast.makeText(context, "Connection added", Toast.LENGTH_SHORT).show()
        })
    }
    if (showToken) {
        TokenDialog(def, onDismiss = { showToken = false }, onSaved = {
            showToken = false
            refresh()
            Toast.makeText(context, "Connection added", Toast.LENGTH_SHORT).show()
        })
    }
    if (showCookie) {
        CookieDialog(def, onDismiss = { showCookie = false }, onSaved = {
            showCookie = false
            refresh()
            Toast.makeText(context, "Cookie saved", Toast.LENGTH_SHORT).show()
        })
    }
    if (showOAuth) {
        OAuthDialog(def, onDismiss = { showOAuth = false }, onSaved = {
            showOAuth = false
            refresh()
            Toast.makeText(context, "Connected ${def.name}", Toast.LENGTH_SHORT).show()
        })
    }
    if (showDevice) {
        DeviceDialog(def, onDismiss = { showDevice = false }, onSaved = {
            showDevice = false
            refresh()
            Toast.makeText(context, "Connected ${def.name}", Toast.LENGTH_SHORT).show()
        })
    }
}

// ---------------------------------------------------------------------------
// Add-connection dialogs
// ---------------------------------------------------------------------------

@Composable
private fun ApiKeyDialog(def: ProviderDef, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(def.name) }
    var key by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Add ${def.name} API key", color = NineRouterColors.TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") }, singleLine = true)
                OutlinedTextField(
                    key, { key = it },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    priority, { priority = it.filter(Char::isDigit) },
                    label = { Text("Priority") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Stored encrypted with the Android Keystore on this device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank(),
                onClick = {
                    Store.state.connections.add(
                        Connection(
                            id = UUID.randomUUID().toString(),
                            provider = def.id,
                            name = name.trim().ifBlank { def.name },
                            authType = "apikey",
                            apiKey = key.trim(),
                            priority = priority.toIntOrNull() ?: 1,
                        )
                    )
                    Store.save()
                    onSaved()
                },
            ) { Text("Save", color = NineRouterColors.Brand) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TokenDialog(def: ProviderDef, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(def.name) }
    var access by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Paste ${def.name} token", color = NineRouterColors.TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") }, singleLine = true)
                OutlinedTextField(
                    access, { access = it },
                    label = { Text("Access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    refresh, { refresh = it },
                    label = { Text("Refresh token (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    minutes, { minutes = it.filter(Char::isDigit) },
                    label = { Text("Expires in minutes (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = access.isNotBlank(),
                onClick = {
                    val mins = minutes.toLongOrNull() ?: 0L
                    Store.state.connections.add(
                        Connection(
                            id = UUID.randomUUID().toString(),
                            provider = def.id,
                            name = name.trim().ifBlank { def.name },
                            authType = "oauth",
                            accessToken = access.trim(),
                            refreshToken = refresh.trim(),
                            expiresAt = if (mins > 0) System.currentTimeMillis() + mins * 60_000L else 0L,
                        )
                    )
                    Store.save()
                    onSaved()
                },
            ) { Text("Save", color = NineRouterColors.Brand) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CookieDialog(def: ProviderDef, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(def.name) }
    var cookie by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Paste ${def.name} cookie", color = NineRouterColors.TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") }, singleLine = true)
                OutlinedTextField(
                    cookie, { cookie = it },
                    label = { Text("Cookie header") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = cookie.isNotBlank(),
                onClick = {
                    Store.state.connections.add(
                        Connection(
                            id = UUID.randomUUID().toString(),
                            provider = def.id,
                            name = name.trim().ifBlank { def.name },
                            authType = "cookie",
                            apiKey = cookie.trim(),
                        )
                    )
                    Store.save()
                    onSaved()
                },
            ) { Text("Save", color = NineRouterColors.Brand) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OAuthDialog(def: ProviderDef, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    val manualRef = remember { AtomicReference<String?>(null) }

    fun startLogin() {
        if (busy) return
        busy = true
        status = "Opening browser..."
        error = ""
        GatewayService.start(context)
        scope.launch {
            val result = OAuthFlow.loginWithBrowser(
                def = def,
                openBrowser = { url ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            error = "No browser available: ${e.message}"
                        }
                    }
                },
                onStatus = { msg -> android.os.Handler(android.os.Looper.getMainLooper()).post { status = msg } },
                manualCode = { manualRef.get() },
            )
            val processed = if (result.tokens != null) OAuthFlow.postProcess(def, result.tokens) else result
            withContext(Dispatchers.Main) {
                busy = false
                val tokens = processed.tokens
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
                            projectId = tokens.projectId,
                            email = tokens.email,
                            accountId = tokens.accountId,
                        )
                    )
                    Store.save()
                    onSaved()
                } else {
                    error = processed.error ?: "OAuth failed."
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = NineRouterColors.Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(def.id, def.name, 30.dp)
                Spacer(Modifier.width(8.dp))
                Text("Connect ${def.name}", color = NineRouterColors.TextMain)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A browser tab opens on the provider's login page. The app catches the callback on a temporary localhost port, exactly like the desktop dashboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Info)
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Danger)
                }
                OutlinedTextField(
                    manual, { manual = it },
                    label = { Text("Callback URL or code (fallback)") },
                    singleLine = true,
                )
                TextButton(
                    enabled = manual.isNotBlank() && busy,
                    onClick = { manualRef.set(manual.trim()) },
                ) { Text("Submit code", color = NineRouterColors.Brand) }
            }
        },
        confirmButton = {
            TextButton(onClick = { startLogin() }, enabled = !busy) {
                Text(if (busy) "Waiting..." else "Open browser", color = NineRouterColors.Brand)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun DeviceDialog(def: ProviderDef, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var challenge by remember { mutableStateOf<OAuthFlow.DeviceChallenge?>(null) }
    var status by remember { mutableStateOf("Requesting device code...") }
    var error by remember { mutableStateOf("") }
    var cancelled by remember { mutableStateOf(false) }

    LaunchedEffect(def.id) {
        val ch = withContext(Dispatchers.IO) { OAuthFlow.startDevice(def) }
        if (ch == null) {
            error = "Device flow unavailable for ${def.name}."
            status = ""
            return@LaunchedEffect
        }
        challenge = ch
        status = "Waiting for authorization..."
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ch.verificationUri))) }
        var interval = ch.interval.coerceAtLeast(2)
        while (!cancelled && System.currentTimeMillis() < ch.expiresAt) {
            delay(interval * 1000L)
            if (cancelled) break
            val result = withContext(Dispatchers.IO) { OAuthFlow.pollDevice(def, ch) }
            val tokens = result.tokens
            if (tokens != null) {
                val processed = withContext(Dispatchers.IO) { OAuthFlow.postProcess(def, tokens) }
                withContext(Dispatchers.Main) {
                    if (processed.tokens != null) {
                        Store.state.connections.add(
                            Connection(
                                id = UUID.randomUUID().toString(),
                                provider = def.id,
                                name = def.name,
                                authType = "oauth",
                                accessToken = processed.tokens.access,
                                refreshToken = processed.tokens.refresh,
                                expiresAt = System.currentTimeMillis() + processed.tokens.expiresIn * 1000L,
                                projectId = processed.tokens.projectId,
                                email = processed.tokens.email,
                                accountId = processed.tokens.accountId,
                            )
                        )
                        Store.save()
                        onSaved()
                    } else {
                        error = processed.error ?: "Token exchange failed."
                        status = ""
                    }
                }
                return@LaunchedEffect
            }
            if (result.error != null && !result.error.contains("pending", true) &&
                !result.error.contains("slow_down", true)
            ) {
                withContext(Dispatchers.Main) {
                    error = result.error
                    status = ""
                }
                return@LaunchedEffect
            }
        }
        if (!cancelled) {
            withContext(Dispatchers.Main) {
                error = "Device code expired."
                status = ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = { cancelled = true; onDismiss() },
        containerColor = NineRouterColors.Surface,
        title = { Text("Device login · ${def.name}", color = NineRouterColors.TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                challenge?.let { ch ->
                    Text(ch.userCode, style = MaterialTheme.typography.titleLarge, color = NineRouterColors.Brand)
                    Text("Open ${ch.verificationUri} and enter this code.", style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Info)
                if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Danger)
            }
        },
        confirmButton = {
            TextButton(
                enabled = challenge?.verificationUri?.isNotBlank() == true,
                onClick = {
                    challenge?.verificationUri?.let {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    }
                },
            ) { Text("Open link", color = NineRouterColors.Brand) }
        },
        dismissButton = { TextButton(onClick = { cancelled = true; onDismiss() }) { Text("Close") } },
    )
}

// ---------------------------------------------------------------------------
// Small shared bits
// ---------------------------------------------------------------------------

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.18f) else NineRouterColors.Surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) NineRouterColors.BrandOrange else NineRouterColors.TextMuted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun BadgeText(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = NineRouterColors.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = NineRouterColors.TextMuted,
            modifier = Modifier.padding(14.dp),
        )
    }
}
