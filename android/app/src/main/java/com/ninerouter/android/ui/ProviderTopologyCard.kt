package com.ninerouter.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.R
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Store
import com.ninerouter.android.data.UsageEntry

private data class TopoProvider(
    val id: String,
    val name: String,
    val requests: Int,
)

/**
 * Native echo of the upstream ProviderTopology card: 9Router at the centre,
 * connected providers around it, with animated traffic on recently active
 * edges. The web version uses React Flow; this keeps the same information and
 * motion language without adding a graph dependency.
 */
@Composable
fun ProviderTopologyCard(
    usage: List<UsageEntry>,
    revision: Long,
    modifier: Modifier = Modifier,
) {
    val providers = remember(usage, revision) {
        val now = System.currentTimeMillis()
        val recent = usage.filter { it.ts >= now - 60_000L }
        val ids = buildList {
            Store.state.connections.filter { it.enabled }.forEach { add(it.provider) }
            Store.state.nodes.forEach { add(it.id) }
        }.distinct()

        ids.map { id ->
            val node = Store.state.nodes.firstOrNull { it.id == id }
            TopoProvider(
                id = id,
                name = Catalog.find(id)?.name ?: node?.name ?: id,
                requests = recent.count { it.provider == id },
            )
        }.sortedWith(
            compareByDescending<TopoProvider> { it.requests }
                .thenBy { it.name.lowercase() },
        )
    }
    val activeCount = providers.count { it.requests > 0 }
    val shown = providers.take(4)

    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Provider Topology",
                    style = MaterialTheme.typography.titleSmall,
                    color = NineRouterColors.TextMain,
                )
                Text(
                    "Traffic from 9Router to your providers",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                )
            }
            Pill(
                if (activeCount > 0) "$activeCount active" else "${providers.size} idle",
                if (activeCount > 0) NineRouterColors.Success else NineRouterColors.TextMuted,
                if (activeCount > 0) NineRouterColors.Success.copy(alpha = 0.12f) else NineRouterColors.Surface2,
            )
        }

        Spacer(Modifier.height(6.dp))
        if (providers.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(150.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RouterHub(0)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Connect a provider to see live traffic.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            }
        } else {
            TopologyGraph(shown, activeCount)
            if (providers.size > shown.size) {
                Text(
                    "+${providers.size - shown.size} more connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TopologyGraph(providers: List<TopoProvider>, activeCount: Int) {
    val transition = rememberInfiniteTransition(label = "topologyPulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topologyPhase",
    )

    Box(Modifier.fillMaxWidth().height(208.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val targets = listOf(
                Offset(size.width / 2f, size.height * 0.17f),
                Offset(size.width * 0.82f, size.height / 2f),
                Offset(size.width / 2f, size.height * 0.83f),
                Offset(size.width * 0.18f, size.height / 2f),
            )
            providers.forEachIndexed { index, provider ->
                val target = targets[index % targets.size]
                val active = provider.requests > 0
                drawLine(
                    color = if (active) NineRouterColors.BrandOrange.copy(alpha = 0.6f)
                    else NineRouterColors.Border,
                    start = center,
                    end = target,
                    strokeWidth = if (active) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (active) {
                    val dx = target.x - center.x
                    val dy = target.y - center.y
                    listOf(phase, (phase + 0.5f) % 1f).forEachIndexed { dotIndex, at ->
                        drawCircle(
                            color = if (dotIndex == 0) NineRouterColors.BrandOrange
                            else NineRouterColors.BrandOrange.copy(alpha = 0.5f),
                            radius = if (dotIndex == 0) 3.dp.toPx() else 2.dp.toPx(),
                            center = Offset(center.x + dx * at, center.y + dy * at),
                        )
                    }
                }
            }
        }

        RouterHub(
            activeCount = activeCount,
            modifier = Modifier.align(Alignment.Center),
        )

        providers.forEachIndexed { index, provider ->
            val alignment = when (index) {
                0 -> Alignment.TopCenter
                1 -> Alignment.CenterEnd
                2 -> Alignment.BottomCenter
                else -> Alignment.CenterStart
            }
            TopologyNode(
                provider = provider,
                modifier = Modifier.align(alignment).width(106.dp),
            )
        }
    }
}

@Composable
private fun RouterHub(activeCount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(90.dp),
        color = NineRouterColors.Surface2,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, NineRouterColors.BrandOrange.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.brand_mark),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    "9Router",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NineRouterColors.TextMain,
                )
                Text(
                    if (activeCount > 0) "$activeCount active" else "idle",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activeCount > 0) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                )
            }
        }
    }
}

@Composable
private fun TopologyNode(provider: TopoProvider, modifier: Modifier = Modifier) {
    val active = provider.requests > 0
    Surface(
        modifier = modifier,
        color = if (active) NineRouterColors.BrandOrange.copy(alpha = 0.10f) else NineRouterColors.Surface2.copy(alpha = 0.75f),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(
            1.dp,
            if (active) NineRouterColors.BrandOrange.copy(alpha = 0.55f) else NineRouterColors.BorderSubtle,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(provider.id, provider.name, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) NineRouterColors.TextMain else NineRouterColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (active) "${provider.requests} req" else "idle",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                    maxLines = 1,
                )
            }
        }
    }
}
