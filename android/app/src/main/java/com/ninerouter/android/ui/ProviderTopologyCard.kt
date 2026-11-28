package com.ninerouter.android.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninerouter.android.R
import com.ninerouter.android.core.ActiveRequests
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.ProviderDef
import com.ninerouter.android.data.Store
import com.ninerouter.android.data.UsageEntry
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Layout constants from the upstream buildLayout(). Positions use the
// dashboard's approximate 180x30 / 120x44 boxes; the rendered cards use their
// real measured size (180x56 / dynamic router width x52).
private const val TOPO_LAYOUT_NODE_W = 180f
private const val TOPO_LAYOUT_NODE_H = 30f
private const val TOPO_LAYOUT_ROUTER_W = 120f
private const val TOPO_LAYOUT_ROUTER_H = 44f
private const val TOPO_NODE_W = 180f
private const val TOPO_NODE_H = 56f
private const val TOPO_ROUTER_H = 52f
private const val TOPO_NODE_GAP = 24f

private data class TopoProvider(
    val id: String,
    val label: String,
    val color: Color,
    val textIcon: String,
)

private enum class TopoSide { Top, Bottom, Left, Right }

private data class TopoNode(
    val provider: TopoProvider,
    val centerX: Float,
    val centerY: Float,
    val angle: Double,
    val width: Float,
    val active: Boolean,
    val last: Boolean,
    val error: Boolean,
) {
    val sourceSide: TopoSide
        get() = when {
            abs(angle + PI / 2) < PI / 4 || abs(angle - 3 * PI / 2) < PI / 4 -> TopoSide.Top
            abs(angle - PI / 2) < PI / 4 -> TopoSide.Bottom
            centerX > 0 -> TopoSide.Right
            else -> TopoSide.Left
        }

    val targetSide: TopoSide
        get() = when (sourceSide) {
            TopoSide.Top -> TopoSide.Bottom
            TopoSide.Bottom -> TopoSide.Top
            TopoSide.Left -> TopoSide.Right
            TopoSide.Right -> TopoSide.Left
        }
}

private data class TopoLayout(
    val nodes: List<TopoNode>,
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
    val routerWidth: Float,
)

private fun providerColor(def: ProviderDef?): Color {
    val raw = def?.color.orEmpty()
    if (raw.isBlank()) return Color(0xFF6B7280)
    return runCatching { Color(AndroidColor.parseColor(raw)) }.getOrDefault(Color(0xFF6B7280))
}

private fun providerTextIcon(def: ProviderDef?, id: String): String =
    def?.textIcon?.takeIf { it.isNotBlank() } ?: id.take(2).uppercase(Locale.US)

private fun isLlmProvider(def: ProviderDef?): Boolean {
    if (def == null) return true
    return def.serviceKinds.isEmpty() || def.serviceKinds.contains("llm")
}

private fun topologyProviders(): List<TopoProvider> {
    val nodeNames = Store.state.nodes.associate { it.id to it.name }
    val seen = LinkedHashSet<String>()
    val result = mutableListOf<TopoProvider>()

    Store.state.connections.filter { it.enabled }.forEach { connection ->
        val id = connection.provider
        if (!seen.add(id)) return@forEach
        val def = Catalog.find(id)
        if (def?.hidden == true) return@forEach
        if (!isLlmProvider(def)) return@forEach
        val label = when {
            def != null && def.name.isNotBlank() && def.name != id -> def.name
            nodeNames[id]?.isNotBlank() == true -> nodeNames.getValue(id)
            connection.name.isNotBlank() -> connection.name
            else -> id
        }
        result += TopoProvider(
            id = id,
            label = label,
            color = providerColor(def),
            textIcon = providerTextIcon(def, id),
        )
    }

    // Free/no-auth providers are always present on the upstream topology even
    // when they have no stored connection row.
    Catalog.providers()
        .filter { it.noAuth && !it.hidden && it.id !in seen && isLlmProvider(it) }
        .forEach { def ->
            result += TopoProvider(
                id = def.id,
                label = def.name.ifBlank { def.id },
                color = providerColor(def),
                textIcon = providerTextIcon(def, def.id),
            )
        }
    return result
}

