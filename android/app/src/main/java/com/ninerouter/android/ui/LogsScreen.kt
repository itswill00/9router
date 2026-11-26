package com.ninerouter.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.data.Store
import com.ninerouter.android.data.UsageEntry
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_FILTERS = listOf("All", "Success", "Failed")

private fun filterLogs(all: List<UsageEntry>, filter: String): List<UsageEntry> = when (filter) {
    "Success" -> all.filter { it.ok }
    "Failed" -> all.filter { !it.ok }
    else -> all
}

@Composable
fun LogsScreen() {
    val revision by liveRevision(2_000L)
    var filter by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf<UsageEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val all = remember(Store.state.usage, revision) { Store.state.usage.toList() }

    val errors = all.count { !it.ok }
    val avgLatency = all.filter { it.latencyMs > 0 }
        .let { if (it.isEmpty()) 0L else it.sumOf { entry -> entry.latencyMs } / it.size }
    val streams = all.count { it.stream }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LogStat("Requests", logFmt(all.size), NineRouterColors.TextMain, Modifier.weight(1f))
                LogStat("Failed", logFmt(errors), if (errors > 0) NineRouterColors.Danger else NineRouterColors.TextMuted, Modifier.weight(1f))
                LogStat("Streams", logFmt(streams), NineRouterColors.Info, Modifier.weight(1f))
                LogStat("Avg", if (avgLatency > 0) "${avgLatency}ms" else "—", NineRouterColors.Success, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LogSegmented(LOG_FILTERS, filter) { filter = it }
                }
                if (all.isNotEmpty()) {
                    PulsingDot(NineRouterColors.Success, size = 5.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("Live", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Success)
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.BrandOrange)
                    }
                }
            }
        }

        AnimatedContent(
            targetState = filter,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(240)) { it / 16 }) togetherWith
                    (fadeOut(tween(130)) + slideOutVertically(tween(160)) { -it / 16 })
            },
            label = "logsList",
        ) { activeFilter ->
            val entries = remember(all, activeFilter) { filterLogs(all, activeFilter) }.asReversed()
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = if (all.isEmpty()) "No requests logged yet" else "No entries match this filter",
                        body = if (all.isEmpty()) {
                            "Send a message from Basic Chat or call the local endpoint. Streaming and non-streaming requests both show up here."
                        } else {
                            "Try the All filter to see every request."
                        },
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(entries, key = { index, entry -> "${entry.ts}-$index" }) { _, entry ->
                        LogRow(entry, modifier = Modifier.animateItem()) { selected = entry }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        LogDetailDialog(entry) { selected = null }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = NineRouterColors.Surface,
            title = { Text("Clear request logs?", color = NineRouterColors.TextMain) },
            text = {
                Text(
                    "This also clears the usage analytics, since both read the same history. Credentials and settings are untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.mutate { usage.clear() }
                    confirmClear = false
                }) { Text("Clear", color = NineRouterColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = NineRouterColors.TextMuted) }
            },
        )
    }
}

@Composable
private fun LogStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.medium,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 }) togetherWith
                        (fadeOut(tween(110)) + slideOutVertically(tween(130)) { -it / 2 })
                },
                label = "logStat",
            ) { text ->
                Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LogSegmented(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(NineRouterColors.Surface2, RoundedCornerShape(9.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(option) },
                color = if (active) NineRouterColors.BrandOrange else Color.Transparent,
                shape = RoundedCornerShape(7.dp),
            ) {
                Text(
                    option,
                    Modifier.padding(vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) NineRouterColors.Bg else NineRouterColors.TextMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LogRow(entry: UsageEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, if (entry.ok) NineRouterColors.BorderSubtle else NineRouterColors.Danger.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (entry.ok) NineRouterColors.Success else NineRouterColors.Danger, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    logClock(entry.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMain,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    logEndpoint(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.stream) {
                    LogBadge("stream", NineRouterColors.Info)
                    Spacer(Modifier.width(4.dp))
                }
                LogBadge(
                    "HTTP ${entry.status}",
                    if (entry.ok) NineRouterColors.TextMuted else NineRouterColors.Danger,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.model.ifBlank { "(unknown model)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${logFmt(entry.prompt)}↑ ${logFmt(entry.completion)}↓",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.provider.ifBlank { "—" } + if (entry.latencyMs > 0) " · ${entry.latencyMs} ms" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!entry.ok && entry.error.isNotBlank()) {
                    Text(
                        entry.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.Danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.4f),
                    )
                } else if (entry.cost > 0) {
                    Text(
                        "$" + String.format(Locale.US, "%.4f", entry.cost),
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextSubtle,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun LogDetailDialog(entry: UsageEntry, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NineRouterColors.Surface,
        title = { Text("Request log", color = NineRouterColors.TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                LogDetailRow("Time", logDateTime(entry.ts))
                LogDetailRow("Endpoint", logEndpoint(entry))
                LogDetailRow("Provider", entry.provider.ifBlank { "—" })
                LogDetailRow("Model", entry.model.ifBlank { "—" })
                LogDetailRow("Status", "${if (entry.ok) "ok" else "failed"} · HTTP ${entry.status}")
                LogDetailRow("Mode", if (entry.stream) "stream" else "non-stream")
                LogDetailRow("Latency", if (entry.latencyMs > 0) "${entry.latencyMs} ms" else "—")
                LogDetailRow("Input", logFmt(entry.prompt))
                LogDetailRow("Cached", logFmt(entry.cached))
                LogDetailRow("Output", logFmt(entry.completion))
                LogDetailRow("Est. cost", "$" + String.format(Locale.US, "%.4f", entry.cost))
                if (entry.error.isNotBlank()) {
                    LogDetailRow("Error", entry.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(logText(entry)))
                copied = true
            }) {
                Text(if (copied) "Copied ✓" else "Copy", color = NineRouterColors.BrandOrange)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = NineRouterColors.TextMuted) }
        },
    )
}

@Composable
private fun LogDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
            modifier = Modifier.width(78.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextMain,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun logEndpoint(entry: UsageEntry): String = when {
    entry.endpoint.isBlank() -> "—"
    entry.endpoint == "chat" -> "Basic Chat"
    else -> entry.endpoint
}

private fun logText(entry: UsageEntry): String = buildString {
    appendLine("9Router request log")
    appendLine("time: ${logDateTime(entry.ts)}")
    appendLine("endpoint: ${logEndpoint(entry)}")
    appendLine("provider: ${entry.provider}")
    appendLine("model: ${entry.model}")
    appendLine("status: ${if (entry.ok) "ok" else "failed"} (HTTP ${entry.status})")
    appendLine("mode: ${if (entry.stream) "stream" else "non-stream"}")
    appendLine("latency: ${entry.latencyMs} ms")
    appendLine("tokens: in ${entry.prompt} / cached ${entry.cached} / out ${entry.completion}")
    append("cost: $${String.format(Locale.US, "%.4f", entry.cost)}")
    if (entry.error.isNotBlank()) append("\nerror: ${entry.error}")
}

private val logNumberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

private fun logFmt(n: Int): String = logNumberFormat.format(n)

private fun logClock(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))

private fun logDateTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
