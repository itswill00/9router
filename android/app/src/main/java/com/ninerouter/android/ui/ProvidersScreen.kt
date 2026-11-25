package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.core.OAuthFlow
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.ProviderDef
import com.ninerouter.android.data.ProviderNode
import com.ninerouter.android.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun ProvidersScreen(refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showNodeDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ProviderDef?>(null) }
    var provider by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var oauth by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    var nodePrefix by remember { mutableStateOf("") }
    var nodeName by remember { mutableStateOf("") }
    var nodeUrl by remember { mutableStateOf("") }
    var nodeKey by remember { mutableStateOf("") }
    var deviceChallenge by remember { mutableStateOf<OAuthFlow.DeviceChallenge?>(null) }
    var deviceProvider by remember { mutableStateOf<ProviderDef?>(null) }
    var deviceStatus by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf<ProviderDef?>(null) }

    val providers = Catalog.providers()
    val filtered = providers.filter {
        query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)
    }
    val groups = listOf(
        "OAuth Providers" to filtered.filter { it.category.equals("oauth", true) },
        "Free Providers" to filtered.filter { it.category.contains("free", true) },
        "API Key Providers" to filtered.filter { it.category.equals("apikey", true) },
        "API Key Compatible Providers" to filtered.filter {
            !it.category.equals("oauth", true) &&
                !it.category.contains("free", true) &&
                !it.category.equals("apikey", true)
        },
    )

    fun openProvider(p: ProviderDef) {
        selected = p
        provider = p.id
        label = p.name
        secret = ""
        refreshToken = ""
        oauth = p.authType.equals("oauth", true)
        showAdd = true
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            SectionHeader("Providers", "Manage your AI provider connections")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                query, { query = it },
                label = { Text("Search providers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                PrimaryButton("Add provider", onClick = {
                    selected = null
                    provider = ""
                    label = ""
                    secret = ""
                    refreshToken = ""
                    oauth = false
                    showAdd = true
                })
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Add node") { showNodeDialog = true }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (Store.state.connections.isNotEmpty()) {
                item {
                    SectionHeader(
                        "Your connections",
                        "Tap a switch to enable or pause a connection",
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                items(Store.state.connections, key = { "c-${it.id}" }) { c ->
                    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProviderIcon(c.provider, c.name.ifBlank { c.provider }, 28.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        c.name.ifBlank { c.provider },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NineRouterColors.TextMain,
                                    )
                                    Text(
                                        "${c.provider} · ${c.authType}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NineRouterColors.TextMuted,
                                    )
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
                                }) { Text("×") }
                            }
                        }
                    }
                }
            }

            groups.forEach { (heading, list) ->
                if (list.isNotEmpty()) {
                    item {
                        SectionHeader(
                            heading,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            action = {
                                TextButton(onClick = {
                                    Toast.makeText(context, "Connections are tested on first use.", Toast.LENGTH_SHORT).show()
                                }) { Text("Test All") }
                            },
                        )
                    }
                    val visible = if (showAll || query.isNotBlank()) list else list.take(4)
                    items(visible.chunked(2), key = { row -> row.joinToString("|") { it.id } }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { p ->
                                ProviderTile(
                                    p = p,
                                    count = Store.state.connections.count { it.provider == p.id },
                                    modifier = Modifier.weight(1f),
                                    onClick = { openProvider(p) },
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (visible.size < list.size) {
                        item {
                            TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text("Show all ${list.size} providers")
                            }
                        }
                    }
                }
            }

            if (Store.state.nodes.isNotEmpty()) {
                item {
                    SectionHeader("Compatible nodes", modifier = Modifier.padding(horizontal = 12.dp))
                }
                items(Store.state.nodes, key = { "n-${it.id}" }) { node ->
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProviderIcon("openai", node.name, 28.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(node.name, color = NineRouterColors.TextMain)
                                    Text("${node.prefix}/…", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
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
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = NineRouterColors.Surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    selected?.let {
                        ProviderIcon(it.id, it.name, 32.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(selected?.name ?: "Add connection", color = NineRouterColors.TextMain)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected?.let { def ->
                        if (def.id in BROWSER_OAUTH || def.id in DEVICE_OAUTH) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (def.id in BROWSER_OAUTH) {
                                    TextButton(onClick = {
                                        oauthBusy = def
                                        scope.launch(Dispatchers.IO) {
                                            val tokens = OAuthFlow.loginWithBrowser(
                                                def,
                                                openBrowser = { url ->
                                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                    }
                                                },
                                            )
                                            withContext(Dispatchers.Main) {
                                                oauthBusy = null
                                                if (tokens != null) {
                                                    Store.state.connections.add(
                                                        Connection(
                                                            id = UUID.randomUUID().toString(),
                                                            provider = def.id, name = def.name,
                                                            authType = "oauth",
                                                            accessToken = tokens.access,
                                                            refreshToken = tokens.refresh,
                                                            expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000L,
                                                        )
                                                    )
                                                    Store.save()
                                                    showAdd = false
                                                    refresh()
                                                    Toast.makeText(context, "Connected ${def.name}", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "OAuth failed for ${def.name}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }) { Text("Browser login") }
                                }
                                if (def.id in DEVICE_OAUTH) {
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
                                                                    provider = def.id, name = def.name,
                                                                    authType = "oauth",
                                                                    accessToken = tokens.access,
                                                                    refreshToken = tokens.refresh,
                                                                    expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000L,
                                                                )
                                                            )
                                                            Store.save()
                                                            deviceChallenge = null
                                                            showAdd = false
                                                            refresh()
                                                        }
                                                        break
                                                    }
                                                    delay(challenge.interval.coerceAtLeast(2) * 1000L)
                                                }
                                            }
                                        }
                                    }) { Text("Device login") }
                                }
                            }
                        }
                    }
                    if (selected != null && selected!!.authType.equals("oauth", true) &&
                        selected!!.id !in BROWSER_OAUTH && selected!!.id !in DEVICE_OAUTH
                    ) {
                        Text(
                            "This provider uses a custom login flow. Paste an access token below.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                    }
                    OutlinedTextField(provider, { provider = it }, label = { Text("Provider id or alias") }, singleLine = true)
                    OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                    OutlinedTextField(
                        secret, { secret = it },
                        label = { Text(if (oauth) "Access token" else "API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        refreshToken, { refreshToken = it },
                        label = { Text("Refresh token (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (provider.isNotBlank() && secret.isNotBlank()) {
                        Store.state.connections.add(
                            Connection(
                                id = UUID.randomUUID().toString(),
                                provider = provider.trim(),
                                name = label.trim(),
                                authType = if (oauth) "oauth" else "apikey",
                                apiKey = if (oauth) "" else secret.trim(),
                                accessToken = if (oauth) secret.trim() else "",
                                refreshToken = refreshToken.trim(),
                            )
                        )
                        Store.save()
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
                                prefix = nodePrefix.trim(),
                                name = nodeName.ifBlank { nodePrefix.trim() },
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

    oauthBusy?.let { def ->
        AlertDialog(
            onDismissRequest = { oauthBusy = null },
            containerColor = NineRouterColors.Surface,
            title = { Text("Connecting ${def.name}", color = NineRouterColors.TextMain) },
            text = { Text("Complete authorization in the browser.", color = NineRouterColors.TextMuted) },
            confirmButton = { TextButton(onClick = { oauthBusy = null }) { Text("Hide") } },
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

/** Providers whose OAuth can be completed with the generic loopback PKCE flow.
 * Custom flows (Kiro/AWS SSO, Kilo device JSON, Cursor local DB import) are not
 * presented as working buttons; those tokens must be pasted manually. */
private val BROWSER_OAUTH = setOf(
    "claude", "codex", "gemini", "gemini-cli", "antigravity", "iflow", "qwen",
)
private val DEVICE_OAUTH = setOf("github")

@Composable
private fun ProviderTile(
    p: ProviderDef,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = NineRouterColors.Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(p.id, p.name, 26.dp)
                Spacer(Modifier.width(6.dp))
                if (count > 0) {
                    Box(
                        Modifier.width(6.dp)
                            .height(6.dp)
                            .background(
                                NineRouterColors.Success,
                                androidx.compose.foundation.shape.RoundedCornerShape(50),
                            )
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                p.name,
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (count > 0) "$count connected" else "Not connected",
                style = MaterialTheme.typography.labelSmall,
                color = if (count > 0) NineRouterColors.Success else NineRouterColors.TextSubtle,
                maxLines = 1,
            )
        }
    }
}
