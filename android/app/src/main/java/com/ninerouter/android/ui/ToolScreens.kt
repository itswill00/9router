package com.ninerouter.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.R
import com.ninerouter.android.core.Formats
import com.ninerouter.android.core.MediaEngine
import com.ninerouter.android.core.Translator
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Connection
import com.ninerouter.android.data.Settings
import com.ninerouter.android.data.Exposure
import com.ninerouter.android.data.Store
import com.ninerouter.android.data.UsageEntry
import com.ninerouter.android.server.GatewayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.NumberFormat
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Shared tool-page helpers
// ---------------------------------------------------------------------------

@Composable
private fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NineRouterColors.SurfaceSunken,
        shape = NineRouterShapes.small,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = NineRouterColors.TextMain,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun CopyChip(value: String, label: String = "Copy", modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    TextButton(
        onClick = {
            copySensitive(context, "Value", value)
            copied = true
            Toast.makeText(context, tr("Copied"), Toast.LENGTH_SHORT).show()
        },
        modifier = modifier,
    ) {
        Icon(
            if (copied) Icons.Default.Check else CopyIcon,
            contentDescription = null,
            tint = if (copied) NineRouterColors.Success else NineRouterColors.TextMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (copied) "Copied" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (copied) NineRouterColors.Success else NineRouterColors.TextMuted,
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, copyValue: String = value) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
            Spacer(Modifier.height(1.dp))
            MonoText(value)
        }
        CopyChip(copyValue, label = "Copy")
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, tr("No browser available"), Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------------------
// Quota Tracker
// ---------------------------------------------------------------------------

@Composable
fun QuotaScreen(refresh: () -> Unit, onOpenProviders: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val revision = storeRevision().value
    val now by liveNow(1_000L)
    val connections = Store.state.connections
    val weekAgo = now - 7L * 24 * 60 * 60 * 1000
    val weekUsage = Store.state.usage.filter { it.ts >= weekAgo }

    val available = connections.count { it.enabled && it.unavailableUntil <= now }
    val cooling = connections.count { it.enabled && it.unavailableUntil > now }
    val off = connections.count { !it.enabled }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Account availability",
                            style = MaterialTheme.typography.titleSmall,
                            color = NineRouterColors.TextMain,
                            modifier = Modifier.weight(1f),
                        )
                        if (connections.isNotEmpty()) {
                            TextButton(onClick = onOpenProviders) {
                                Text("Providers", color = NineRouterColors.BrandOrange)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuotaMetric("Available", available.toString(), NineRouterColors.Success, Modifier.weight(1f))
                        QuotaMetric("Cooling", cooling.toString(), NineRouterColors.Warning, Modifier.weight(1f))
                        QuotaMetric("Turned off", off.toString(), NineRouterColors.TextMuted, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Quota windows sync from the full 9Router dashboard; this build tracks availability, cooldowns and local usage.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            }

            if (connections.isEmpty()) {
                item {
                    Panel {
                        EmptyState(
                            title = "No providers connected",
                            body = "Connect a provider to track account availability and quota windows.",
                            action = {
                                SecondaryButton("Open Providers", onClick = onOpenProviders)
                            },
                        )
                    }
                }
            } else {
                items(connections, key = { it.id }, contentType = { "quota-account" }) { conn ->
                    val def = Catalog.find(conn.provider)
                    val providerName = def?.name ?: conn.provider
                    val entries = weekUsage.filter { it.provider == conn.provider }
                    QuotaAccountCard(
                        conn = conn,
                        providerName = providerName,
                        entries = entries,
                        now = now,
                        modifier = Modifier.animateItem(),
                        onToggle = { enabled ->
                            conn.enabled = enabled
                            Store.save()
                            refresh()
                        },
                    )
                }
            }

            item {
                Panel {
                    Text("About this page", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A cooling account is skipped until its cooldown ends. Turned-off accounts stay configured but receive no traffic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            }
    }
}

@Composable
private fun QuotaMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = NineRouterColors.Surface2,
        shape = NineRouterShapes.medium,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun QuotaAccountCard(
    conn: Connection,
    providerName: String,
    entries: List<UsageEntry>,
    now: Long,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    val cooling = conn.enabled && conn.unavailableUntil > now
    val requests = entries.size
    val tokens = entries.sumOf { it.prompt + it.completion }
    val cost = entries.sumOf { it.cost }
    val subtitle = buildList {
        if (conn.name.isNotBlank() && conn.name != providerName) add(providerName)
        if (conn.email.isNotBlank() && conn.email != conn.name) add(conn.email)
        add(if (conn.authType == "oauth") "OAuth" else "API key")
    }.joinToString(" · ")

    Panel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderIcon(conn.provider, providerName, size = 36.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    conn.name.ifBlank { providerName },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineRouterColors.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                !conn.enabled -> Pill("Turned off", NineRouterColors.TextMuted, NineRouterColors.Surface2)
                cooling -> Pill("Cooling down", NineRouterColors.Warning, NineRouterColors.Warning.copy(alpha = 0.12f))
                else -> Pill("Available", NineRouterColors.Success, NineRouterColors.Success.copy(alpha = 0.12f))
            }
        }

        Spacer(Modifier.height(8.dp))
        DividerLine()
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuotaStat("Requests 7d", quotaFmt(requests), Modifier.weight(1f))
            QuotaStat("Tokens 7d", quotaFmt(tokens), Modifier.weight(1f))
            QuotaStat("Est. cost", quotaMoney(cost), Modifier.weight(1f))
        }

        if (cooling) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Available again in ${quotaRemaining(conn.unavailableUntil, now)}.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.Warning,
            )
        }
        if (conn.lastError.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                conn.lastError,
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.Danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Use this account",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = conn.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun QuotaStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle, maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = NineRouterColors.TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun quotaRemaining(until: Long, now: Long): String {
    val diff = (until - now).coerceAtLeast(0)
    val minutes = (diff / 60_000).coerceAtLeast(1)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / (60 * 24)}d ${(minutes % (60 * 24)) / 60}h"
    }
}

