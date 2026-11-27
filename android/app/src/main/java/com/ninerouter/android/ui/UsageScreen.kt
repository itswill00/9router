package com.ninerouter.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.data.Store
import com.ninerouter.android.data.UsageEntry
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val PERIODS = listOf("Today", "24h", "7D", "30D", "60D", "All")
private val CHART_MODES = listOf("Tokens", "Requests", "Cost")

private data class Bucket(val label: String, val tokens: Long, val requests: Int, val cost: Double) {
    fun value(mode: String): Float = when (mode) {
        "Requests" -> requests.toFloat()
        "Cost" -> cost.toFloat()
        else -> tokens.toFloat()
    }
}

private data class ModelAgg(
    val model: String,
    val requests: Int,
    val prompt: Int,
    val completion: Int,
    val cached: Int,
    val cost: Double,
    val providers: List<Pair<String, Int>>,
) {
    val total: Int get() = prompt + completion
}

@Composable
fun UsageScreen(refresh: () -> Unit) {
    val revision by liveRevision(1_500L)
    var tab by remember { mutableStateOf("Overview") }
    var period by remember { mutableStateOf("Today") }
    var chartMode by remember { mutableStateOf("Tokens") }
    var providerMode by remember { mutableStateOf("Tokens") }
    var modelMode by remember { mutableStateOf("Tokens") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<UsageEntry?>(null) }

    val filtered = remember(Store.state.usage, period, revision) {
        filterPeriod(Store.state.usage, period)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Segmented(listOf("Overview", "Details"), tab) { tab = it }
                }
                Spacer(Modifier.width(8.dp))
                PulsingDot(NineRouterColors.Success, size = 6.dp)
                Spacer(Modifier.width(4.dp))
                Text("Live", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.Success)
            }
            if (tab == "Overview") {
                Spacer(Modifier.height(6.dp))
                PeriodSelector(period) { period = it }
            }
        }

        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val enter = fadeIn(tween(220)) + slideInVertically(tween(240)) { it / 14 }
                val exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { -it / 14 }
                enter togetherWith exit
            },
            label = "usageTab",
        ) { target ->
            if (target == "Details") {
                DetailsList(filtered) { selected = it }
            } else {
                Overview(
                    filtered = filtered,
                    revision = revision,
                    chartMode = chartMode,
                    setChartMode = { chartMode = it },
                    providerMode = providerMode,
                    setProviderMode = { providerMode = it },
                    modelMode = modelMode,
                    setModelMode = { modelMode = it },
                    expanded = expanded,
                    setExpanded = { expanded = it },
                    onSelect = { selected = it },
                )
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = NineRouterColors.Surface,
            title = { Text("Request details", color = NineRouterColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(entry.ts)))
                    DetailRow("Provider", entry.provider)
                    DetailRow("Model", entry.model)
                    DetailRow("Status", "${if (entry.ok) "ok" else "failed"} · HTTP ${entry.status}")
                    if (entry.endpoint.isNotBlank()) DetailRow("Endpoint", entry.endpoint)
                    DetailRow("Mode", if (entry.stream) "stream" else "non-stream")
                    if (entry.latencyMs > 0) DetailRow("Latency", "${entry.latencyMs} ms")
                    DetailRow("Input", fmt(entry.prompt))
                    DetailRow("Cached", fmt(entry.cached))
                    DetailRow("Output", fmt(entry.completion))
                    DetailRow("Est. cost", money(entry.cost))
                    if (entry.error.isNotBlank()) DetailRow("Error", entry.error)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun Overview(
    filtered: List<UsageEntry>,
    revision: Long,
    chartMode: String,
    setChartMode: (String) -> Unit,
    providerMode: String,
    setProviderMode: (String) -> Unit,
    modelMode: String,
    setModelMode: (String) -> Unit,
    expanded: String?,
    setExpanded: (String?) -> Unit,
    onSelect: (UsageEntry) -> Unit,
) {
    val totalRequests = filtered.size
    val input = filtered.sumOf { it.prompt }
    val cached = filtered.sumOf { it.cached }
    val output = filtered.sumOf { it.completion }
    val cost = filtered.sumOf { it.cost }
    val okCount = filtered.count { it.ok }
    val successRate = if (totalRequests > 0) okCount * 100.0 / totalRequests else null
    val successText = when {
        successRate == null -> "—"
        successRate >= 99.95 -> "100%"
        else -> String.format(Locale.US, "%.1f%%", successRate)
    }
    val successColor = when {
        successRate == null -> NineRouterColors.TextMuted
        successRate >= 98.0 -> NineRouterColors.Success
        successRate >= 90.0 -> NineRouterColors.Warning
        else -> NineRouterColors.Danger
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatGrid(
            listOf(
                Stat("Total Requests", fmt(totalRequests), NineRouterColors.TextMain),
                Stat("Total Input Tokens", fmt(input), NineRouterColors.Brand),
                Stat("Cached Tokens", fmt(cached), NineRouterColors.Info),
                Stat("Output Tokens", fmt(output), NineRouterColors.Success),
                Stat("Est. Cost", money(cost), NineRouterColors.Warning),
                Stat("Success Rate", successText, successColor),
            )
        )

        ProviderTopologyCard(usage = Store.state.usage, revision = revision)

        Panel {
            SectionHeader("Recent Requests", action = {
                Text(
                    "${filtered.size} total",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
            })
            Spacer(Modifier.height(6.dp))
            if (filtered.isEmpty()) {
                EmptyState(
                    title = "No requests yet",
                    body = "Traffic through the local gateway shows up here with tokens and cost.",
                )
            } else {
                filtered.takeLast(6).asReversed().forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(r) }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(6.dp).background(
                                if (r.ok) NineRouterColors.Success else NineRouterColors.Danger,
                                RoundedCornerShape(50),
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            r.model,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${fmt(r.prompt)}↑ ${fmt(r.completion)}↓",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(timeAgo(r.ts), style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                    }
                }
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Usage over time", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain, modifier = Modifier.weight(1f))
                SegmentedSmall(CHART_MODES, chartMode, setChartMode)
            }
            Spacer(Modifier.height(6.dp))
            val buckets = buildBuckets(filtered)
            if (buckets.all { it.value(chartMode) <= 0f }) {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text("No data for this period", color = NineRouterColors.TextMuted)
                }
            } else {
                UsageAreaChart(buckets, chartMode)
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("By Provider", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain, modifier = Modifier.weight(1f))
                SegmentedSmall(listOf("Tokens", "Requests"), providerMode, setProviderMode)
            }
            Spacer(Modifier.height(6.dp))
            val providers = filtered.groupBy { it.provider }
                .map { (name, entries) ->
                    name to (if (providerMode == "Requests") entries.size else entries.sumOf { it.prompt + it.completion })
                }
                .sortedByDescending { it.second }
                .take(6)
            if (providers.isEmpty()) {
                Text("No provider usage yet", color = NineRouterColors.TextMuted)
            } else {
                BarList(providers.map { it.first to it.second.toFloat() }, if (providerMode == "Requests") "req" else "tokens")
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Top Models", style = MaterialTheme.typography.titleSmall, color = NineRouterColors.TextMain, modifier = Modifier.weight(1f))
                SegmentedSmall(listOf("Tokens", "Requests"), modelMode, setModelMode)
            }
            Spacer(Modifier.height(6.dp))
            val models = filtered.groupBy { it.model }
                .map { (name, entries) ->
                    name to (if (modelMode == "Requests") entries.size else entries.sumOf { it.prompt + it.completion })
                }
                .sortedByDescending { it.second }
                .take(5)
            if (models.isEmpty()) {
                Text("No model usage yet", color = NineRouterColors.TextMuted)
            } else {
                BarList(models.map { it.first to it.second.toFloat() }, if (modelMode == "Requests") "req" else "tokens", truncate = 18)
            }
        }

        Panel {
            SectionHeader("Usage by Model")
            Spacer(Modifier.height(6.dp))
            val rows = filtered.groupBy { it.model }.map { (model, entries) ->
                ModelAgg(
                    model = model,
                    requests = entries.size,
                    prompt = entries.sumOf { it.prompt },
                    completion = entries.sumOf { it.completion },
                    cached = entries.sumOf { it.cached },
                    cost = entries.sumOf { it.cost },
                    providers = entries.groupBy { it.provider }.map { it.key to it.value.size },
                )
            }.sortedByDescending { it.total }.take(12)
            if (rows.isEmpty()) {
                Text("No usage recorded yet.", color = NineRouterColors.TextMuted)
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("Model", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                    Text("Req", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                    Spacer(Modifier.width(10.dp))
                    Text("In / Out", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                }
                rows.forEach { row ->
                    Column(Modifier.fillMaxWidth().clickable {
                        setExpanded(if (expanded == row.model) null else row.model)
                    }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.model,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(fmt(row.requests), style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${fmt(row.prompt)} / ${fmt(row.completion)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.Brand,
                            )
                        }
                        AnimatedVisibility(
                            visible = expanded == row.model,
                            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                        ) {
                            Column {
                                row.providers.forEach { (p, count) ->
                                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp, bottom = 2.dp)) {
                                        Text(p, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted, modifier = Modifier.weight(1f))
                                        Text("$count req", style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                                    }
                                }
                                Text(
                                    "cached ${fmt(row.cached)} · est. ${money(row.cost)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NineRouterColors.TextSubtle,
                                    modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DetailsList(filtered: List<UsageEntry>, onSelect: (UsageEntry) -> Unit) {
    if (filtered.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "No request details yet",
                body = "Send a request through the gateway to see it listed here.",
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = filtered.takeLast(200).asReversed(),
            key = { _, entry -> "${entry.ts}-${entry.provider}-${entry.model}-${entry.latencyMs}" },
            contentType = { _, _ -> "usage-entry" },
        ) { _, entry ->
            Surface(
                Modifier.fillMaxWidth().animateItem().clickable { onSelect(entry) },
                color = NineRouterColors.Surface,
                shape = NineRouterShapes.large,
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).background(
                            if (entry.ok) NineRouterColors.Success else NineRouterColors.Danger,
                            RoundedCornerShape(50),
                        )
                    )
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.model, style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                        Text(
                            "${entry.provider} · ${timeAgo(entry.ts)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextMuted,
                        )
                    }
                    Text(
                        "${fmt(entry.prompt)}↑ ${fmt(entry.completion)}↓",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatGrid(stats: List<Stat>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stats.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { stat ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = NineRouterColors.Surface,
                            shape = NineRouterShapes.large,
                            border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    stat.label.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NineRouterColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                AnimatedContent(
                                    targetState = stat.value,
                                    transitionSpec = {
                                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 }) togetherWith
                                            (fadeOut(tween(120)) + slideOutVertically(tween(140)) { -it / 2 })
                                    },
                                    label = "statValue",
                                ) { value ->
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = stat.color,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class Stat(val label: String, val value: String, val color: Color)

@Composable
private fun Segmented(options: List<String>, selected: String, onSelect: (String) -> Unit) {
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
private fun SegmentedSmall(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { option ->
            val active = option == selected
            Surface(
                modifier = Modifier.clickable { onSelect(option) },
                color = if (active) NineRouterColors.BrandOrange else NineRouterColors.Surface2,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    option,
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) NineRouterColors.Bg else NineRouterColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun PeriodSelector(period: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(NineRouterColors.Surface, RoundedCornerShape(9.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PERIODS.forEach { option ->
            val active = option == period
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(option) },
                color = if (active) NineRouterColors.Surface3 else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    option,
                    Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) NineRouterColors.TextMain else NineRouterColors.TextSubtle,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun UsageAreaChart(buckets: List<Bucket>, mode: String) {
    val color = when (mode) {
        "Requests" -> NineRouterColors.Info
        "Cost" -> NineRouterColors.BrandAmber
        else -> Color(0xFF9B8CFF)
    }
    val max = buckets.maxOf { it.value(mode) }.coerceAtLeast(1f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val w = size.width
            val h = size.height
            // horizontal grid
            for (i in 0..3) {
                val y = h * i / 3f
                drawLine(NineRouterColors.Border.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (buckets.isEmpty()) return@Canvas
            val stepX = if (buckets.size > 1) w / (buckets.size - 1) else w
            val points = buckets.mapIndexed { i, b ->
                Offset(i * stepX, h - (b.value(mode) / max) * (h * 0.9f))
            }
            val fill = Path().apply {
                moveTo(points.first().x, h)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, h)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f))))
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(line, color, style = Stroke(width = 2.2f))
            points.forEach { drawCircle(color, radius = 2.4f, center = it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val step = if (buckets.size > 6) 3 else 1
            buckets.forEachIndexed { index, b ->
                if (index % step == 0 || index == buckets.lastIndex) {
                    Text(b.label, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextSubtle)
                }
            }
        }
    }
}

@Composable
private fun BarList(rows: List<Pair<String, Float>>, unit: String, truncate: Int = 12) {
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f
    val colors = listOf(
        NineRouterColors.BrandOrange,
        NineRouterColors.Success,
        NineRouterColors.Info,
        NineRouterColors.BrandAmber,
        NineRouterColors.Danger,
        Color(0xFF9B8CFF),
    )
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEachIndexed { i, (name, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (name.length > truncate) name.take(truncate) + "…" else name,
                    modifier = Modifier.width(92.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                    maxLines = 1,
                )
                var grown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { grown = true }
                val fraction by animateFloatAsState(
                    targetValue = if (grown) (value / max).coerceIn(0.02f, 1f) else 0f,
                    animationSpec = tween(durationMillis = 620, delayMillis = i * 45),
                    label = "bar$i",
                )
                Box(Modifier.weight(1f).height(7.dp).background(NineRouterColors.Surface2, RoundedCornerShape(50))) {
                    Box(
                        Modifier.fillMaxWidth(fraction)
                            .height(7.dp)
                            .background(colors[i % colors.size], RoundedCornerShape(50))
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (unit == "req") value.toInt().toString() else compact(value.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
            }
        }
    }
}

private fun filterPeriod(usage: List<UsageEntry>, period: String): List<UsageEntry> {
    val now = System.currentTimeMillis()
    val start = when (period) {
        "Today" -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        "24h" -> now - 24 * 60 * 60 * 1000L
        "7D" -> now - 7 * 24 * 60 * 60 * 1000L
        "30D" -> now - 30L * 24 * 60 * 60 * 1000L
        "60D" -> now - 60L * 24 * 60 * 60 * 1000L
        else -> 0L
    }
    return usage.filter { it.ts >= start }
}

private fun buildBuckets(usage: List<UsageEntry>): List<Bucket> {
    if (usage.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val minTs = usage.minOf { it.ts }
    val span = (now - minTs).coerceAtLeast(60 * 60 * 1000L)
    val count = 12
    val width = span / count
    // Clock times are meaningless beyond a day; show dates for week/month views.
    val formatter = SimpleDateFormat(if (span > 24 * 60 * 60 * 1000L) "MMM d" else "HH:mm", Locale.US)
    return (0 until count).map { i ->
        val from = minTs + i * width
        val to = from + width
        val entries = usage.filter { it.ts >= from && it.ts < to }
        val label = formatter.format(Date(from))
        Bucket(
            label = label,
            tokens = entries.sumOf { (it.prompt + it.completion).toLong() },
            requests = entries.size,
            cost = entries.sumOf { it.cost },
        )
    }
}

private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
private fun fmt(n: Int): String = numberFormat.format(n)
private fun money(n: Double): String =
    if (n > 0.0 && n < 1.0) "$" + String.format(Locale.US, "%.4f", n)
    else "$" + String.format(Locale.US, "%.2f", n)
private fun compact(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun timeAgo(ts: Long): String {
    val diff = ((System.currentTimeMillis() - ts) / 1000).coerceAtLeast(0)
    return when {
        diff < 60 -> "${diff}s ago"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMain)
    }
}
