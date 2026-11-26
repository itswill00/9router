package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.AppInfo
import com.ninerouter.android.R
import com.ninerouter.android.GatewayService
import com.ninerouter.android.data.ApiKey
import com.ninerouter.android.data.Settings
import com.ninerouter.android.data.Store
import com.ninerouter.android.server.GatewayState
import com.ninerouter.android.update.UpdateManager
import java.util.UUID

@Composable
fun SettingsScreen(
    refresh: () -> Unit,
    onOpenTokenSaver: () -> Unit,
    onOpenCombos: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val revision by storeRevision()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val s = Store.state.settings
    val updateState by UpdateManager.state.collectAsState()
    val running by GatewayState.running.collectAsState()
    var showKeyDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearUsage by remember { mutableStateOf(false) }
    var pendingKeyDelete by remember { mutableStateOf<ApiKey?>(null) }
    var keyName by remember { mutableStateOf("") }
    var keyAllowed by remember { mutableStateOf("") }
    var createdKey by remember { mutableStateOf("") }

    fun save(mutator: Settings.() -> Settings) {
        Store.mutate { settings = settings.mutator() }
        refresh()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Gateway ---------------------------------------------------
            item { SettingsSection("Gateway", "Local API server") }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) PulsingDot(NineRouterColors.Success, size = 8.dp)
                        else StatusDot(NineRouterColors.TextSubtle, size = 9.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (running) "Gateway running" else "Gateway stopped",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (running) NineRouterColors.Success else NineRouterColors.TextMuted,
                            )
                            Text(
                                if (running) "Serving loopback traffic" else "Start it to accept requests",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextSubtle,
                            )
                        }
                        Pill(":${GatewayState.PORT}")
                    }
                    Spacer(Modifier.height(NineRouterSpacing.md))
                    ToggleRow(
                        "Start gateway on launch",
                        "Runs the loopback server in a foreground service.",
                        s.autoStartGateway,
                    ) { value ->
                        save { copy(autoStartGateway = value) }
                        if (value) GatewayService.start(context) else GatewayService.stop(context)
                    }
                    DividerLine(Modifier.padding(vertical = 8.dp))
                    ToggleRow(
                        "Require API key",
                        "Reject /v1 requests without a valid key.",
                        s.requireApiKey,
                    ) { value -> save { copy(requireApiKey = value) } }
                    Spacer(Modifier.height(NineRouterSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(NineRouterSpacing.sm)) {
                        SecondaryButton("Open dashboard", modifier = Modifier.weight(1f)) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GatewayState.BASE_URL)))
                            }.onFailure {
                                Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                            }
                        }
                        SecondaryButton(if (running) "Shutdown" else "Start", modifier = Modifier.weight(1f)) {
                            if (running) GatewayService.stop(context)
                            else GatewayService.start(context)
                            refresh()
                        }
                    }
                    Spacer(Modifier.height(NineRouterSpacing.sm))
                    SecondaryButton("Restart gateway", modifier = Modifier.fillMaxWidth()) {
                        GatewayService.restart(context)
                        Toast.makeText(context, "Restarting gateway…", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }

            // ---- Routing & combos ------------------------------------------
            item { SettingsSection("Routing & combos", "Account selection and rotation") }
            item {
                Panel {
                    Text("Account strategy", style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceChip("Fill first", s.fallbackStrategy == "fill-first") {
                            save { copy(fallbackStrategy = "fill-first") }
                        }
                        ChoiceChip("Round robin", s.fallbackStrategy == "round-robin") {
                            save { copy(fallbackStrategy = "round-robin") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (s.fallbackStrategy == "round-robin") {
                            "Rotates between healthy accounts. The sticky limit keeps one account for consecutive requests."
                        } else {
                            "Uses the first healthy account until it fails, then falls back."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                    AnimatedVisibility(
                        visible = s.fallbackStrategy == "round-robin",
                        enter = fadeIn(tween(200)) + expandVertically(tween(240)),
                        exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
                    ) {
                        Column {
                            DividerLine(Modifier.padding(vertical = 8.dp))
                            StepperRow(
                                title = "Account sticky limit",
                                description = "Requests kept on one account before rotating.",
                                value = s.stickyLimit,
                                min = 1,
                                max = 50,
                                onChange = { value -> save { copy(stickyLimit = value) } },
                            )
                        }
                    }
                    DividerLine(Modifier.padding(vertical = 8.dp))
                    StepperRow(
                        title = "Combo round-robin stickiness",
                        description = "Requests on one combo model before the next member starts.",
                        value = s.comboStickyRoundRobinLimit,
                        min = 1,
                        max = 20,
                        onChange = { value -> save { copy(comboStickyRoundRobinLimit = value) } },
                    )
                    DividerLine(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Combo strategies",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineRouterColors.TextMain,
                            )
                            Text(
                                "${Store.state.combos.size} combos · fallback, round robin, smart, fusion",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMuted,
                            )
                        }
                        TextButton(onClick = onOpenCombos) {
                            Text("Open", color = NineRouterColors.BrandOrange)
                        }
                    }
                    DividerLine(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Token savers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineRouterColors.TextMain,
                            )
                            Text(
                                "${tokenSaverCount(s)} of 4 active · RTK, Caveman, Ponytail, custom prompt",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMuted,
                            )
                        }
                        TextButton(onClick = onOpenTokenSaver) {
                            Text("Open", color = NineRouterColors.BrandOrange)
                        }
                    }
                }
            }

            // ---- Access & API keys -----------------------------------------
            item {
                SettingsSection(
                    "Access & API keys",
                    "Keys accepted by the gateway",
                    action = {
                        TextButton(onClick = { showKeyDialog = true }) {
                            Text("Add key", color = NineRouterColors.BrandOrange)
                        }
                    },
                )
            }
            if (Store.state.apiKeys.isEmpty()) {
                item {
                    Panel {
                        EmptyState(
                            title = "No extra keys",
                            body = "The main API key from Endpoint & Key still works. Add scoped keys so clients can be limited to specific models.",
                        )
                    }
                }
            }
            items(Store.state.apiKeys, key = { it.id }, contentType = { "api-key" }) { key ->
                Panel(modifier = Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = NineRouterColors.BrandOrange.copy(alpha = 0.14f),
                            shape = NineRouterShapes.small,
                        ) {
                            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_nav_key),
                                    contentDescription = null,
                                    tint = NineRouterColors.BrandOrange,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                key.name.ifBlank { "Key ${key.id.take(6)}" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineRouterColors.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (key.allowedModels.isEmpty()) "All models allowed"
                                else "${key.allowedModels.size} models allowed",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMuted,
                            )
                        }
                        Pill(
                            if (key.active) "Active" else "Paused",
                            if (key.active) NineRouterColors.Success else NineRouterColors.TextMuted,
                            if (key.active) NineRouterColors.Success.copy(alpha = 0.12f) else NineRouterColors.Surface2,
                        )
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = key.active,
                            onCheckedChange = { value ->
                                key.active = value
                                Store.save()
                                refresh()
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = NineRouterColors.SurfaceSunken,
                        shape = NineRouterShapes.small,
                        border = androidx.compose.foundation.BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                    ) {
                        MonoText(
                            key.key.take(18) + "…",
                            color = NineRouterColors.TextMuted,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                        )
                    }
                    if (key.allowedModels.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            key.allowedModels.joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextSubtle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(key.key))
                            Toast.makeText(context, "API key copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy key") }
                        TextButton(onClick = { pendingKeyDelete = key }) {
                            Text("Delete", color = NineRouterColors.Danger)
                        }
                    }
                }
            }
            if (createdKey.isNotBlank()) {
                item {
                    Panel {
                        Text("New API key", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.Success)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Copy it now. Only the masked value is shown later.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = NineRouterColors.SurfaceSunken,
                            shape = NineRouterShapes.small,
                            border = androidx.compose.foundation.BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                        ) {
                            MonoText(
                                createdKey,
                                color = NineRouterColors.TextMain,
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(createdKey))
                                Toast.makeText(context, "API key copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                            TextButton(onClick = { createdKey = "" }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // ---- Updates ---------------------------------------------------
            item { SettingsSection("App updates", "GitHub release channel") }
            item { UpdatePanel(updateState) }

            // ---- About & data ----------------------------------------------
            item { SettingsSection("About & data", "Build information and local state") }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "About this app",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineRouterColors.TextMain,
                            )
                            Text(
                                "Project story, developer and links",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMuted,
                            )
                        }
                        TextButton(onClick = onOpenAbout) {
                            Text("Open", color = NineRouterColors.BrandOrange)
                        }
                    }
                    DividerLine(Modifier.padding(vertical = 8.dp))
                    StatusRow("Version", AppInfo.VERSION_NAME)
                    Spacer(Modifier.height(6.dp))
                    StatusRow("Build", AppInfo.VERSION_CODE.toString())
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Credentials are encrypted with the Android Keystore before being written to disk. The gateway only listens on loopback.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            }
            item {
                Panel {
                    Text("Local data", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Usage history", style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
                            Text(
                                "${Store.state.usage.size} requests kept on this device",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMuted,
                            )
                        }
                        TextButton(onClick = { showClearUsage = true }) {
                            Text("Clear", color = NineRouterColors.Danger)
                        }
                    }
                    DividerLine(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Preferences", style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
                            Text(
                                "Gateway, routing, combo and prompt defaults",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMuted,
                            )
                        }
                        TextButton(onClick = { showResetDialog = true }) {
                            Text("Reset", color = NineRouterColors.TextMuted)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Create API key", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        keyName,
                        { keyName = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        keyAllowed,
                        { keyAllowed = it },
                        label = { Text("Allowed models") },
                        placeholder = { Text("comma separated, blank = all") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Leave allowed models blank to permit every model and combo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
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
                    keyName = ""
                    keyAllowed = ""
                    showKeyDialog = false
                    refresh()
                }) { Text("Create", color = NineRouterColors.BrandOrange) }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") } },
        )
    }

    if (pendingKeyDelete != null) {
        val key = pendingKeyDelete!!
        AlertDialog(
            onDismissRequest = { pendingKeyDelete = null },
            containerColor = NineRouterColors.Surface,
            title = { Text("Delete API key?", color = NineRouterColors.TextMain) },
            text = {
                Text(
                    "Delete \"${key.name.ifBlank { key.id.take(8) }}\"? Clients using it will be rejected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.mutate { apiKeys.removeAll { it.id == key.id } }
                    pendingKeyDelete = null
                    refresh()
                }) { Text("Delete", color = NineRouterColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingKeyDelete = null }) { Text("Cancel") } },
        )
    }

    if (showClearUsage) {
        AlertDialog(
            onDismissRequest = { showClearUsage = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Clear usage history?", color = NineRouterColors.TextMain) },
            text = {
                Text(
                    "Usage analytics and Console Log read the same history. Clearing it does not touch credentials, combos or settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.mutate { usage.clear() }
                    showClearUsage = false
                    refresh()
                }) { Text("Clear", color = NineRouterColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { showClearUsage = false }) { Text("Cancel") } },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Reset preferences?", color = NineRouterColors.TextMain) },
            text = {
                Text(
                    "Gateway, routing, token and combo defaults return to their initial values. Providers, connections, combos and API keys are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.mutate {
                        settings = Settings(apiKey = settings.apiKey)
                    }
                    showResetDialog = false
                    refresh()
                }) { Text("Reset", color = NineRouterColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(NineRouterColors.BrandOrange),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NineRouterColors.TextMain,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
            )
        }
        action?.invoke()
    }
}

@Composable
private fun StepperRow(
    title: String,
    description: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
            Text(description, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
        }
        Stepper(value, min, max, onChange)
    }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { if (value > min) onChange(value - 1) },
            enabled = value > min,
            color = NineRouterColors.Surface2,
            shape = NineRouterShapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, NineRouterColors.BorderSubtle),
            modifier = Modifier.size(36.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("−", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
            }
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = NineRouterColors.TextMain,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(34.dp),
        )
        Surface(
            onClick = { if (value < max) onChange(value + 1) },
            enabled = value < max,
            color = NineRouterColors.Surface2,
            shape = NineRouterShapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, NineRouterColors.BorderSubtle),
            modifier = Modifier.size(36.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("+", style = MaterialTheme.typography.titleMedium, color = NineRouterColors.TextMain)
            }
        }
    }
}

private fun tokenSaverCount(s: Settings): Int = listOf(
    s.rtkEnabled,
    s.cavemanEnabled,
    s.ponytailEnabled,
    s.customPromptEnabled,
).count { it }