private fun providerNodeWidth(label: String): Float {
    // 16sp Manrope average advance is ~8.8px; include icon, gaps, padding and
    // the active dot so a label never clips at the upstream minimum width.
    val text = label.length * 8.8f
    return max(150f, 90f + text)
}

private fun buildLayout(
    providers: List<TopoProvider>,
    activeSet: Set<String>,
    lastSet: Set<String>,
    errorSet: Set<String>,
): TopoLayout? {
    val count = providers.size
    if (count == 0) return null

    val minRx = ((TOPO_LAYOUT_NODE_W + TOPO_NODE_GAP) * count) / (2 * PI)
    val rx = max(320f, minRx.toFloat())
    val ry = max(200f, rx * 0.55f)
    val routerWidth = if (activeSet.isNotEmpty()) 164f else 134f

    val nodes = providers.mapIndexed { index, provider ->
        val angle = -PI / 2 + (2 * PI * index) / count
        val cx = rx * cos(angle).toFloat()
        val cy = ry * sin(angle).toFloat()
        val key = provider.id.lowercase(Locale.US)
        val active = key in activeSet
        TopoNode(
            provider = provider,
            centerX = cx,
            centerY = cy,
            angle = angle,
            width = providerNodeWidth(provider.label),
            active = active,
            last = !active && key in lastSet,
            error = !active && key in errorSet,
        )
    }

    // React Flow places the node by its top-left position, so the rendered
    // card extends below the ellipse point by its measured height.
    var minX = -TOPO_LAYOUT_ROUTER_W / 2
    var maxX = -TOPO_LAYOUT_ROUTER_W / 2 + routerWidth
    var minY = -TOPO_LAYOUT_ROUTER_H / 2
    var maxY = -TOPO_LAYOUT_ROUTER_H / 2 + TOPO_ROUTER_H
    nodes.forEach { node ->
        val left = node.centerX - TOPO_LAYOUT_NODE_W / 2
        val top = node.centerY - TOPO_LAYOUT_NODE_H / 2
        minX = min(minX, left)
        maxX = max(maxX, left + node.width)
        minY = min(minY, top)
        maxY = max(maxY, top + TOPO_NODE_H)
    }
    return TopoLayout(
        nodes = nodes,
        minX = minX,
        minY = minY,
        width = maxX - minX,
        height = maxY - minY,
        routerWidth = routerWidth,
    )
}

private fun handlePoint(centerX: Float, centerY: Float, halfW: Float, halfH: Float, side: TopoSide): Offset =
    when (side) {
        TopoSide.Top -> Offset(centerX, centerY - halfH)
        TopoSide.Bottom -> Offset(centerX, centerY + halfH)
        TopoSide.Left -> Offset(centerX - halfW, centerY)
        TopoSide.Right -> Offset(centerX + halfW, centerY)
    }

/**
 * Native port of the upstream ProviderTopology React Flow graph.
 *
 * Providers sit on an ellipse around the 9Router node. Edges are cubic beziers
 * with the same handle selection and color precedence (error, active, last,
 * idle). Active edges draw the electric halo/plasma/core beam plus moving
 * particles; active providers get a colored border and pulsing dot; the router
 * pulses and shows the active count.
 */
