package com.ninerouter.android.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninerouter.android.GatewayService
import com.ninerouter.android.R
import com.ninerouter.android.core.ChatEngine
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.ModelResolver
import com.ninerouter.android.core.Translator
import com.ninerouter.android.update.UpdateManager
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.ChatImage
import com.ninerouter.android.data.ChatMessage
import com.ninerouter.android.data.ChatSession
import com.ninerouter.android.data.Exposure
import com.ninerouter.android.data.Pricing
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ---------------------------------------------------------------------------
// Endpoint
// ---------------------------------------------------------------------------

@Composable
fun EndpointScreen(
    refresh: () -> Unit,
    onOpenChat: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
) {
    // Read the revision here, in this screen's scope, and unconditionally.
    // Reading it only inside the network-mode branch leaves the screen
    // unsubscribed while the toggle is off, so the switch never turns on.
    val revision = storeRevision().value
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val running by GatewayState.running.collectAsState()
    val gatewayError by GatewayState.error.collectAsState()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var revealKey by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var publicUrlDraft by remember { mutableStateOf(Store.state.settings.publicBaseUrl) }
    var testingPublic by remember { mutableStateOf(false) }
    var publicResult by remember { mutableStateOf("") }
    val current = Store.state.settings
    val exposed = Exposure.enabled(current)
    val apiRoot = Exposure.apiRoot(current, GatewayState.BASE_URL)

    LaunchedEffect(running) { testResult = "" }
    LaunchedEffect(publicUrlDraft, exposed) { publicResult = "" }

    fun copy(label: String, value: String) {
        copySensitive(context, label, value)
        Toast.makeText(context, tr("$label copied"), Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GatewayHero(
            running = running,
            exposed = exposed,
            apiRoot = apiRoot,
            error = gatewayError,
            testing = testing,
            testResult = testResult,
            connections = Store.state.connections.size,
            models = Catalog.modelIds().size + Store.state.nodes.sumOf { it.models.size },
            requests24h = Store.state.usage.count { it.ts >= System.currentTimeMillis() - 24L * 60 * 60 * 1000 },
            onStartStop = {
                if (running) GatewayService.stop(context) else GatewayService.start(context)
                refresh()
            },
            onOpenDashboard = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(GatewayState.BASE_URL))
                    )
                }.onFailure {
                    Toast.makeText(context, tr("No browser available"), Toast.LENGTH_SHORT).show()
                }
            },
            onCopyEndpoint = { copy("Base URL", apiRoot) },
            onCopyDashboard = { copy("URL", GatewayState.BASE_URL) },
            onOpenChat = onOpenChat,
            onOpenProviders = onOpenProviders,
            onOpenUsage = onOpenUsage,
            onTest = {
                if (!testing) {
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
            },
        )

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Browser access", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                Spacer(Modifier.weight(1f))
                Pill(
                    if (exposed) "private networks" else "loopback only",
                    if (exposed) NineRouterColors.Warning else NineRouterColors.TextMuted,
                    if (exposed) NineRouterColors.Warning.copy(alpha = 0.12f) else NineRouterColors.Surface2,
                )
            }
            Spacer(Modifier.height(NineRouterSpacing.sm))
            if (exposed) {
                val first = remember(revision, running) { localEndpoints().firstOrNull() }
                Bullet(
                    if (first != null) "Open $first on a phone, tablet or computer on the same Wi-Fi or tailnet."
                    else "Connect to Wi-Fi or a VPN; no private address is up yet."
                )
                Bullet("Public internet peers are rejected when the connection is accepted. Put a tunnel or reverse proxy in front and use the public URL in Remote access.")
                Bullet("The API key is always required while remote access is on.")
            } else {
                Bullet("Open http://127.0.0.1:${GatewayState.PORT} on this phone — include http:// and the port.")
                Bullet("Make sure the gateway shows running above, then use Open dashboard if typing fails.")
                Bullet("From a computer run adb reverse tcp:${GatewayState.PORT} tcp:${GatewayState.PORT}, then open http://localhost:${GatewayState.PORT}.")
                Bullet("The server binds to loopback by default; turn on Remote access below to reach it from other devices.")
            }
            Spacer(Modifier.height(NineRouterSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
                SecondaryButton("Restart gateway", modifier = Modifier.weight(1f)) {
                    GatewayService.restart(context)
                    Toast.makeText(context, tr("Restarting gateway…"), Toast.LENGTH_SHORT).show()
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
                    Crossfade(
                        targetState = revealKey,
                        animationSpec = tween(180),
                        label = "apiKeyReveal",
                        modifier = Modifier.weight(1f),
                    ) { revealed ->
                        MonoText(
                            if (revealed) Store.state.settings.apiKey else "••••••••••••••••••••••",
                            color = NineRouterColors.Brand,
                            maxLines = 3,
                        )
                    }
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
            Row(horizontalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
                SecondaryButton("Regenerate key", modifier = Modifier.weight(1f)) {
                    confirmRegenerate = true
                }
                SecondaryButton("Copy config", modifier = Modifier.weight(1f)) {
                    copy(
                        "Config",
                        "Base URL: $apiRoot\nAPI key: ${Store.state.settings.apiKey}\nModel: openai/gpt-5-mini",
                    )
                }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remote access", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
                Spacer(Modifier.weight(1f))
                Pill(
                    if (exposed) "LAN & tunnel" else "this phone only",
                    if (exposed) NineRouterColors.Warning else NineRouterColors.TextMuted,
                    if (exposed) NineRouterColors.Warning.copy(alpha = 0.12f) else NineRouterColors.Surface2,
                )
            }
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                "Allow LAN and tunnel clients",
                "Adds private Wi-Fi, link-local and tailnet peers. Public internet addresses are dropped.",
                exposed,
            ) { enable ->
                if (!enable) {
                    Store.mutate { settings = settings.copy(exposure = Exposure.LOOPBACK) }
                    if (running) GatewayService.rebind(context)
                    Toast.makeText(context, tr("Back to loopback only"), Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Store.mutate {
                        settings = settings.copy(
                            exposure = Exposure.NETWORK,
                            requireApiKey = true,
                            // Legacy state could have an empty key; generate one
                            // instead of silently refusing to turn the mode on.
                            apiKey = settings.apiKey.ifBlank { Store.newKey() },
                        )
                    }
                    if (running) GatewayService.rebind(context)
                    Toast.makeText(
                        context,
                        if (running) "Restarting with network access…" else "Network mode saved",
                        Toast.LENGTH_SHORT,
                    ).show()
                    refresh()
                }
            }

            if (exposed) {
                Spacer(Modifier.height(8.dp))
                NoticeBanner(
                    "The API key is required while remote access is on. Do not paste it into shared configs.",
                    NineRouterColors.Warning,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Reachable on this device",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
                Spacer(Modifier.height(2.dp))
                val hosts = remember(revision, running) { localEndpoints() }
                if (hosts.isEmpty()) {
                    Text(
                        "No private network interface yet. Connect to Wi-Fi or a VPN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                    )
                } else {
                    hosts.forEach { url ->
                        CopyRow(url.removePrefix("http://"), url) { copy("URL", url) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    publicUrlDraft,
                    { publicUrlDraft = it },
                    label = { Text("Public base URL") },
                    placeholder = { Text("https://ai.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Point a tunnel or reverse proxy at this phone, then use this HTTPS URL in clients. " +
                        "TLS ends at the proxy; the phone keeps serving plain HTTP on the private hop.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
                val draftRoot = Exposure.normalizePublicUrl(publicUrlDraft)
                if (!draftRoot.isNullOrBlank() && !draftRoot.startsWith("https://")) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "HTTP is not encrypted. Use HTTPS outside a tailnet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.Warning,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
                    SecondaryButton("Save URL", modifier = Modifier.weight(1f)) {
                        val normalized = Exposure.normalizePublicUrl(publicUrlDraft)
                        when {
                            normalized == null -> Toast.makeText(
                                context,
                                "Use http(s)://host, optionally with a port or path",
                                Toast.LENGTH_SHORT,
                            ).show()
                            else -> {
                                Store.mutate { settings = settings.copy(publicBaseUrl = normalized) }
                                publicUrlDraft = normalized
                                Toast.makeText(
                                    context,
                                    if (normalized.isBlank()) "Public URL cleared" else "Public URL saved",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refresh()
                            }
                        }
                    }
                    SecondaryButton(
                        if (testingPublic) "Testing…" else "Test URL",
                        modifier = Modifier.weight(1f),
                    ) {
                        if (testingPublic) return@SecondaryButton
                        val root = Exposure.normalizePublicUrl(publicUrlDraft)
                        if (root.isNullOrBlank()) {
                            publicResult = "Enter a public base URL first."
                            return@SecondaryButton
                        }
                        testingPublic = true
                        publicResult = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val started = System.currentTimeMillis()
                                    val health = httpProbe("$root/api/health", null)
                                    val auth = httpProbe("$root/v1/models", Store.state.settings.apiKey)
                                    "Health $health · Auth $auth · ${System.currentTimeMillis() - started} ms"
                                }.getOrElse { it.message ?: it.javaClass.simpleName }
                            }
                            testingPublic = false
                            publicResult = result
                        }
                    }
                }
                if (publicResult.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        publicResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (publicResult.contains("Auth 200")) NineRouterColors.Success else NineRouterColors.Warning,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "The test runs from this phone. If hairpin NAT blocks the round trip, try it from another device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Regenerate the gateway key?", color = NineRouterColors.TextMain) },
            text = {
                Text(
                    "Every client using the current key will stop working until it is updated.",
                    color = NineRouterColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.mutate { settings = settings.copy(apiKey = Store.newKey()) }
                    confirmRegenerate = false
                    refresh()
                }) { Text("Regenerate", color = NineRouterColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") }
            },
        )
    }
}

private fun localEndpoints(): List<String> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && Exposure.allows(it) }
        .mapNotNull { address ->
            val host = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
            if (host.contains(':')) "http://[$host]:${GatewayState.PORT}"
            else "http://$host:${GatewayState.PORT}"
        }
        .distinct()
}.getOrDefault(emptyList())

private fun httpProbe(url: String, apiKey: String?): String {
    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    return try {
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        // Never forward the gateway key to a redirect target.
        connection.instanceFollowRedirects = false
        if (!apiKey.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
        val code = connection.responseCode
        if (code in 200..299) code.toString() else "HTTP $code"
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun GatewayHero(
    running: Boolean,
    exposed: Boolean,
    apiRoot: String,
    error: String,
    testing: Boolean,
    testResult: String,
    connections: Int,
    models: Int,
    requests24h: Int,
    onStartStop: () -> Unit,
    onOpenDashboard: () -> Unit,
    onCopyEndpoint: () -> Unit,
    onCopyDashboard: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenUsage: () -> Unit,
    onTest: () -> Unit,
) {
    val stateColor = if (running) NineRouterColors.Success else NineRouterColors.TextSubtle
    Surface(
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
        color = Color.Transparent,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2A170E), NineRouterColors.SurfaceElevated),
                    ),
                )
                .drawBehind {
                    val center = Offset(size.width * 0.90f, 0f)
                    val radius = size.width * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(NineRouterColors.BrandOrange.copy(alpha = 0.20f), Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                },
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .background(stateColor.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (running) PulsingDot(stateColor, size = 10.dp)
                        else StatusDot(stateColor, size = 11.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Eyebrow(if (exposed) "Private network" else "Loopback", NineRouterColors.BrandAmber)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (running) "Gateway online" else "Gateway offline",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = NineRouterColors.TextMain,
                        )
                        Text(
                            if (!running) "Start it to serve requests"
                            else if (exposed) "Phone, Wi-Fi and tailnet clients"
                            else "OpenAI-compatible traffic on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = NineRouterColors.TextMuted,
                        )
                    }
                    Pill(
                        ":${GatewayState.PORT}",
                        if (running) NineRouterColors.BrandAmber else NineRouterColors.TextMuted,
                        if (running) NineRouterColors.BrandAmber.copy(alpha = 0.12f) else NineRouterColors.Surface2,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Surface(
                    color = NineRouterColors.Bg.copy(alpha = 0.42f),
                    shape = NineRouterShapes.medium,
                    border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        CopyRow("OpenAI base URL", apiRoot, onCopyEndpoint)
                        Spacer(Modifier.height(8.dp))
                        CopyRow("Local dashboard", GatewayState.BASE_URL, onCopyDashboard)
                    }
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    NoticeBanner(error, NineRouterColors.Danger)
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        if (running) "Shutdown" else "Start gateway",
                        modifier = Modifier.weight(1f),
                        onClick = onStartStop,
                    )
                    SecondaryButton("Dashboard", modifier = Modifier.weight(1f), onClick = onOpenDashboard)
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction("Chat", R.drawable.ic_chat, onOpenChat)
                    QuickAction("Providers", R.drawable.ic_nav_providers, onOpenProviders)
                    QuickAction("Usage", R.drawable.ic_nav_usage, onOpenUsage)
                    QuickAction(
                        if (testing) "Testing" else "Test",
                        icon = Icons.Default.Check,
                        loading = testing,
                        onClick = onTest,
                    )
                }

                AnimatedVisibility(
                    visible = testResult.isNotBlank(),
                    enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(160)),
                ) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            testResult,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (testResult.startsWith("OK")) NineRouterColors.Success else NineRouterColors.Warning,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        "Accounts",
                        connections.toString(),
                        "connected",
                        NineRouterColors.TextMain,
                        Modifier.weight(1f),
                    )
                    MetricTile(
                        "Models",
                        models.toString(),
                        "catalog",
                        NineRouterColors.TextMain,
                        Modifier.weight(1f),
                    )
                    MetricTile(
                        "24h",
                        requests24h.toString(),
                        "requests",
                        NineRouterColors.BrandAmber,
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.QuickAction(
    label: String,
    iconRes: Int? = null,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    loading: Boolean = false,
) {
    Surface(
        color = NineRouterColors.Surface2.copy(alpha = 0.56f),
        shape = NineRouterShapes.medium,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
        modifier = Modifier
            .weight(1f)
            .clip(NineRouterShapes.medium)
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = NineRouterColors.BrandOrange,
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = NineRouterColors.BrandOrange,
                    modifier = Modifier.size(16.dp),
                )
            } else if (iconRes != null) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = null,
                    tint = NineRouterColors.BrandOrange,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1300)
            copied = false
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
            Spacer(Modifier.height(1.dp))
            MonoText(value)
        }
        IconButton(onClick = { onCopy(); copied = true }, modifier = Modifier.size(32.dp)) {
            Crossfade(copied, animationSpec = tween(160), label = "copyState") { done ->
                Icon(
                    if (done) Icons.Default.Check else CopyIcon,
                    contentDescription = if (done) "Copied" else "Copy $label",
                    tint = if (done) NineRouterColors.Success else NineRouterColors.TextSubtle,
                    modifier = Modifier.size(15.dp),
                )
            }
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
// Usage
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
fun UpdatePanel(state: UpdateManager.State) {
    val context = LocalContext.current

    fun openReleasePage() {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.RELEASES_PAGE)))
        }
    }

    Panel {
        Text(
            "Release channel",
            style = MaterialTheme.typography.titleMedium,
            color = NineRouterColors.TextMain,
        )
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

private data class UiMessage(
    val role: String,
    val content: String,
    val id: Long = System.nanoTime(),
    val model: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val elapsedMs: Long = 0L,
    val images: List<ChatImage> = emptyList(),
) {
    val tokensPerSecond: Float
        get() = if (elapsedMs > 0L) outputTokens * 1000f / elapsedMs else 0f
}

private fun ChatMessage.toUi(): UiMessage = UiMessage(
    role = role,
    content = content,
    id = id,
    model = model,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    elapsedMs = elapsedMs,
    images = images,
)

private fun UiMessage.toChat(): ChatMessage = ChatMessage(
    id = id,
    role = role,
    content = content,
    model = model,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    elapsedMs = elapsedMs,
    images = images,
)

@Composable
fun ChatScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { ChatEngine() }
    val parser = remember { Json { ignoreUnknownKeys = true } }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val running by GatewayState.running.collectAsState()
    val revision by storeRevision()

    val optionsKey = Store.state.combos.map { it.name } +
        Store.state.connections.filter { it.enabled }.map { it.provider } +
        Store.state.nodes.flatMap { n -> n.models.map { "${n.prefix}/$it" } } +
        Store.state.aliases.keys.toList()
    val options = remember(optionsKey) {
        buildList {
            Store.state.combos.forEach { add(it.name) }
            Store.state.connections.forEach { c ->
                Catalog.find(c.provider)?.models?.forEach { add("${c.provider}/$it") }
            }
            Store.state.nodes.forEach { n -> n.models.forEach { add("${n.prefix}/$it") } }
            Store.state.aliases.keys.forEach { add(it) }
        }.distinct()
    }

    var sessionId by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<UiMessage>()) }
    var model by rememberSaveable { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf(listOf<ChatImage>()) }
    var imageBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    var runStartedAt by remember { mutableStateOf(0L) }
    var liveElapsed by remember { mutableStateOf(0L) }
    var introShown by remember { mutableStateOf(false) }
    val activeResponse = remember { mutableStateOf<okhttp3.Response?>(null) }
    val listState = rememberLazyListState()

    fun defaultModel(): String = Store.state.combos.firstOrNull()?.name
        ?: options.firstOrNull()
        ?: "openai/gpt-5-mini"

    fun currentSession(): ChatSession? =
        Store.state.chatSessions.firstOrNull { it.id == sessionId }

    fun persist(msgs: List<UiMessage>) {
        val cleaned = msgs.filterNot {
            it.role == "assistant" && (it.content.isBlank() || it.content.startsWith("[error"))
        }
        val prepared = cleaned.map { it.toChat() }
        val firstUser = cleaned.firstOrNull { it.role == "user" }
        val now = System.currentTimeMillis()
        if (Store.state.chatSessions.none { it.id == sessionId }) return
        Store.mutate {
            val session = chatSessions.firstOrNull { it.id == sessionId } ?: return@mutate
            session.model = model
            session.messages.clear()
            session.messages.addAll(prepared)
            session.updatedAt = now
            if (session.title == "New chat" && firstUser != null) {
                val text = firstUser.content.lineSequence().firstOrNull().orEmpty().trim()
                session.title = when {
                    text.isNotBlank() -> text.take(48)
                    firstUser.images.isNotEmpty() -> "Image"
                    else -> "New chat"
                }
            }
            chatSessions.sortByDescending { it.updatedAt }
            while (chatSessions.size > 40) {
                val removed = chatSessions.removeAt(chatSessions.lastIndex)
                removed.messages.flatMap { it.images }.forEach { runCatching { File(it.path).delete() } }
            }
        }
    }

    fun loadSession(id: String) {
        val session = Store.state.chatSessions.firstOrNull { it.id == id } ?: return
        sessionId = id
        Store.mutate { activeChatSessionId = id }
        messages = session.messages.map { it.toUi() }
        model = session.model.ifBlank { defaultModel() }
        input = ""
        pendingImages = emptyList()
    }

    fun stop() {
        generation += 1
        runCatching { activeResponse.value?.close() }
        activeResponse.value = null
        streamJob?.cancel()
        streamJob = null
        busy = false
        messages = messages.filterNot { it.role == "assistant" && it.content.isBlank() }
        persist(messages)
    }

    fun newChat() {
        stop()
        val session = ChatSession(id = UUID.randomUUID().toString(), model = model.ifBlank { defaultModel() })
        Store.mutate {
            chatSessions.add(0, session)
            activeChatSessionId = session.id
        }
        sessionId = session.id
        messages = emptyList()
        input = ""
        pendingImages = emptyList()
    }

    fun deleteSession(id: String) {
        if (Store.state.activeChatSessionId == id) stop()
        val removed = Store.state.chatSessions.firstOrNull { it.id == id }
        Store.mutate { chatSessions.removeAll { it.id == id } }
        removed?.messages?.flatMap { it.images }?.forEach { runCatching { File(it.path).delete() } }
        if (Store.state.activeChatSessionId == id) {
            val next = Store.state.chatSessions.firstOrNull()
            if (next != null) {
                loadSession(next.id)
            } else {
                sessionId = ""
                messages = emptyList()
                newChat()
            }
        }
    }

    LaunchedEffect(Unit) {
        val existing = Store.state.chatSessions.firstOrNull { it.id == Store.state.activeChatSessionId }
            ?: Store.state.chatSessions.firstOrNull()
        if (existing != null) {
            loadSession(existing.id)
        } else {
            val session = ChatSession(id = UUID.randomUUID().toString(), model = defaultModel())
            Store.mutate {
                chatSessions.add(0, session)
                activeChatSessionId = session.id
            }
            sessionId = session.id
            model = session.model
        }
        introShown = true
    }

    // Persist the latest transcript (including a partial streamed answer) when
    // the user leaves the chat tab.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { activeResponse.value?.close() }
            activeResponse.value = null
            persist(messages)
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        // Only follow the stream when the user is already near the bottom.
        if (last >= messages.lastIndex - 1) listState.scrollToItem(messages.lastIndex)
    }

    LaunchedEffect(busy) {
        while (busy) {
            liveElapsed = System.currentTimeMillis() - runStartedAt
            delay(250)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        imageBusy = true
        scope.launch {
            val room = (4 - pendingImages.size).coerceAtLeast(0)
            val added = uris.take(room).mapNotNull { uri -> saveChatImage(context, uri) }
            pendingImages = (pendingImages + added).take(4)
            imageBusy = false
            if (uris.size > room) {
                Toast.makeText(context, tr("Up to 4 images per message"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun patch(id: Long, block: (UiMessage) -> UiMessage) {
        messages = messages.map { if (it.id == id) block(it) else it }
    }

    fun send() {
        val text = input.trim()
        val images = pendingImages
        if ((text.isBlank() && images.isEmpty()) || model.isBlank() || busy) return
        input = ""
        pendingImages = emptyList()
        busy = true
        generation += 1
        val requestId = generation
        val startedAt = System.currentTimeMillis()
        runStartedAt = startedAt
        liveElapsed = 0L
        val history = messages.filterNot {
            it.role == "assistant" && it.content.startsWith("[error")
        } + UiMessage(role = "user", content = text, images = images)
        val chatSessionId = currentSession()?.id.orEmpty()
        val placeholder = UiMessage(role = "assistant", content = "", model = model)
        val inputTokens = (history.sumOf { it.content.length + it.images.size * 256 } / 4).coerceAtLeast(1)
        messages = history + placeholder
        // Save the user turn immediately; a long stream or a process kill must
        // not lose the question (and its generated chat title).
        persist(messages)
        streamJob = scope.launch(Dispatchers.IO) {
            val request = buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray { history.forEach { add(chatMessageJson(it)) } })
                put("stream", true)
                if (chatSessionId.isNotBlank()) put("session_id", chatSessionId)
            }
            try {
                when (val out = engine.handle(request.toString(), Formats.OPENAI, Formats.OPENAI, "chat")) {
                    is ChatEngine.Outcome.Json -> {
                        val answer = contentOf(parser, out.body).ifBlank { "(empty response)" }
                        val elapsed = System.currentTimeMillis() - startedAt
                        withContext(Dispatchers.Main) {
                            if (generation == requestId) {
                                patch(placeholder.id) {
                                    it.copy(
                                        content = answer,
                                        model = out.member.ifBlank { model },
                                        inputTokens = inputTokens,
                                        outputTokens = (answer.length / 4).coerceAtLeast(1),
                                        elapsedMs = elapsed,
                                    )
                                }
                                persist(messages)
                            }
                        }
                    }
                    is ChatEngine.Outcome.Stream -> {
                        activeResponse.value = out.response
                        val resolved = out.member.ifBlank { model }
                        withContext(Dispatchers.Main) {
                            if (generation == requestId) patch(placeholder.id) { it.copy(model = resolved) }
                        }
                        val answer = StringBuilder()
                        var lastUiAt = 0L
                        try {
                            out.response.body?.source()?.use { src ->
                                while (true) {
                                    val line = src.readUtf8Line() ?: break
                                    // Use the provider's real format: OAuth Gemini /
                                    // Antigravity need GEMINI + the CLI envelope.
                                    val delta = Translator.sseDelta(
                                        out.targetFormat,
                                        line,
                                        model,
                                        out.cliEnvelope,
                                    )
                                    if (delta == null || delta.contains("[DONE]")) continue
                                    val piece = deltaOf(parser, delta.removePrefix("data:").trim())
                                    if (piece.isNotBlank()) {
                                        answer.append(piece)
                                        val now = System.currentTimeMillis()
                                        // Batch UI patches; a fast provider can
                                        // emit 100+ tokens per second.
                                        if (now - lastUiAt >= 50L) {
                                            lastUiAt = now
                                            val snapshot = answer.toString()
                                            withContext(Dispatchers.Main) {
                                                if (generation == requestId) {
                                                    patch(placeholder.id) { it.copy(content = snapshot) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            activeResponse.value = null
                            out.response.close()
                        }
                        val elapsed = System.currentTimeMillis() - startedAt
                        val finalText = answer.toString()
                        withContext(Dispatchers.Main) {
                            if (generation == requestId) {
                                patch(placeholder.id) {
                                    it.copy(
                                        content = finalText.ifBlank { "(no content returned)" },
                                        inputTokens = inputTokens,
                                        outputTokens = (finalText.length / 4).coerceAtLeast(1),
                                        elapsedMs = elapsed,
                                    )
                                }
                                persist(messages)
                            }
                        }
                    }
                    is ChatEngine.Outcome.Error -> withContext(Dispatchers.Main) {
                        if (generation == requestId) {
                            patch(placeholder.id) { it.copy(content = "[error ${out.status}] ${out.message}") }
                            persist(messages)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (generation == requestId) {
                        patch(placeholder.id) { it.copy(content = "[error] ${e.message ?: "request failed"}") }
                        persist(messages)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (generation == requestId) {
                        busy = false
                        streamJob = null
                        messages = messages.filterNot { it.role == "assistant" && it.content.isBlank() }
                        persist(messages)
                    }
                }
            }
        }
    }

    val visionSupported = remember(model, revision) { supportsVision(model) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { showPicker = true },
                color = NineRouterColors.SurfaceElevated,
                shape = NineRouterShapes.medium,
                border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (running) PulsingDot(NineRouterColors.Success, size = 7.dp)
                    else StatusDot(NineRouterColors.TextSubtle)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MODEL", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
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
                        contentDescription = t("Choose model"),
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = { showSessions = true },
                color = NineRouterColors.SurfaceElevated,
                shape = NineRouterShapes.medium,
                border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                modifier = Modifier.size(44.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.List,
                        contentDescription = t("Chat sessions"),
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Surface(
                onClick = { newChat() },
                color = NineRouterColors.SurfaceElevated,
                shape = NineRouterShapes.medium,
                border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                modifier = Modifier.size(44.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = t("New chat"),
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChatEmptyState(shown = introShown) { prompt -> input = prompt }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }, contentType = { it.role }) { m ->
                    val isLast = m.id == messages.lastOrNull()?.id
                    val streamingNow = busy && isLast && m.role == "assistant"
                    MessageBubble(
                        message = m,
                        thinking = streamingNow && m.content.isBlank(),
                        streaming = streamingNow,
                        liveElapsedMs = liveElapsed,
                        onCopy = {
                            clipboard.setText(AnnotatedString(m.content))
                            Toast.makeText(context, tr("Copied"), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        if (pendingImages.isNotEmpty() && visionSupported == false) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                NoticeBanner(
                    text = "This model is not marked as vision-capable. Pick an image model or the request may fail.",
                    color = NineRouterColors.Warning,
                )
            }
        }

        Surface(
            color = NineRouterColors.SurfaceElevated,
            shape = NineRouterShapes.large,
            border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        ) {
            Column {
                if (pendingImages.isNotEmpty() || imageBusy) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pendingImages.forEach { image ->
                            Box {
                                ChatThumbnail(image, 58.dp)
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(18.dp)
                                        .background(NineRouterColors.Bg.copy(alpha = 0.82f), CircleShape)
                                        .clickable { pendingImages = pendingImages - image },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = t("Remove image"),
                                        tint = NineRouterColors.TextMain,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                        }
                        if (imageBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = NineRouterColors.BrandOrange,
                            )
                        }
                    }
                }
                Row(
                    Modifier.padding(start = 4.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = pendingImages.size < 4 && !imageBusy,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_attach),
                            contentDescription = t("Attach image"),
                            tint = NineRouterColors.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NineRouterColors.TextMain),
                        cursorBrush = SolidColor(NineRouterColors.BrandOrange),
                        maxLines = 5,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp)
                            .semantics { contentDescription = tr("Message") },
                        decorationBox = { inner ->
                            Box {
                                if (input.isBlank()) {
                                    Text(
                                        "Message ${model.substringAfter('/').ifBlank { "…" }}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NineRouterColors.TextSubtle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    val canSend = (input.isNotBlank() || pendingImages.isNotEmpty()) && model.isNotBlank()
                    val sendColor by animateColorAsState(
                        targetValue = when {
                            busy -> NineRouterColors.Danger
                            canSend -> NineRouterColors.BrandOrange
                            else -> NineRouterColors.Surface3
                        },
                        animationSpec = tween(200),
                        label = "sendColor",
                    )
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(sendColor, CircleShape)
                            .clickable(enabled = busy || canSend) { if (busy) stop() else send() }
                            .semantics(mergeDescendants = true) {
                                contentDescription = if (busy) "Stop generating" else "Send message"
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            Box(Modifier.size(11.dp).background(NineRouterColors.Bg, RoundedCornerShape(3.dp)))
                        } else {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = null,
                                tint = if (canSend) NineRouterColors.Bg else NineRouterColors.TextSubtle,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            title = "Choose a model",
            models = options,
            selected = listOfNotNull(model.takeIf { it.isNotBlank() }),
            multi = false,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                picked.firstOrNull()?.let {
                    model = it
                    currentSession()?.model = it
                    Store.save()
                }
                showPicker = false
            },
        )
    }

    if (showSessions) {
        ChatSessionsDialog(
            sessions = Store.state.chatSessions.toList(),
            activeId = sessionId,
            onPick = { id -> stop(); loadSession(id); showSessions = false },
            onNew = { newChat(); showSessions = false },
            onDelete = { id -> deleteSession(id) },
            onDismiss = { showSessions = false },
        )
    }
}

@Composable
private fun ChatSessionsDialog(
    sessions: List<ChatSession>,
    activeId: String,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val now by liveNow(30_000L)
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Chat sessions", color = NineRouterColors.TextMain) },
        text = {
            if (sessions.isEmpty()) {
                Text("No sessions yet. Start a new chat.", color = NineRouterColors.TextMuted)
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val selected = session.id == activeId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.12f) else NineRouterColors.Surface2.copy(alpha = 0.4f))
                                .clickable { onPick(session.id) }
                                .padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) NineRouterColors.BrandOrange else NineRouterColors.TextMain,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${session.messages.size} messages · ${sessionAgo(session.updatedAt, now)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NineRouterColors.TextSubtle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { pendingDelete = session.id },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = t("Delete session"),
                                    tint = NineRouterColors.TextSubtle,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Text("New chat", color = NineRouterColors.BrandOrange) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = NineRouterColors.TextMuted) }
        },
    )

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = NineRouterColors.Surface,
            title = { Text("Delete this chat?", color = NineRouterColors.TextMain) },
            text = { Text("The whole transcript and its images will be removed.", color = NineRouterColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    pendingDelete = null
                }) { Text("Delete", color = NineRouterColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChatThumbnail(image: ChatImage, size: Dp) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, image.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(image.path)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(NineRouterColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = image.name.ifBlank { "Attached image" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("IMG", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
        }
    }
}

private fun sessionAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - timestamp).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> "${diff / 86_400_000L}d ago"
    }
}

private fun supportsVision(model: String): Boolean? {
    val target = ModelResolver.resolve(model) ?: return null
    val def = Catalog.find(target.provider) ?: return null
    val caps = def.modelCapabilities[target.model]
        ?: def.modelCapabilities[ModelResolver.upstreamModel(target.provider, target.model)]
        ?: return null
    return caps.any { it.equals("image", true) || it.equals("vision", true) }
}

private fun chatMessageJson(message: UiMessage): JsonObject = buildJsonObject {
    put("role", message.role)
    if (message.images.isEmpty()) {
        put("content", message.content)
    } else {
        put("content", buildJsonArray {
            if (message.content.isNotBlank()) {
                add(buildJsonObject { put("type", "text"); put("text", message.content) })
            }
            message.images.forEach { image ->
                val bytes = runCatching { File(image.path).readBytes() }.getOrNull() ?: return@forEach
                val url = "data:${image.mime};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", url) })
                })
            }
        })
    }
}

private suspend fun saveChatImage(context: android.content.Context, uri: Uri): ChatImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            // Read the bounds first and downsample, so a full-resolution phone
            // photo cannot allocate tens of megabytes.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@withContext null
            var sample = 1
            while (longest / sample > 2560) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?: return@withContext null
            val maxSide = 1280
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 0.99f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val dir = File(context.filesDir, "chat").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
            ChatImage(path = file.absolutePath, name = "image.jpg", mime = "image/jpeg")
        }.getOrNull()
    }

private data class ChatIdea(val title: String, val prompt: String)

@Composable
private fun ChatEmptyState(shown: Boolean, onPick: (String) -> Unit) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(320)) +
            slideInVertically(tween(360)) { it / 6 } +
            scaleIn(tween(360), initialScale = 0.96f),
    ) {
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
            ChatIdeas(onPick = onPick)
        }
    }
}

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
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ideas.chunked(2).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { colIndex, idea ->
                    IdeaCard(
                        idea = idea,
                        shown = shown,
                        delay = 120 + (rowIndex * 2 + colIndex) * 70,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(idea.prompt) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IdeaCard(
    idea: ChatIdea,
    shown: Boolean,
    delay: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(280, delayMillis = delay)) +
            slideInVertically(tween(320, delayMillis = delay)) { it / 3 },
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            color = NineRouterColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, NineRouterColors.Border),
            modifier = Modifier.fillMaxWidth(),
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
}

@Composable
private fun MessageBubble(
    message: UiMessage,
    thinking: Boolean,
    streaming: Boolean,
    liveElapsedMs: Long,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == "user"
    val accent = if (isUser) NineRouterColors.BrandOrange else NineRouterColors.Success
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1300)
            copied = false
        }
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) NineRouterColors.BrandOrange.copy(alpha = 0.14f) else NineRouterColors.Surface,
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .then(
                    if (streaming) Modifier
                    else Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (streaming) PulsingDot(accent, size = 6.dp) else StatusDot(accent, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isUser) "YOU" else message.model.ifBlank { "assistant" },
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                        translate = isUser,
                    )
                }
                Spacer(Modifier.height(5.dp))
                if (message.images.isNotEmpty()) {
                    Row(
                        Modifier.padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.images.take(4).forEach { image -> ChatThumbnail(image, 68.dp) }
                    }
                }
                if (thinking && message.content.isBlank()) {
                    TypingDots()
                } else if (message.content.isNotBlank()) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NineRouterColors.TextMain,
                        translate = false,
                    )
                }
                val hasStats = !isUser && !thinking && (message.inputTokens > 0 || message.elapsedMs > 0L)
                AnimatedVisibility(
                    visible = hasStats || (streaming && !thinking),
                    enter = fadeIn(tween(220)) + expandVertically(tween(240)),
                    exit = shrinkVertically(tween(160)) + fadeOut(tween(140)),
                ) {
                    Row(
                        Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (streaming) {
                            val liveTokens = message.content.length / 4
                            val liveRate = if (liveElapsedMs > 0L) liveTokens * 1000f / liveElapsedMs else 0f
                            Text(
                                "streaming · ~$liveTokens tok · ${rate(liveRate)} tok/s · ${seconds(liveElapsedMs)}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextSubtle,
                            )
                        } else {
                            val cost = remember(message.id, message.model, message.inputTokens, message.outputTokens) {
                                Pricing.cost(message.model, message.inputTokens, message.outputTokens)
                            }
                            Text(
                                "in ~${message.inputTokens} · out ~${message.outputTokens} · " +
                                    "${rate(message.tokensPerSecond)} tok/s · ${seconds(message.elapsedMs)}s" +
                                    if (cost > 0.0) " · ${costText(cost)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextSubtle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { onCopy(); copied = true },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Crossfade(copied, animationSpec = tween(150), label = "messageCopy") { done ->
                                    Icon(
                                        if (done) Icons.Default.Check else CopyIcon,
                                        contentDescription = if (done) "Copied" else "Copy message",
                                        tint = if (done) NineRouterColors.Success else NineRouterColors.TextSubtle,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.1f", ms / 1000f)

private fun rate(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)

private fun costText(value: Double): String =
    if (value >= 0.01) "$" + String.format(java.util.Locale.US, "%.3f", value)
    else "$" + String.format(java.util.Locale.US, "%.4f", value)

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