private val quotaNumberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
private fun quotaFmt(n: Int): String = quotaNumberFormat.format(n)
private fun quotaFmt(n: Long): String = quotaNumberFormat.format(n)
private fun quotaMoney(n: Double): String = "$" + String.format(Locale.US, "%.2f", n)

// ---------------------------------------------------------------------------
// Token Saver
// ---------------------------------------------------------------------------

@Composable
fun TokenSaverScreen(refresh: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val revision = storeRevision().value
    val s = Store.state.settings
    fun save(mutator: Settings.() -> Settings) {
        Store.mutate { settings = settings.mutator() }
        refresh()
    }
    val active = listOf(s.rtkEnabled, s.cavemanEnabled, s.ponytailEnabled, s.customPromptEnabled).count { it }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Compression",
                        style = MaterialTheme.typography.titleSmall,
                        color = NineRouterColors.TextMain,
                        modifier = Modifier.weight(1f),
                    )
                    Pill("$active / 4 active")
                }
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    "RTK compression",
                    "Compresses tool results in place. Fails open on errors.",
                    s.rtkEnabled,
                ) { value -> save { copy(rtkEnabled = value) } }
                DividerLine(Modifier.padding(vertical = 8.dp))
                ToggleRow(
                    "Caveman prompt",
                    "Adds terse-output instructions to the system prompt.",
                    s.cavemanEnabled,
                    trailing = if (s.cavemanEnabled) {
                        {
                            OutlinedTextField(
                                s.cavemanLevel,
                                { value -> save { copy(cavemanLevel = value) } },
                                singleLine = true,
                                modifier = Modifier.width(76.dp),
                            )
                        }
                    } else null,
                ) { value -> save { copy(cavemanEnabled = value) } }
                AnimatedVisibility(
                    visible = s.cavemanEnabled,
                    enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(160)),
                ) {
                    Text(
                        "Level (for example 1–3). Higher means shorter answers.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextSubtle,
                    )
                }
                DividerLine(Modifier.padding(vertical = 8.dp))
                ToggleRow(
                    "Ponytail prompt",
                    "Adds the Ponytail minimalism prompt.",
                    s.ponytailEnabled,
                    trailing = if (s.ponytailEnabled) {
                        {
                            OutlinedTextField(
                                s.ponytailLevel,
                                { value -> save { copy(ponytailLevel = value) } },
                                singleLine = true,
                                modifier = Modifier.width(76.dp),
                            )
                        }
                    } else null,
                ) { value -> save { copy(ponytailEnabled = value) } }
                DividerLine(Modifier.padding(vertical = 8.dp))
                ToggleRow(
                    "Custom system prompt",
                    "Prepend your own instructions to every request.",
                    s.customPromptEnabled,
                ) { value -> save { copy(customPromptEnabled = value) } }
                AnimatedVisibility(
                    visible = s.customPromptEnabled,
                    enter = fadeIn(tween(200)) + expandVertically(tween(240)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            s.customPrompt,
                            { value -> save { copy(customPrompt = value) } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("System prompt") },
                            minLines = 3,
                        )
                    }
                }
            }
        }
        item {
            Panel {
                Text("How it works", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Compression runs before the request leaves the device, so upstream providers receive a smaller prompt. Errors are ignored and the original request is forwarded unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CLI Tools
// ---------------------------------------------------------------------------

private data class CliTool(val name: String, val description: String, val snippet: String)

private fun cliTools(base: String): List<CliTool> = listOf(
    CliTool(
        "Claude Code",
        "Anthropic Claude Code CLI",
        """
export ANTHROPIC_BASE_URL=$base
export ANTHROPIC_AUTH_TOKEN=<API key>
export ANTHROPIC_MODEL=openai/gpt-5-mini
        """.trimIndent(),
    ),
    CliTool(
        "OpenAI Codex CLI",
        "OpenAI Codex CLI / app",
        """
export OPENAI_BASE_URL=$base/v1
export OPENAI_API_KEY=<API key>
        """.trimIndent(),
    ),
    CliTool(
        "Cline / Roo Code",
        "VS Code OpenAI-compatible provider",
        """
Provider: OpenAI Compatible
Base URL: $base/v1
API Key:  <API key>
Model:    openai/gpt-5-mini
        """.trimIndent(),
    ),
    CliTool(
        "Cursor",
        "Override the OpenAI endpoint in Cursor settings",
        """
Override OpenAI Base URL: $base/v1
API Key: <API key>
        """.trimIndent(),
    ),
    CliTool(
        "Continue",
        "Continue.dev config.yaml provider block",
        """
models:
  - provider: openai
    model: openai/gpt-5-mini
    apiBase: $base/v1
    apiKey: <API key>
        """.trimIndent(),
    ),
    CliTool(
        "OpenCode",
        "OpenCode custom OpenAI-compatible provider",
        """
{
  "provider": {
    "9router": {
      "npm": "@ai-sdk/openai-compatible",
      "options": {
        "baseURL": "$base/v1",
        "apiKey": "<API key>"
      },
      "models": { "openai/gpt-5-mini": {} }
    }
  }
}
        """.trimIndent(),
    ),
)

@Composable
fun CliToolsScreen() {
    @Suppress("UNUSED_VARIABLE")
    val revision = storeRevision().value
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val settings = Store.state.settings
    val base = Exposure.publicRoot(settings) ?: GatewayState.BASE_URL
    val apiRoot = Exposure.apiRoot(settings, GatewayState.BASE_URL)
    val apiKey = settings.apiKey
    val tools = cliTools(apiRoot)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gateway endpoint", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                    Spacer(Modifier.weight(1f))
                    Pill(if (settings.requireApiKey || Exposure.enabled(settings)) "key required" else "open")
                }
                Spacer(Modifier.height(6.dp))
                KeyValueRow("OpenAI base URL", apiRoot)
                Spacer(Modifier.height(4.dp))
                KeyValueRow("Anthropic base URL", base)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("API key", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                        Spacer(Modifier.height(1.dp))
                        MonoText(if (apiKey.isBlank()) "(gateway has no key)" else apiKey.take(10) + "…")
                    }
                    TextButton(onClick = {
                        copySensitive(context, "API key", apiKey)
                        Toast.makeText(context, tr("API key copied"), Toast.LENGTH_SHORT).show()
                    }) { Text("Copy key", color = NineRouterColors.TextMuted) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Replace <API key> in the snippets below. Model ids use the provider/model form, for example openai/gpt-5-mini.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                )
            }
        }
        itemsIndexed(tools) { index, tool ->
            CliToolCard(tool, startOpen = index == 0)
        }
        item {
            Panel {
                Text("Using a computer", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (Exposure.publicRoot(settings) != null) {
                        "Open that base URL from any device that can reach your tunnel or reverse proxy. Terminate TLS there; the last hop to the phone is private."
                    } else {
                        "The gateway listens on loopback by default. On a connected computer run adb reverse tcp:${GatewayState.PORT} tcp:${GatewayState.PORT}, then use the same localhost URLs shown above."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun CliToolCard(tool: CliTool, startOpen: Boolean) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "cliChevron")
    Panel {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
                Text(tool.description, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
            }
            CopyChip(tool.snippet)
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = NineRouterColors.TextSubtle,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                CodeBlock(tool.snippet)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Media Providers
// ---------------------------------------------------------------------------

enum class MediaKind(
    val label: String,
    val description: String,
    val serviceKinds: List<String>,
    val method: String,
    val path: String,
    val probePath: String,
    val icon: Int,
    val supported: Boolean,
) {
    Embedding(
        "Embedding", "Vectors for RAG and semantic search.",
        listOf("embedding"), "POST", "/v1/embeddings", "/v1/embeddings", R.drawable.ic_nav_embedding, true,
    ),
    TextToImage(
        "Text to Image", "Generate images from text prompts.",
        listOf("image"), "POST", "/v1/images/generations", "/v1/images/generations", R.drawable.ic_nav_image, true,
    ),
    Video(
        "Video", "Generate short videos from text prompts.",
        listOf("video"), "POST", "/v1/videos/generations", "/v1/videos/generations", R.drawable.ic_nav_video, false,
    ),
    TextToSpeech(
        "Text To Speech", "Turn text into spoken audio.",
        listOf("tts"), "POST", "/v1/audio/speech", "/v1/audio/speech", R.drawable.ic_nav_tts, true,
    ),
    SpeechToText(
        "Speech To Text", "Transcribe audio into text.",
        listOf("stt"), "POST", "/v1/audio/transcriptions", "/v1/audio/transcriptions", R.drawable.ic_nav_stt, true,
    ),
    SystemOne(
        "System One", "Route system-level agent requests.",
        listOf("systemone"), "POST", "/v1/systemone", "/v1/systemone", R.drawable.ic_nav_systemone, false,
    ),
    Web(
        "Web Fetch & Search", "Search the web and fetch pages as text.",
        listOf("webSearch", "webFetch"), "POST", "/v1/search · /v1/web/fetch", "/v1/search", R.drawable.ic_nav_web, true,
    ),
}

private fun MediaKind.engineKind(): String = when (this) {
    MediaKind.TextToImage -> "image"
    MediaKind.TextToSpeech -> "tts"
    MediaKind.SpeechToText -> "stt"
    MediaKind.Web -> "search"
    MediaKind.Embedding -> "embedding"
    MediaKind.Video -> "video"
    MediaKind.SystemOne -> "systemone"
}

private fun mediaDefaultModel(kind: MediaKind, mode: String): String {
    val engine = if (kind == MediaKind.Web) mode else kind.engineKind()
    val connected = Catalog.providers().filter { def ->
        MediaEngine.supports(def, engine) &&
            Store.state.connections.any { it.enabled && it.provider == def.id }
    }
    val pool = connected.ifEmpty { Catalog.providers().filter { MediaEngine.supports(it, engine) } }
    return when (engine) {
        "image" -> {
            val openai = pool.firstOrNull { it.id == "openai" }
            if (openai != null) "openai/gpt-image-1"
            else {
                val image = Regex("dall-e|gpt-image|flux|stable-diffusion|sdxl|imagen|seedream|nano-banana|recraft|grok-2-image|image")
                pool.firstOrNull()?.let { def ->
                    def.models.firstOrNull { image.containsMatchIn(it.lowercase()) }
                        ?.let { "${def.alias}/$it" }
                } ?: "openai/gpt-image-1"
            }
        }
        "tts" -> when {
            pool.any { it.id == "openai" } -> "openai/tts-1"
            pool.any { it.id == "elevenlabs" } -> "el/21m00Tcm4TlvDq8ikWAM"
            else -> "openai/tts-1"
        }
        "stt" -> pool.firstOrNull { it.id == "openai" }?.let { "openai/whisper-1" }
            ?: pool.firstOrNull()?.let { def ->
                def.models.firstOrNull { it.contains("whisper", true) || it.contains("scribe", true) }
                    ?.let { "${def.alias}/$it" }
            } ?: "openai/whisper-1"
        "search" -> pool.firstOrNull()?.let { "${it.alias}/search" } ?: "tavily/search"
        "fetch" -> pool.firstOrNull()?.let { "${it.alias}/fetch" } ?: "jina-reader/fetch"
        "embedding" -> pool.firstOrNull()?.let { def ->
            def.models.firstOrNull { it.contains("embed", true) }?.let { "${def.alias}/$it" }
        } ?: "openai/text-embedding-3-small"
        else -> ""
    }
}

private fun mediaDefaultBody(kind: MediaKind, mode: String): String {
    val model = mediaDefaultModel(kind, mode)
    return when (kind) {
        MediaKind.TextToImage ->
            """{"model":"$model","prompt":"A red fox in fresh snow, cinematic light"}"""
        MediaKind.TextToSpeech ->
            """{"model":"$model","input":"Hello from 9Router on Android"}"""
        MediaKind.Web ->
            if (mode == "fetch") {
                """{"model":"$model","url":"https://example.com","format":"markdown"}"""
            } else {
                """{"model":"$model","query":"9Router open source","max_results":3}"""
            }
        MediaKind.Embedding ->
            """{"model":"$model","input":"Hello from 9Router"}"""
        else -> "{}"
    }
}

private data class MediaTestResult(
    val status: Int,
    val contentType: String,
    val text: String,
    val bitmap: Bitmap? = null,
    val audio: File? = null,
)

private val prettyJson = Json { prettyPrint = true }
private val mediaTestClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()

private fun runMediaTest(path: String, body: String, kind: MediaKind): MediaTestResult {
    val request = Request.Builder()
        .url(GatewayState.BASE_URL + path)
        .header("Content-Type", "application/json")
        .apply {
            val key = Store.state.settings.apiKey
            if (key.isNotBlank()) header("Authorization", "Bearer $key")
        }
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    mediaTestClient.newCall(request).execute().use { response ->
        val bytes = response.body?.bytes() ?: ByteArray(0)
        val contentType = response.header("Content-Type").orEmpty()
        val text = runCatching {
            prettyJson.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)),
            )
        }.getOrDefault(bytes.toString(Charsets.UTF_8).take(12_000))

        var bitmap: Bitmap? = null
        if (kind == MediaKind.TextToImage) {
            val b64 = runCatching {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["data"]
                    ?.jsonArray?.firstOrNull()?.jsonObject?.get("b64_json")?.toString()?.trim('"')
            }.getOrNull()
            if (!b64.isNullOrBlank()) {
                val imageBytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                if (imageBytes != null) bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            }
        }

        var audio: File? = null
        if (kind == MediaKind.TextToSpeech && contentType.startsWith("audio/", true) && bytes.isNotEmpty()) {
            val ext = when {
                contentType.contains("mpeg", true) || contentType.contains("mp3", true) -> "mp3"
                contentType.contains("wav", true) -> "wav"
                contentType.contains("ogg", true) -> "ogg"
                else -> "bin"
            }
            val file = File.createTempFile("9router-media-", ".$ext")
            file.writeBytes(bytes)
            audio = file
        }

        return MediaTestResult(response.code, contentType.ifBlank { "application/json" }, text, bitmap, audio)
    }
}

private data class PickedAudio(val name: String, val mime: String, val bytes: ByteArray)

private fun readPickedAudio(context: Context, uri: Uri): PickedAudio? {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { "audio" }
    val mime = resolver.getType(uri).orEmpty().ifBlank { "audio/mpeg" }
    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: return null
    return PickedAudio(name, mime, bytes)
}

private fun runSttTest(model: String, audio: PickedAudio): MediaTestResult {
    val boundary = "----9router" + UUID.randomUUID().toString().replace("-", "")
    val out = ByteArrayOutputStream()
    fun line(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
    line("--$boundary\r\n")
    line("Content-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n")
    line("--$boundary\r\n")
    line("Content-Disposition: form-data; name=\"file\"; filename=\"${audio.name.replace("\"", "")}\"\r\n")
    line("Content-Type: ${audio.mime}\r\n\r\n")
    out.write(audio.bytes)
    line("\r\n--$boundary--\r\n")
    val multipart = out.toByteArray()
    val contentType = "multipart/form-data; boundary=$boundary"

    val request = Request.Builder()
        .url(GatewayState.BASE_URL + "/v1/audio/transcriptions")
        .header("Content-Type", contentType)
        .apply {
            val key = Store.state.settings.apiKey
            if (key.isNotBlank()) header("Authorization", "Bearer $key")
        }
        .post(multipart.toRequestBody(contentType.toMediaType()))
        .build()

    mediaTestClient.newCall(request).execute().use { response ->
        val bytes = response.body?.bytes() ?: ByteArray(0)
        val replyType = response.header("Content-Type").orEmpty()
        val text = runCatching {
            prettyJson.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)),
            )
        }.getOrDefault(bytes.toString(Charsets.UTF_8).take(12_000))
        return MediaTestResult(response.code, replyType.ifBlank { "application/json" }, text)
    }
}