@Composable
fun ProviderTopologyCard(
    usage: List<UsageEntry>,
    revision: Long,
    modifier: Modifier = Modifier,
) {
    val now by liveNow(1_000L)
    val wide = LocalConfiguration.current.screenWidthDp >= 640
    val cardHeight = if (wide) 480.dp else 320.dp

    val providers = remember(revision) { topologyProviders() }
    val active = remember(now) { ActiveRequests.snapshot(now) }
    val activeSet = remember(active) {
        active.map { it.provider.lowercase(Locale.US) }.toSet()
    }
    val errorProvider = remember(now) { ActiveRequests.errorProvider(now) }
    val lastProvider = remember(usage) {
        usage.maxByOrNull { it.ts }?.provider?.lowercase(Locale.US).orEmpty()
    }
    val lastSet = remember(lastProvider) {
        if (lastProvider.isBlank()) emptySet() else setOf(lastProvider)
    }
    val errorSet = remember(errorProvider) {
        if (errorProvider.isBlank()) emptySet() else setOf(errorProvider)
    }
    val activeCount = activeSet.size
    val layout = remember(providers, activeSet, lastSet, errorSet) {
        buildLayout(providers, activeSet, lastSet, errorSet)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(NineRouterColors.SurfaceSunken.copy(alpha = 0.34f))
            .border(1.dp, NineRouterColors.Border, RoundedCornerShape(12.dp)),
    ) {
        if (layout == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No providers connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineRouterColors.TextMuted,
                )
            }
        } else {
            TopologyGraph(layout = layout, activeCount = activeCount)
        }
    }
}

