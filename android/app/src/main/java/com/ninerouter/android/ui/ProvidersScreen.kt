package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.ninerouter.android.GatewayService
import com.ninerouter.android.core.NodeProbe
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
    var nodeId by remember { mutableStateOf<String?>(null) }
    val detail = detailId?.let { Catalog.find(it) }
    val node = nodeId?.let { id -> Store.state.nodes.firstOrNull { it.id == id } }
    // Predictive Back returns to the catalog instead of leaving the app.
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = detail != null || node != null,
        onBackCompleted = {
            if (node != null) nodeId = null else detailId = null
        },
    )
    val screenKey = when {
        node != null -> "node:${node.id}"
        detail != null -> "detail:${detail.id}"
        else -> "catalog"
    }
    AnimatedContent(
        targetState = screenKey,
        transitionSpec = {
            val goingBack = targetState == "catalog"
            val enter = if (goingBack) slideInHorizontally { -it / 6 } else slideInHorizontally { it / 6 }
            val exit = if (goingBack) slideOutHorizontally { it / 6 } else slideOutHorizontally { -it / 6 }
            (enter + fadeIn(tween(230))) togetherWith (exit + fadeOut(tween(150)))
        },
        label = "providerNav",
    ) { key ->
        when {
            key.startsWith("node:") -> NodeDetail(
                nodeId = key.removePrefix("node:"),
                onBack = { nodeId = null },
                refresh = refresh,
            )
            key.startsWith("detail:") -> {
                val def = Catalog.find(key.removePrefix("detail:"))
                if (def != null) {
                    ProviderDetail(def = def, onBack = { detailId = null }, refresh = refresh)
                }
            }
            else -> ProviderCatalog(
                onOpen = { detailId = it.id },
                onOpenNode = { nodeId = it.id },
                refresh = refresh,
            )
        }
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
private fun ProviderCatalog(
    onOpen: (ProviderDef) -> Unit,
    onOpenNode: (ProviderNode) -> Unit,
    refresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var showAll by remember { mutableStateOf(false) }
    var showNodeDialog by remember { mutableStateOf(false) }
    var nodeType by remember { mutableStateOf("openai-compatible") }
    var nodePrefix by remember { mutableStateOf("") }
    var nodeName by remember { mutableStateOf("") }
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
                shape = NineRouterShapes.small,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = NineRouterColors.TextSubtle,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    AnimatedVisibility(visible = query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = NineRouterColors.TextSubtle,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
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
                items(Store.state.nodes, key = { "n-${it.id}" }, contentType = { "node" }) { node ->
                    Box(Modifier.padding(horizontal = 12.dp).animateItem()) {
                        NodeCard(node = node, onClick = { onOpenNode(node) })
                    }
                }
            }

            SECTION_ORDER.forEach { section ->
                val list = grouped[section].orEmpty()
                if (list.isNotEmpty()) {
                    item {
                        SectionHeader(
                            sectionTitle(section),
                            null,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
                            action = { Pill("${list.size}") },
                        )
                    }
                    val visible = if (showAll || query.isNotBlank() || statusFilter != "all") {
                        list
                    } else {
                        list.take(if (section == "apikey") 12 else 10)
                    }
                    items(visible.chunked(2), key = { row -> row.joinToString("|") { it.id } }, contentType = { "provider-row" }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).animateItem(),
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
        var apiType by remember { mutableStateOf("chat") }
        var base by remember {
            mutableStateOf(
                if (nodeType.startsWith("anthropic")) "https://api.anthropic.com/v1"
                else "https://api.openai.com/v1"
            )
        }
        fun defaultBase(forType: String) =
            if (forType.startsWith("anthropic")) "https://api.anthropic.com/v1"
            else "https://api.openai.com/v1"
        val conflict = Store.state.nodes.any {
            it.prefix.equals(nodePrefix.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { showNodeDialog = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Add compatible endpoint", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip("OpenAI", type == "openai-compatible") {
                            type = "openai-compatible"
                            base = defaultBase(type)
                        }
                        FilterChip("Anthropic", type == "anthropic-compatible") {
                            type = "anthropic-compatible"
                            base = defaultBase(type)
                        }
                    }
                    if (type == "openai-compatible") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip("Chat Completions", apiType == "chat") { apiType = "chat" }
                            FilterChip("Responses API", apiType == "responses") { apiType = "responses" }
                        }
                    }
                    OutlinedTextField(
                        nodePrefix, { nodePrefix = it.filter { c -> !c.isWhitespace() && c != '/' } },
                        label = { Text("Prefix (e.g. local)") },
                        singleLine = true,
                    )
                    OutlinedTextField(nodeName, { nodeName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(base, { base = it }, label = { Text("Base URL (ending in /v1)") }, singleLine = true)
                    if (base.trim().startsWith("http://") &&
                        !base.contains("127.0.0.1") && !base.contains("localhost") &&
                        !base.contains("[::1]")
                    ) {
                        Text(
                            "Plain HTTP sends the API key unencrypted over the network. Prefer HTTPS unless this is a trusted LAN endpoint.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.Warning,
                        )
                    }
                    OutlinedTextField(
                        nodeKey, { nodeKey = it },
                        label = { Text("API key (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    val conflict = Store.state.nodes.any {
                        it.prefix.equals(nodePrefix.trim(), ignoreCase = true)
                    }
                    if (conflict) {
                        Text(
                            "Another endpoint already uses this prefix.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.Danger,
                        )
                    }
                    Text(
                        if (type.startsWith("anthropic")) {
                            "The app appends /messages. Model ids are used verbatim."
                        } else {
                            "The app appends /chat/completions or /responses. Model ids are used verbatim."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nodePrefix.isNotBlank() && base.isNotBlank() && !conflict,
                    onClick = {
                        if (nodePrefix.isNotBlank() && base.isNotBlank() && !conflict) {
                            val node = ProviderNode(
                                id = "$type-" + UUID.randomUUID().toString().take(8),
                                prefix = nodePrefix.trim(),
                                name = nodeName.ifBlank { nodePrefix.trim() },
                                type = type,
                                apiType = if (type.startsWith("anthropic")) "chat" else apiType,
                                baseUrl = base.trim().trimEnd('/'),
                                apiKey = nodeKey.trim(),
                            )
                            Store.state.nodes.add(node)
                            Store.save()
                            nodePrefix = ""; nodeName = ""; nodeKey = ""
                            showNodeDialog = false
                            refresh()
                            onOpenNode(node)
                        }
                    },
                ) { Text("Save", color = NineRouterColors.Brand) }
            },
            dismissButton = { TextButton(onClick = { showNodeDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NodeCard(node: ProviderNode, onClick: () -> Unit) {
    val accent = if (NodeProbe.isAnthropic(node)) NineRouterColors.Warning else NineRouterColors.BrandOrange
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = NineRouterColors.Surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (NodeProbe.isAnthropic(node)) "AC" else "OC",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineRouterColors.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    NodeProbe.typeLabel(node),
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${node.prefix}/ · ${node.models.size} models" +
                        if (node.apiKey.isBlank()) " · no key" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextSubtle)
        }
    }
}

@Composable
private fun NodeInfo(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = NineRouterColors.TextMain,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NodeDetail(nodeId: String, onBack: () -> Unit, refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val node = Store.state.nodes.firstOrNull { it.id == nodeId }
    if (node == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var newModel by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var fetchMessage by remember { mutableStateOf("") }
    var testModel by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun save() {
        Store.save()
        refresh()
    }

    fun test(model: String) {
        if (testing) return
        testing = true
        testModel = model
        testResult = null
        scope.launch {
            val result = NodeProbe.testModel(node, model)
            testing = false
            testResult = result.ok to
                (if (result.latencyMs > 0) "${result.message} · ${result.latencyMs} ms" else result.message)
        }
    }

    fun fetch() {
        if (fetching) return
        fetching = true
        fetchMessage = ""
        scope.launch {
            val result = NodeProbe.fetchModels(node)
            fetching = false
            when {
                result.error != null -> fetchMessage = result.error
                result.models.isEmpty() -> fetchMessage = "The endpoint returned no models."
                else -> {
                    val merged = (node.models + result.models).distinct()
                    val added = merged.size - node.models.size
                    node.models = merged
                    save()
                    fetchMessage = "Added $added model${if (added == 1) "" else "s"} (${result.models.size} returned)."
                }
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = NineRouterSpacing.md, vertical = NineRouterSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(NineRouterSpacing.md),
    ) {
        item {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = NineRouterSpacing.xs, vertical = NineRouterSpacing.xs),
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = NineRouterColors.TextMuted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Providers", color = NineRouterColors.TextMuted)
            }
        }
        item {
            Row(verticalAlignment = Alignment.Top) {
                val accent = if (NodeProbe.isAnthropic(node)) NineRouterColors.Warning else NineRouterColors.BrandOrange
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
                ) {
                    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (NodeProbe.isAnthropic(node)) "AC" else "OC",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.width(NineRouterSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(node.name, style = MaterialTheme.typography.headlineSmall, color = NineRouterColors.TextMain)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${NodeProbe.typeLabel(node)} · ${node.prefix}/",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${node.models.size} model${if (node.models.size == 1) "" else "s"} configured",
                        style = MaterialTheme.typography.labelMedium,
                        color = NineRouterColors.TextSubtle,
                    )
                }
                TextButton(onClick = { showEdit = true }) { Text("Edit", color = NineRouterColors.BrandOrange) }
            }
        }

        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Endpoint", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                    Spacer(Modifier.weight(1f))
                    Pill(if (node.apiKey.isBlank()) "no key" else "key saved")
                }
                Spacer(Modifier.height(NineRouterSpacing.sm))
                Surface(
                    color = NineRouterColors.SurfaceSunken,
                    shape = NineRouterShapes.small,
                    border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonoText(NodeProbe.chatUrl(node), modifier = Modifier.weight(1f), maxLines = 2)
                        IconButton(
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(NodeProbe.chatUrl(node)))
                                Toast.makeText(context, "Endpoint copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                CopyIcon,
                                contentDescription = "Copy endpoint",
                                tint = NineRouterColors.TextSubtle,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(NineRouterSpacing.sm))
                NodeInfo("Base URL", node.baseUrl)
                Spacer(Modifier.height(6.dp))
                NodeInfo("Model list", NodeProbe.modelsUrl(node))
                Spacer(Modifier.height(6.dp))
                NodeInfo("API key", if (node.apiKey.isBlank()) "Not set" else "••••••••••••")
                Spacer(Modifier.height(NineRouterSpacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
                    SecondaryButton(if (fetching) "Fetching…" else "Fetch models", onClick = { fetch() }, modifier = Modifier.weight(1f))
                    SecondaryButton("Check key", onClick = { test(node.models.firstOrNull().orEmpty()) }, modifier = Modifier.weight(1f))
                }
                if (fetchMessage.isNotBlank()) {
                    Spacer(Modifier.height(NineRouterSpacing.sm))
                    Text(fetchMessage, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Info)
                }
            }
        }

        testResult?.let { (ok, message) ->
            item {
                NoticeBanner(
                    (if (ok) "Model responded: " else "Test failed: ") + message,
                    if (ok) NineRouterColors.Success else NineRouterColors.Danger,
                )
            }
        }

        item {
            Panel(modifier = Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Models", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                    Spacer(Modifier.width(NineRouterSpacing.sm))
                    Pill(node.models.size.toString())
                }
                Spacer(Modifier.height(2.dp))
                MonoText("Send ${node.prefix}/<model-id> from any client", color = NineRouterColors.TextMuted, maxLines = 2)
                Spacer(Modifier.height(NineRouterSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newModel,
                        onValueChange = { newModel = it },
                        placeholder = { Text("Add a model id") },
                        singleLine = true,
                        shape = NineRouterShapes.small,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(NineRouterSpacing.sm))
                    PrimaryButton(
                        "Add",
                        enabled = newModel.isNotBlank(),
                        onClick = {
                            val value = newModel.trim()
                            if (value.isNotBlank() && value !in node.models) {
                                node.models = node.models + value
                                newModel = ""
                                save()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(NineRouterSpacing.sm))
                if (node.models.isEmpty()) {
                    EmptyState(
                        title = "No models yet",
                        body = "Add a model id above or fetch the endpoint catalog.",
                    )
                } else {
                    node.models.forEachIndexed { index, model ->
                        if (index > 0) DividerLine(Modifier.padding(vertical = 2.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MonoText(model, maxLines = 1)
                                if (testing && testModel == model) {
                                    Text("Testing…", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Info)
                                }
                            }
                            TextButton(onClick = { test(model) }, enabled = !testing) {
                                Text("Test", color = NineRouterColors.BrandOrange)
                            }
                            IconButton(
                                onClick = {
                                    node.models = node.models.filterNot { it == model }
                                    save()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.Delete, "Remove model", tint = NineRouterColors.TextSubtle, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remove endpoint", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                        Text("Its model ids stop resolving immediately.", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
                    }
                    TextButton(onClick = { showDelete = true }) { Text("Delete", color = NineRouterColors.Danger) }
                }
            }
        }
    }

    if (showEdit) {
        EditNodeDialog(
            node = node,
            onDismiss = { showEdit = false },
            onSaved = {
                showEdit = false
                save()
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Delete ${node.name}?", color = NineRouterColors.TextMain) },
            text = {
                Text(
                    "Connections and model ids under ${node.prefix}/ stop working immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.state.nodes.removeAll { it.id == node.id }
                    Store.save()
                    showDelete = false
                    refresh()
                    onBack()
                }) { Text("Delete", color = NineRouterColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EditNodeDialog(node: ProviderNode, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(node.name) }
    var prefix by remember { mutableStateOf(node.prefix) }
    var type by remember { mutableStateOf(node.type) }
    var apiType by remember { mutableStateOf(node.apiType) }
    var base by remember { mutableStateOf(node.baseUrl) }
    var key by remember { mutableStateOf(node.apiKey) }
    val conflict = Store.state.nodes.any {
        it.id != node.id && it.prefix.equals(prefix.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Edit ${node.name}", color = NineRouterColors.TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("OpenAI", type == "openai-compatible") { type = "openai-compatible" }
                    FilterChip("Anthropic", type == "anthropic-compatible") { type = "anthropic-compatible" }
                }
                if (type == "openai-compatible") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip("Chat Completions", apiType == "chat") { apiType = "chat" }
                        FilterChip("Responses API", apiType == "responses") { apiType = "responses" }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    prefix,
                    { prefix = it.filter { c -> !c.isWhitespace() && c != '/' } },
                    label = { Text("Prefix") },
                    singleLine = true,
                )
                OutlinedTextField(base, { base = it }, label = { Text("Base URL (ending in /v1)") }, singleLine = true)
                if (base.trim().startsWith("http://") &&
                    !base.contains("127.0.0.1") && !base.contains("localhost") &&
                    !base.contains("[::1]")
                ) {
                    Text(
                        "Plain HTTP sends the API key unencrypted. Prefer HTTPS unless this is a trusted LAN endpoint.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.Warning,
                    )
                }
                OutlinedTextField(
                    key, { key = it },
                    label = { Text("API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (conflict) {
                    Text("Another endpoint already uses this prefix.", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Danger)
                }
                Text(
                    "Changing the prefix changes the model ids clients must send.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.Warning,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = prefix.isNotBlank() && base.isNotBlank() && !conflict,
                onClick = {
                    node.name = name.trim().ifBlank { node.prefix }
                    node.prefix = prefix.trim()
                    node.type = type
                    node.apiType = if (type.startsWith("anthropic")) "chat" else apiType
                    node.baseUrl = base.trim().trimEnd('/')
                    node.apiKey = key.trim()
                    Store.save()
                    onSaved()
                },
            ) { Text("Save", color = NineRouterColors.Brand) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    var modelQuery by remember { mutableStateOf("") }
    var visibleModels by remember { mutableStateOf(10) }

    val connections = Store.state.connections.filter { it.provider == def.id }
    val authLabel = when {
        ProviderAuth.browserOAuth(def) && ProviderAuth.apiKey(def) -> "OAuth or API key"
        ProviderAuth.browserOAuth(def) -> "OAuth"
        ProviderAuth.deviceOAuth(def) -> "Device code"
        ProviderAuth.cookie(def) -> "Cookie"
        ProviderAuth.customOAuth(def) -> "Access token"
        else -> "API key"
    }

    fun openWebsite() {
        if (def.website.isBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(def.website))) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = NineRouterSpacing.md, vertical = NineRouterSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(NineRouterSpacing.md),
    ) {
        item {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = NineRouterSpacing.xs, vertical = NineRouterSpacing.xs),
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = NineRouterColors.TextMuted,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Providers", color = NineRouterColors.TextMuted)
            }
        }

        item {
            Row(verticalAlignment = Alignment.Top) {
                ProviderIcon(def.id, def.name, 54.dp)
                Spacer(Modifier.width(NineRouterSpacing.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            def.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = NineRouterColors.TextMain,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (def.deprecated) {
                            Spacer(Modifier.width(NineRouterSpacing.sm))
                            Pill("Deprecated", NineRouterColors.Warning, NineRouterColors.Warning.copy(alpha = 0.12f))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${connections.size} connection${if (connections.size == 1) "" else "s"} · $authLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                    )
                    if (def.website.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open provider website ↗",
                            style = MaterialTheme.typography.labelMedium,
                            color = NineRouterColors.BrandOrange,
                            modifier = Modifier.clickable(onClick = { openWebsite() }),
                        )
                    }
                }
            }
        }

        if (ProviderAuth.customOAuth(def)) {
            item {
                NoticeBanner(
                    text = "${ProviderAuth.customOAuthHint(def)} Paste a token to use it.",
                    color = NineRouterColors.Warning,
                )
            }
        } else if (ProviderAuth.cookie(def)) {
            item {
                NoticeBanner(
                    text = "Web-session providers use the Cookie header. Some require browser-only attestation that this app does not reproduce.",
                    color = NineRouterColors.Warning,
                )
            }
        }

        item {
            Panel(modifier = Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connections", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                    Spacer(Modifier.width(NineRouterSpacing.sm))
                    Pill(connections.size.toString())
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Accounts are tried in priority order, then fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
                Spacer(Modifier.height(NineRouterSpacing.md))
                if (connections.isEmpty()) {
                    EmptyState(
                        title = "No connections yet",
                        body = "Add an account or key to start routing requests through ${def.name}.",
                    )
                } else {
                    connections.forEachIndexed { index, conn ->
                        if (index > 0) DividerLine(Modifier.padding(vertical = NineRouterSpacing.sm))
                        ConnectionListItem(
                            conn = conn,
                            onChange = {
                                Store.save()
                                refresh()
                            },
                            onDelete = {
                                Store.state.connections.removeAll { it.id == conn.id }
                                Store.save()
                                refresh()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(NineRouterSpacing.md))
                ConnectionActions(
                    def = def,
                    onOAuth = { showOAuth = true },
                    onDevice = { showDevice = true },
                    onApiKey = { showApiKey = true },
                    onToken = { showToken = true },
                    onCookie = { showCookie = true },
                )
            }
        }

        item {
            Panel(modifier = Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Models", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                    Spacer(Modifier.width(NineRouterSpacing.sm))
                    Pill(def.models.size.toString())
                    Spacer(Modifier.weight(1f))
                    Text(
                        "addressed as ${def.alias}/…",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextSubtle,
                    )
                }
                if (def.models.isNotEmpty()) {
                    Spacer(Modifier.height(NineRouterSpacing.sm))
                    OutlinedTextField(
                        value = modelQuery,
                        onValueChange = {
                            modelQuery = it
                            visibleModels = 10
                        },
                        placeholder = { Text("Search ${def.models.size} models") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        singleLine = true,
                        shape = NineRouterShapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(NineRouterSpacing.sm))

                val filtered = if (modelQuery.isBlank()) {
                    def.models
                } else {
                    def.models.filter { model ->
                        model.contains(modelQuery, true) ||
                            def.modelNames[model].orEmpty().contains(modelQuery, true)
                    }
                }
                if (def.models.isEmpty()) {
                    EmptyState(
                        title = "No model catalog",
                        body = "This provider has no exported model list. You can still use a custom model id from a combo.",
                    )
                } else if (filtered.isEmpty()) {
                    EmptyState(
                        title = "No matches",
                        body = "No model matches “$modelQuery”.",
                    )
                } else {
                    filtered.take(visibleModels).forEachIndexed { index, model ->
                        if (index > 0) DividerLine(Modifier.padding(vertical = 2.dp))
                        ModelCatalogRow(
                            model = model,
                            name = def.modelNames[model],
                            caps = def.modelCapabilities[model].orEmpty(),
                        )
                    }
                    if (filtered.size > visibleModels) {
                        TextButton(onClick = { visibleModels += 20 }) {
                            Text("Show ${minOf(20, filtered.size - visibleModels)} more of ${filtered.size}", color = NineRouterColors.BrandOrange)
                        }
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

@Composable
private fun ConnectionActions(
    def: ProviderDef,
    onOAuth: () -> Unit,
    onDevice: () -> Unit,
    onApiKey: () -> Unit,
    onToken: () -> Unit,
    onCookie: () -> Unit,
) {
    val browser = ProviderAuth.browserOAuth(def)
    val device = ProviderAuth.deviceOAuth(def)
    val apiKey = ProviderAuth.apiKey(def)
    val custom = ProviderAuth.customOAuth(def)
    val cookie = ProviderAuth.cookie(def)

    Column(verticalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
        when {
            browser -> PrimaryButton("Connect with OAuth", onClick = onOAuth, modifier = Modifier.fillMaxWidth())
            device -> PrimaryButton("Connect with device code", onClick = onDevice, modifier = Modifier.fillMaxWidth())
            custom -> PrimaryButton("Paste access token", onClick = onToken, modifier = Modifier.fillMaxWidth())
        }
        if (apiKey) {
            if (browser || device) {
                SecondaryButton("Add API key", onClick = onApiKey, modifier = Modifier.fillMaxWidth())
            } else if (!custom) {
                PrimaryButton("Add API key", onClick = onApiKey, modifier = Modifier.fillMaxWidth())
            }
        }
        if (cookie) {
            SecondaryButton("Paste cookie", onClick = onCookie, modifier = Modifier.fillMaxWidth())
        }
        if (browser || device) {
            TextButton(onClick = onToken, modifier = Modifier.fillMaxWidth()) {
                Text("Paste token manually", color = NineRouterColors.TextMuted)
            }
        }
    }
}

@Composable
private fun ConnectionListItem(conn: Connection, onChange: () -> Unit, onDelete: () -> Unit) {
    val typeLabel = when (conn.authType.lowercase()) {
        "oauth" -> "OAuth"
        "cookie" -> "Cookie"
        else -> "API key"
    }
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            color = if (conn.enabled) NineRouterColors.Success.copy(alpha = 0.10f) else NineRouterColors.Surface2,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (conn.enabled) NineRouterColors.Success.copy(alpha = 0.25f) else NineRouterColors.Border),
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Text(
                    when (conn.authType.lowercase()) {
                        "oauth" -> "OA"
                        "cookie" -> "CK"
                        else -> "KEY"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conn.enabled) NineRouterColors.Success else NineRouterColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.width(NineRouterSpacing.sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conn.name.ifBlank { conn.provider },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineRouterColors.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!conn.enabled) {
                    Spacer(Modifier.width(6.dp))
                    Pill("off", NineRouterColors.TextMuted, NineRouterColors.Surface2)
                }
            }
            Text(
                listOfNotNull(
                    typeLabel,
                    conn.email.takeIf { it.isNotBlank() },
                    conn.projectId.takeIf { it.isNotBlank() }?.let { "project ${it.take(12)}" },
                    conn.lastError.takeIf { it.isNotBlank() }?.let { "error: ${it.take(36)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (conn.lastError.isNotBlank()) NineRouterColors.Danger else NineRouterColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Priority ${conn.priority}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
                IconButton(
                    onClick = {
                        conn.priority = (conn.priority - 1).coerceAtLeast(0)
                        onChange()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = NineRouterColors.TextMuted, modifier = Modifier.size(17.dp))
                }
                IconButton(
                    onClick = {
                        conn.priority += 1
                        onChange()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move down", tint = NineRouterColors.TextMuted, modifier = Modifier.size(17.dp))
                }
            }
        }
        Switch(
            checked = conn.enabled,
            onCheckedChange = {
                conn.enabled = it
                onChange()
            },
            modifier = Modifier.size(34.dp, 20.dp),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete connection", tint = NineRouterColors.TextSubtle, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun ModelCatalogRow(model: String, name: String?, caps: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                name ?: model,
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (name != null) {
                Text(
                    model,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = NineRouterColors.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (caps.isNotEmpty()) {
            Spacer(Modifier.width(NineRouterSpacing.sm))
            Text(
                caps.take(2).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
                maxLines = 1,
            )
        }
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
            val processed = if (result.tokens != null) {
                withContext(Dispatchers.IO) { OAuthFlow.postProcess(def, result.tokens) }
            } else {
                result
            }
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
                            deviceId = tokens.deviceId,
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
                    "A browser tab opens on the provider's login page. After you approve, the page shows a Return to 9Router button — tap it to come back. The app catches the callback on a temporary localhost port, exactly like the desktop dashboard.",
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
        if (ch.verificationUri.isNotBlank()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ch.verificationUri))) }
        }
        var interval = ch.interval.coerceAtLeast(2)
        while (!cancelled && System.currentTimeMillis() < ch.expiresAt) {
            delay(interval * 1000L)
            if (cancelled) break
            val result = withContext(Dispatchers.IO) { OAuthFlow.pollDevice(def, ch) }
            val tokens = result.tokens
            if (tokens != null) {
                val provenanced = tokens.copy(deviceId = ch.deviceId)
                val processed = withContext(Dispatchers.IO) { OAuthFlow.postProcess(def, provenanced) }
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
                                deviceId = processed.tokens.deviceId,
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