@Composable
private fun MediaTester(kind: MediaKind) {
    if (!kind.supported) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webMode by rememberSaveable(kind.name) { mutableStateOf("search") }
    var body by rememberSaveable(kind.name) { mutableStateOf(mediaDefaultBody(kind, webMode)) }
    var result by remember(kind) { mutableStateOf<MediaTestResult?>(null) }
    var busy by remember(kind) { mutableStateOf(false) }
    var error by remember(kind) { mutableStateOf("") }
    var player by remember(kind) { mutableStateOf<MediaPlayer?>(null) }
    var sttModel by rememberSaveable(kind.name) { mutableStateOf(mediaDefaultModel(kind, "stt")) }
    var picked by remember(kind) { mutableStateOf<PickedAudio?>(null) }
    val activePlayer by rememberUpdatedState(player)
    DisposableEffect(kind) {
        onDispose { runCatching { activePlayer?.release() } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val audio = withContext(Dispatchers.IO) { readPickedAudio(context, uri) }
                when {
                    audio == null -> error = "Cannot read the selected audio file."
                    audio.bytes.size > 7 * 1024 * 1024 ->
                        error = "Audio is larger than the 7 MiB console limit."
                    else -> {
                        picked = audio
                        error = ""
                        result = null
                    }
                }
            }
        }
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Try it", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
            Spacer(Modifier.weight(1f))
            Pill("local gateway")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (kind == MediaKind.SpeechToText) {
                "Choose an audio file (up to 7 MiB). It is posted as multipart/form-data to the local gateway."
            } else {
                "Edit the request and send it through the gateway on this device."
            },
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextMuted,
        )
        if (kind == MediaKind.Web) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceChip("Search", webMode == "search") {
                    webMode = "search"
                    body = mediaDefaultBody(kind, webMode)
                    result = null
                }
                ChoiceChip("Fetch", webMode == "fetch") {
                    webMode = "fetch"
                    body = mediaDefaultBody(kind, webMode)
                    result = null
                }
            }
        }
        if (kind != MediaKind.SpeechToText) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                body,
                {
                    body = it
                    error = ""
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    if (busy) "Sending…" else "Send",
                    modifier = Modifier.weight(1f),
                    enabled = !busy && body.isNotBlank(),
                ) {
                    busy = true
                    error = ""
                    result = null
                    val path = if (kind == MediaKind.Web) "/v1/$webMode" else kind.probePath
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { runMediaTest(path, body, kind) }
                        }.onSuccess {
                            result = it
                        }.onFailure {
                            error = it.message ?: it.javaClass.simpleName
                        }
                        busy = false
                    }
                }
                SecondaryButton("Reset") {
                    body = mediaDefaultBody(kind, webMode)
                    result = null
                    error = ""
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                sttModel,
                {
                    sttModel = it
                    error = ""
                },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Choose audio", modifier = Modifier.weight(1f)) {
                    picker.launch("audio/*")
                }
                PrimaryButton(
                    if (busy) "Sending…" else "Transcribe",
                    modifier = Modifier.weight(1f),
                    enabled = !busy && picked != null && sttModel.isNotBlank(),
                ) {
                    val audio = picked ?: return@PrimaryButton
                    busy = true
                    error = ""
                    result = null
                    val model = sttModel
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { runSttTest(model, audio) }
                        }.onSuccess {
                            result = it
                        }.onFailure {
                            error = it.message ?: it.javaClass.simpleName
                        }
                        busy = false
                    }
                }
            }
            picked?.let { audio ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "${audio.name} · ${audio.bytes.size / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = error.isNotBlank(),
            enter = fadeIn(tween(150)) + expandVertically(tween(200)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                NoticeBanner(error, NineRouterColors.Danger)
            }
        }
        result?.let { reply ->
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    "HTTP ${reply.status}",
                    if (reply.status in 200..299) NineRouterColors.Success else NineRouterColors.Danger,
                    if (reply.status in 200..299) NineRouterColors.Success.copy(alpha = 0.12f)
                    else NineRouterColors.Danger.copy(alpha = 0.12f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    reply.contentType.substringBefore(';'),
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            reply.bitmap?.let { bitmap ->
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "Generated image",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (reply.audio != null) {
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Play audio", modifier = Modifier.fillMaxWidth()) {
                    runCatching {
                        player?.release()
                        player = MediaPlayer().apply {
                            setDataSource(reply.audio.absolutePath)
                            prepare()
                            start()
                        }
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Cannot play audio", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            CodeBlock(reply.text.take(12_000))
        }
    }
}


@Composable
fun MediaProviderScreen(kind: MediaKind, onOpenProviders: () -> Unit) {
    val revision by storeRevision()
    val providers = remember(kind, revision) {
        val engineKind = if (kind == MediaKind.Web) "search" else kind.engineKind()
        Catalog.providers().filter { def ->
            kind.serviceKinds.any { it in def.serviceKinds } ||
                MediaEngine.supports(def, engineKind) ||
                (kind == MediaKind.Web && MediaEngine.supports(def, "fetch"))
        }
    }
    val readyCount = remember(kind, revision) {
        val engineKind = if (kind == MediaKind.Web) "search" else kind.engineKind()
        providers.count { MediaEngine.supports(it, engineKind) || (kind == MediaKind.Web && MediaEngine.supports(it, "fetch")) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(kind.icon),
                        contentDescription = null,
                        tint = NineRouterColors.BrandOrange,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Gateway route", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                        MonoText("${kind.method} ${kind.path}")
                    }
                    Pill(
                        if (kind.supported) "Available" else "Not proxied",
                        if (kind.supported) NineRouterColors.Success else NineRouterColors.Warning,
                        if (kind.supported) NineRouterColors.Success.copy(alpha = 0.12f)
                        else NineRouterColors.Warning.copy(alpha = 0.12f),
                    )
                }
                if (!kind.supported) {
                    Spacer(Modifier.height(8.dp))
                    NoticeBanner(
                        "This media route is not served by the native gateway yet. The provider list below comes from the bundled registry; use the full 9Router dashboard for this capability.",
                        NineRouterColors.Warning,
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$readyCount provider(s) ready on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextSubtle,
                    )
                }
            }
        }
        if (kind.supported) {
            item { MediaTester(kind) }
        }
        item {
            SectionHeader("Providers (${providers.size})", "From the bundled provider registry")
        }
        if (providers.isEmpty()) {
            item {
                Panel {
                    EmptyState(
                        title = "No providers for this kind",
                        body = "The bundled registry has no provider that advertises ${kind.label.lowercase()} support.",
                    )
                }
            }
        } else {
            items(providers, key = { it.id }, contentType = { "media-provider" }) { def ->
                val count = Store.state.connections.count { it.provider == def.id }
                val engineKind = if (kind == MediaKind.Web) "search" else kind.engineKind()
                val ready = MediaEngine.supports(def, engineKind) ||
                    (kind == MediaKind.Web && MediaEngine.supports(def, "fetch"))
                Panel(modifier = Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderIcon(def.id, def.name, size = 34.dp)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                def.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineRouterColors.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (ready) {
                                    if (count > 0) "${def.id} · ready" else "${def.id} · connect a key"
                                } else {
                                    "${def.id} · no native route yet"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ready) NineRouterColors.TextSubtle else NineRouterColors.TextFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Pill(
                            when {
                                count > 0 -> "$count connected"
                                ready -> "Ready"
                                else -> "Registry"
                            },
                            when {
                                count > 0 -> NineRouterColors.Success
                                ready -> NineRouterColors.BrandOrange
                                else -> NineRouterColors.TextMuted
                            },
                            when {
                                count > 0 -> NineRouterColors.Success.copy(alpha = 0.12f)
                                ready -> NineRouterColors.BrandOrange.copy(alpha = 0.12f)
                                else -> NineRouterColors.Surface2
                            },
                        )
                    }
                }
            }
        }
        item {
            SecondaryButton("Open Providers", modifier = Modifier.fillMaxWidth(), onClick = onOpenProviders)
        }
    }
}