@Composable
private fun TopologyGraph(layout: TopoLayout, activeCount: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val fitScale = remember(layout.width, layout.height, widthDp, heightDp) {
            (min(widthDp / layout.width, heightDp / layout.height) * (1f / 1.2f))
                .coerceIn(0.1f, 2f)
        }
        var scale by remember(layout.width, layout.height) { mutableFloatStateOf(fitScale) }
        var pan by remember(layout.width, layout.height) { mutableStateOf(Offset.Zero) }
        var widthPx by remember { mutableFloatStateOf(0f) }
        var heightPx by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(fitScale, widthPx, heightPx) {
            if (widthPx > 0f && heightPx > 0f) {
                scale = fitScale
                pan = Offset.Zero
            }
        }

        val graphWidthPx = with(density) { layout.width.dp.toPx() }
        val graphHeightPx = with(density) { layout.height.dp.toPx() }
        val translationX = widthPx / 2f - graphWidthPx * scale / 2f + pan.x
        val translationY = heightPx / 2f - graphHeightPx * scale / 2f + pan.y

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged {
                    widthPx = it.width.toFloat()
                    heightPx = it.height.toFloat()
                }
                .pointerInput(layout.width, layout.height) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        val next = (scale * zoom).coerceIn(0.1f, 2f)
                        scale = next
                        pan += panChange
                    }
                },
        ) {
            Box(
                Modifier
                    .requiredSize(layout.width.dp, layout.height.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.translationX = translationX
                        this.translationY = translationY
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                TopologyEdges(layout)
                RouterNode(
                    activeCount = activeCount,
                    routerWidth = layout.routerWidth,
                    modifier = Modifier.offset(
                        x = (-TOPO_LAYOUT_ROUTER_W / 2 - layout.minX).dp,
                        y = (-TOPO_LAYOUT_ROUTER_H / 2 - layout.minY).dp,
                    ),
                )
                layout.nodes.forEach { node ->
                    ProviderNode(
                        node = node,
                        modifier = Modifier.offset(
                            x = (node.centerX - TOPO_LAYOUT_NODE_W / 2 - layout.minX).dp,
                            y = (node.centerY - TOPO_LAYOUT_NODE_H / 2 - layout.minY).dp,
                        ),
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                TopologyControl("+") { scale = (scale * 1.2f).coerceAtMost(2f) }
                TopologyControl("−") { scale = (scale / 1.2f).coerceAtLeast(0.1f) }
                TopologyControl("⤢") {
                    scale = fitScale
                    pan = Offset.Zero
                }
            }
        }
    }
}

@Composable
private fun TopologyControl(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = NineRouterColors.Surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, NineRouterColors.Border),
        modifier = Modifier.size(28.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = NineRouterColors.TextMain,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TopologyEdges(layout: TopoLayout) {
    val hasActive = layout.nodes.any { it.active }
    var elapsedMs by remember(hasActive) { mutableLongStateOf(0L) }
    LaunchedEffect(hasActive) {
        if (!hasActive) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                elapsedMs = (now - start) / 1_000_000L
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val routerLeft = -TOPO_LAYOUT_ROUTER_W / 2f
        val routerTop = -TOPO_LAYOUT_ROUTER_H / 2f
        val routerCenter = Offset(
            routerLeft + layout.routerWidth / 2f - layout.minX,
            routerTop + TOPO_ROUTER_H / 2f - layout.minY,
        )
        val routerHalfW = layout.routerWidth / 2f
        val routerHalfH = TOPO_ROUTER_H / 2f

        layout.nodes.forEach { node ->
            val providerLeft = node.centerX - TOPO_LAYOUT_NODE_W / 2f
            val providerTop = node.centerY - TOPO_LAYOUT_NODE_H / 2f
            val providerCenter = Offset(
                providerLeft + node.width / 2f - layout.minX,
                providerTop + TOPO_NODE_H / 2f - layout.minY,
            )
            val source = handlePoint(
                routerCenter.x,
                routerCenter.y,
                routerHalfW,
                routerHalfH,
                node.sourceSide,
            )
            val target = handlePoint(
                providerCenter.x,
                providerCenter.y,
                node.width / 2f,
                TOPO_NODE_H / 2f,
                node.targetSide,
            )
            val path = bezierPath(source, node.sourceSide, target, node.targetSide)
            val baseColor = when {
                node.error -> Color(0xFFEF4444)
                node.active -> Color(0xFF22D3EE)
                node.last -> Color(0xFFF59E0B)
                else -> NineRouterColors.Border
            }
            val baseWidth = when {
                node.error -> 2.5.dp.toPx()
                node.active -> 3.5.dp.toPx()
                node.last -> 2.dp.toPx()
                else -> 1.dp.toPx()
            }
            val baseAlpha = when {
                node.error -> 0.9f
                node.active -> 1f
                node.last -> 0.7f
                else -> 0.3f
            }

            if (node.active) {
                drawElectricEdge(path, elapsedMs)
            } else {
                drawPath(
                    path,
                    color = baseColor.copy(alpha = baseAlpha),
                    style = Stroke(width = baseWidth, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * The upstream edge is three SVG strokes passed through an animated
 * feTurbulence/feDisplacementMap, plus six energy orbs and five blinking
 * sparks. Compose has no SVG filter, so the same idea is rebuilt with a
 * time-jittered polyline for the halo/plasma, CSS-speed dash offsets and
 * per-particle motion periods.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElectricEdge(
    path: Path,
    elapsedMs: Long,
) {
    val halo = jitteredPath(path, amplitude = 3.5.dp.toPx(), elapsedMs = elapsedMs, seed = 1)
    val plasma = jitteredPath(path, amplitude = 2.2.dp.toPx(), elapsedMs = elapsedMs, seed = 7)
    val core = jitteredPath(path, amplitude = 0.7.dp.toPx(), elapsedMs = elapsedMs, seed = 13)

    val haloFlicker = when (((elapsedMs / 90L) % 4L).toInt()) {
        0 -> 0.85f
        1 -> 1f
        2 -> 0.55f
        else -> 1f
    }
    val plasmaFlicker = when (((elapsedMs / 110L) % 3L).toInt()) {
        0 -> 0.85f
        1 -> 1f
        else -> 0.65f
    }

    // Wide soft glow, then the upstream halo/plasma/core layers.
    drawPath(
        path = halo,
        color = Color(0xFF22D3EE).copy(alpha = 0.10f * haloFlicker),
        style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round),
    )
    drawPath(
        path = halo,
        color = Color(0xFF22D3EE).copy(alpha = 0.35f * haloFlicker),
        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
    )
    drawPath(
        path = plasma,
        color = Color(0xFF4ADE80).copy(alpha = 0.85f * plasmaFlicker),
        style = Stroke(
            width = 5.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(14.dp.toPx(), 10.dp.toPx()),
                phase = -((elapsedMs % 180L) / 180f) * 36f,
            ),
        ),
    )
    drawPath(
        path = core,
        color = Color(0xFFF8FAFC),
        style = Stroke(
            width = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                phase = -((elapsedMs % 220L) / 220f) * 36f,
            ),
        ),
    )
    drawEnergyParticles(path, elapsedMs)
}

private fun jitteredPath(path: Path, amplitude: Float, elapsedMs: Long, seed: Int): Path {
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return path
    val steps = 26
    val frame = elapsedMs / 80f
    val out = Path()
    var previous: Offset? = null
    for (index in 0..steps) {
        val distance = length * index / steps
        val point = measure.getPosition(distance)
        val before = measure.getPosition((distance - 2f).coerceAtLeast(0f))
        val after = measure.getPosition((distance + 2f).coerceAtMost(length))
        val tangent = after - before
        val normal = if (tangent.getDistance() > 0.0001f) {
            Offset(-tangent.y, tangent.x) / tangent.getDistance()
        } else {
            Offset.Zero
        }
        val noise = pseudoNoise(index * 1.7f + frame * 2.9f + seed * 19.7f)
        val shifted = point + normal * (noise * amplitude)
        if (previous == null) out.moveTo(shifted.x, shifted.y) else out.lineTo(shifted.x, shifted.y)
        previous = shifted
    }
    return out
}

private fun pseudoNoise(value: Float): Float {
    val raw = kotlin.math.sin(value * 12.9898f) * 43758.5453f
    return (raw - kotlin.math.floor(raw)) * 2f - 1f
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEnergyParticles(
    path: Path,
    elapsedMs: Long,
) {
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return

    // <animateMotion dur={0.4 + i*0.08}s begin={i*0.09}s>
    repeat(6) { index ->
        val begin = index * 90L
        if (elapsedMs < begin) return@repeat
        val duration = 400L + index * 80L
        val progress = ((elapsedMs - begin) % duration) / duration.toFloat()
        val position = measure.getPosition(progress * length)
        val radius = if (index % 2 == 0) 4.dp.toPx() else 2.5.dp.toPx()
        val color = when (index % 3) {
            0 -> Color(0xFFFDE047)
            1 -> Color(0xFF67E8F9)
            else -> Color.White
        }
        drawCircle(
            color = color.copy(alpha = 0.16f),
            radius = radius * 2.6f,
            center = position,
        )
        drawCircle(color = color, radius = radius, center = position)
    }

    // Sparks: blink opacity and travel with their own short periods.
    repeat(5) { index ->
        val blinkBegin = index * 70L
        if (elapsedMs >= blinkBegin) {
            val blinkDuration = 350L + (index % 3) * 100L
            val phase = ((elapsedMs - blinkBegin) % blinkDuration) / blinkDuration.toFloat()
            val alpha = when {
                phase < 0.15f -> phase / 0.15f
                phase < 0.30f -> 1f - (phase - 0.15f) / 0.15f
                phase < 0.55f -> 0f
                phase < 0.70f -> (phase - 0.55f) / 0.15f
                phase < 0.85f -> 1f - (phase - 0.70f) / 0.15f
                else -> 0f
            }
            if (alpha > 0f) {
                val motionDuration = 280L + index * 50L
                val motionBegin = index * 110L
                if (elapsedMs >= motionBegin) {
                    val progress = ((elapsedMs - motionBegin) % motionDuration) / motionDuration.toFloat()
                    val position = measure.getPosition(progress * length)
                    drawCircle(
                        color = Color(0xFFE0F2FE).copy(alpha = 0.20f * alpha),
                        radius = 4.5.dp.toPx(),
                        center = position,
                    )
                    drawCircle(
                        color = Color(0xFFE0F2FE).copy(alpha = alpha),
                        radius = 1.8.dp.toPx(),
                        center = position,
                    )
                }
            }
        }
    }
}

private fun controlOffset(distance: Float): Float =
    if (distance >= 0f) 0.5f * distance else 0.25f * 25f * kotlin.math.sqrt(-distance)

private fun controlPoint(
    from: Offset,
    fromSide: TopoSide,
    to: Offset,
): Offset = when (fromSide) {
    TopoSide.Top -> Offset(from.x, from.y - controlOffset(from.y - to.y))
    TopoSide.Bottom -> Offset(from.x, from.y + controlOffset(to.y - from.y))
    TopoSide.Left -> Offset(from.x - controlOffset(from.x - to.x), from.y)
    TopoSide.Right -> Offset(from.x + controlOffset(to.x - from.x), from.y)
}

private fun bezierPath(
    source: Offset,
    sourceSide: TopoSide,
    target: Offset,
    targetSide: TopoSide,
): Path {
    val c1 = controlPoint(source, sourceSide, target)
    val c2 = controlPoint(target, targetSide, source)
    return Path().apply {
        moveTo(source.x, source.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, target.x, target.y)
    }
}

@Composable
private fun ProviderNode(node: TopoNode, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val activeColor = node.provider.color
    val borderColor = when {
        node.active -> activeColor
        node.error -> Color(0xFFEF4444)
        node.last -> Color(0xFFF59E0B)
        else -> NineRouterColors.Border
    }
    Box(
        modifier
            .width(node.width.dp)
            .height(TOPO_NODE_H.dp)
            .then(
                if (node.active) Modifier
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(activeColor.copy(alpha = 0.18f), Color.Transparent),
                                center = center,
                                radius = size.maxDimension * 0.9f,
                            ),
                            radius = size.maxDimension * 0.9f,
                            center = center,
                        )
                    }
                else Modifier,
            )
            .clip(shape)
            .background(NineRouterColors.Bg, shape)
            .border(2.dp, borderColor, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics {
                contentDescription = "${node.provider.label}, ${if (node.active) "active" else "idle"}"
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(activeColor.copy(alpha = 0.09f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ProviderIcon(
                    node.provider.id,
                    node.provider.label,
                    size = 24.dp,
                    fallbackColor = activeColor,
                    fallbackText = node.provider.textIcon,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                node.provider.label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
                fontWeight = FontWeight.Medium,
                color = if (node.active) activeColor else NineRouterColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (node.active) {
                Spacer(Modifier.width(8.dp))
                PulsingDot(activeColor, size = 8.dp)
            }
        }
    }
}

@Composable
private fun RouterNode(activeCount: Int, routerWidth: Float, modifier: Modifier = Modifier) {
    val powering = activeCount > 0
    val transition = rememberInfiniteTransition(label = "topologyRouter")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "routerPulse",
    )
    val shake by transition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
        label = "routerShake",
    )
    val shape = RoundedCornerShape(14.dp)
    val brush = if (powering) {
        Brush.linearGradient(
            listOf(
                NineRouterColors.BrandOrange.copy(alpha = 0.30f),
                Color(0xFFFACC15).copy(alpha = 0.20f),
                Color(0xFF22D3EE).copy(alpha = 0.25f),
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                NineRouterColors.BrandOrange.copy(alpha = 0.08f),
                NineRouterColors.BrandOrange.copy(alpha = 0.03f),
            ),
        )
    }
    Row(
        modifier
            .width(routerWidth.dp)
            .height(TOPO_ROUTER_H.dp)
            .then(
                if (powering) Modifier.graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                } else Modifier,
            )
            .drawBehind {
                if (powering) {
                    drawCircle(
                        color = Color(0xFFFDE047).copy(alpha = 0.10f),
                        radius = size.maxDimension * 1.15f,
                        center = center,
                    )
                    drawCircle(
                        color = Color(0xFF22D3EE).copy(alpha = 0.08f),
                        radius = size.maxDimension * 0.9f,
                        center = center,
                    )
                }
            }
            .clip(shape)
            .background(brush, shape)
            .border(
                2.dp,
                if (powering) Color(0xFFFDE047) else NineRouterColors.BrandOrange,
                shape,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = if (powering) shake else 0f },
        )
        Spacer(Modifier.width(9.dp))
        Text(
            "9Router",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (powering) Color(0xFFFDE047) else NineRouterColors.BrandOrange,
            maxLines = 1,
        )
        if (activeCount > 0) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = Color(0xFFFACC15),
                shape = RoundedCornerShape(999.dp),
                shadowElevation = 0.dp,
            ) {
                Text(
                    activeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