// ---------------------------------------------------------------------------
// Proxy Pools
// ---------------------------------------------------------------------------

@Composable
fun ProxyPoolsScreen() {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Panel {
                EmptyState(
                    title = "No proxy pools",
                    body = "The native gateway dials providers directly over the device network. Proxy pools and rotation are configured in the full 9Router dashboard.",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Skills
// ---------------------------------------------------------------------------

private const val SKILLS_REPO = "https://github.com/decolua/9router"
private const val SKILLS_RAW = "https://raw.githubusercontent.com/decolua/9router/refs/heads/master/skills"
private const val SKILLS_BLOB = "https://github.com/decolua/9router/blob/master/skills"

private data class SkillLink(
    val id: String,
    val name: String,
    val description: String,
    val endpoint: String? = null,
    val isEntry: Boolean = false,
) {
    val rawUrl: String get() = "$SKILLS_RAW/$id/SKILL.md"
    val blobUrl: String get() = "$SKILLS_BLOB/$id/SKILL.md"
}

private val skillLinks = listOf(
    SkillLink(
        "9router", "9Router (Entry)",
        "Setup + index of all capabilities. Start here — covers base URL, auth, model discovery, and links to every capability skill.",
        isEntry = true,
    ),
    SkillLink("9router-chat", "Chat", "Chat / code-gen via OpenAI or Anthropic format with streaming.", "/v1/chat/completions"),
    SkillLink("9router-image", "Image Generation", "Text-to-image via DALL-E, Imagen, FLUX, MiniMax, SDWebUI…", "/v1/images/generations"),
    SkillLink("9router-tts", "Text-to-Speech", "OpenAI / ElevenLabs / Edge / Google / Deepgram voices.", "/v1/audio/speech"),
    SkillLink("9router-stt", "Speech-to-Text", "Transcribe audio via OpenAI Whisper, Groq, Gemini, Deepgram, AssemblyAI…", "/v1/audio/transcriptions"),
    SkillLink("9router-embeddings", "Embeddings", "Vectors for RAG / semantic search via OpenAI, Gemini, Mistral…", "/v1/embeddings"),
    SkillLink("9router-web-search", "Web Search", "Web and X search via Tavily / Exa / Brave / Serper / SearXNG / Google PSE / You.com / Xquik.", "/v1/search"),
    SkillLink("9router-web-fetch", "Web Fetch", "URL → markdown / text / HTML via Firecrawl, Jina, Tavily, Exa.", "/v1/web/fetch"),
)

@Composable
fun SkillsScreen() {
    val context = LocalContext.current
    val entryUrl = skillLinks.first().rawUrl

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Panel {
                Text("Paste this to your AI", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                Spacer(Modifier.height(6.dp))
                CodeBlock("Read this skill and use it: $entryUrl")
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CopyChip(entryUrl, label = "Copy link")
                }
            }
        }
        items(skillLinks, key = { it.id }, contentType = { "skill" }) { skill ->
            Panel(modifier = Modifier.animateItem()) {
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        color = if (skill.isEntry) NineRouterColors.BrandOrange else NineRouterColors.BrandOrange.copy(alpha = 0.14f),
                        shape = NineRouterShapes.small,
                    ) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_nav_skills),
                                contentDescription = null,
                                tint = if (skill.isEntry) NineRouterColors.Bg else NineRouterColors.BrandOrange,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                skill.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = NineRouterColors.TextMain,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (skill.isEntry) {
                                Spacer(Modifier.width(6.dp))
                                Pill(
                                    "START HERE",
                                    NineRouterColors.BrandOrange,
                                    NineRouterColors.BrandOrange.copy(alpha = 0.16f),
                                )
                            }
                        }
                        if (skill.endpoint != null) {
                            Spacer(Modifier.height(3.dp))
                            Pill(skill.endpoint)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                        Spacer(Modifier.height(4.dp))
                        MonoText(skill.rawUrl, color = NineRouterColors.TextSubtle, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CopyChip(skill.rawUrl, label = "Copy")
                            TextButton(onClick = { openUrl(context, skill.blobUrl) }) {
                                Text("GitHub", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
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
                        Text("More on GitHub", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                        Text(
                            "Browse source, README, and examples.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                    }
                    TextButton(onClick = { openUrl(context, "$SKILLS_REPO/tree/master/skills") }) {
                        Text("View", color = NineRouterColors.BrandOrange)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Translator debugger
// ---------------------------------------------------------------------------

private val translatorFormats = listOf(
    "OpenAI" to Formats.OPENAI,
    "Claude" to Formats.CLAUDE,
    "Gemini" to Formats.GEMINI,
    "Responses" to Formats.OPENAI_RESPONSES,
)

private val translatorJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Composable
fun TranslatorScreen() {
    var source by remember { mutableStateOf(Formats.OPENAI) }
    var target by remember { mutableStateOf(Formats.CLAUDE) }
    var model by remember { mutableStateOf("") }
    var body by remember {
        mutableStateOf(
            """
{
  "model": "gpt-5-mini",
  "messages": [
    { "role": "user", "content": "Hello from 9Router" }
  ]
}
            """.trimIndent(),
        )
    }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Panel {
                Text("Request body", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    body,
                    { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 7,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(10.dp))
                Text("Source format", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    translatorFormats.forEach { (label, value) ->
                        ChoiceChip(label, source == value) { source = value }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Target format", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    translatorFormats.forEach { (label, value) ->
                        ChoiceChip(label, target == value) { target = value }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    model,
                    { model = it },
                    label = { Text("Upstream model (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Translate request", modifier = Modifier.fillMaxWidth()) {
                    val parsed = runCatching { translatorJson.parseToJsonElement(body) as JsonObject }.getOrNull()
                    if (parsed == null) {
                        error = "The request body must be a JSON object."
                        result = ""
                        return@PrimaryButton
                    }
                    val modelValue = model.ifBlank {
                        (parsed["model"] as? JsonPrimitive)?.content ?: "model"
                    }
                    runCatching {
                        Translator.requestBody(source, target, parsed, modelValue, wantsStream = false)
                    }.onSuccess {
                        result = translatorJson.encodeToString(it)
                        error = ""
                    }.onFailure {
                        error = it.message ?: "Translation failed"
                        result = ""
                    }
                }
                AnimatedVisibility(
                    visible = error.isNotBlank(),
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(error, NineRouterColors.Danger)
                    }
                }
            }
        }
        if (result.isNotBlank()) {
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Translated request", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                        Spacer(Modifier.weight(1f))
                        CopyChip(result)
                    }
                    Spacer(Modifier.height(6.dp))
                    CodeBlock(result)
                }
            }
        }
        item {
            Panel {
                Text("How this works", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Requests pivot through OpenAI. This previews the upstream body that the router would send for the selected provider format. URL and headers are chosen per provider and are not shown here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            }
        }
    }
}
