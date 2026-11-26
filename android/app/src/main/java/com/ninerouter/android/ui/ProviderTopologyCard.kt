package com.ninerouter.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
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
 * Mobile-first replacement for the upstream React Flow topology. A phone is
 * tall and narrow, so the flow runs downwards: 9Router at the top, one branch
 * per provider, bar thickness proportional to the busiest provider, and the
 * long tail collapsed into an "Other" branch. This stays readable from one
 * provider to dozens and avoids a fixed-height node graph.
 */
@Composable
fun ProviderTopologyCard(
    usage: List<UsageEntry>,
    revision: Long,
    modifier: Modifier = Modifier,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val limit = if (wide) 6 else 4

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
    val totalRequests = providers.sumOf { it.requests }
    val maxRequests = providers.maxOfOrNull { it.requests }?.coerceAtLeast(1) ?: 1
    val shown = providers.take(limit)
    val rest = providers.drop(limit)
    val restRequests = rest.sumOf { it.requests }

    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Provider Topology",
                    style = MaterialTheme.typography.titleSmall,
                    color = NineRouterColors.TextMain,
                )
                Text(
                    "Live route volume · last 60 seconds",
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

        Spacer(Modifier.height(8.dp))
        if (providers.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RouterRow(totalRequests = 0, activeCount = 0)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connect a provider to see live traffic.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextMuted,
                )
            }
        } else {
            RouterRow(totalRequests = totalRequests, activeCount = activeCount)
            Spacer(Modifier.height(2.dp))
            Column {
                Box(
                    Modifier
                        .padding(start = 14.dp)
                        .width(2.dp)
                        .height(7.dp)
                        .background(NineRouterColors.Border),
                )
                shown.forEachIndexed { index, provider ->
                    ProviderFlowRow(
                        provider = provider,
                        maxRequests = maxRequests,
                        totalRequests = totalRequests,
                        last = index == shown.lastIndex && rest.isEmpty(),
                    )
                }
                if (rest.isNotEmpty()) {
                    OtherFlowRow(
                        count = rest.size,
                        requests = restRequests,
                        totalRequests = totalRequests,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouterRow(totalRequests: Int, activeCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(30.dp)
                .background(NineRouterColors.BrandOrange.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.brand_mark),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "9Router",
                style = MaterialTheme.typography.titleSmall,
                color = NineRouterColors.TextMain,
            )
            Text(
                if (totalRequests > 0) "$totalRequests requests in the last minute"
                else "Waiting for traffic",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
            )
        }
        if (activeCount > 0) {
            PulsingDot(NineRouterColors.Success, size = 6.dp)
        }
    }
}

@Composable
private fun ProviderFlowRow(
    provider: TopoProvider,
    maxRequests: Int,
    totalRequests: Int,
    last: Boolean,
) {
    val active = provider.requests > 0
    val fraction = (provider.requests.toFloat() / maxRequests).coerceIn(0f, 1f)
    val share = if (totalRequests > 0) {
        (provider.requests * 100.0 / totalRequests).toInt()
    } else {
        0
    }

    Row(
        Modifier.fillMaxWidth().height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopologySpine(active = active, last = last)
        Spacer(Modifier.width(2.dp))
        ProviderIcon(provider.id, provider.name, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) NineRouterColors.TextMain else NineRouterColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (active) "${provider.requests} req" else "idle",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                )
            }
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(NineRouterColors.Surface2, RoundedCornerShape(50)),
            ) {
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(
                                if (active) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                                RoundedCornerShape(50),
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "$share%",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) NineRouterColors.TextMuted else NineRouterColors.TextSubtle,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

@Composable
private fun OtherFlowRow(count: Int, requests: Int, totalRequests: Int) {
    val share = if (totalRequests > 0) (requests * 100.0 / totalRequests).toInt() else 0
    Row(
        Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopologySpine(active = requests > 0, last = true, muted = true)
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier.size(20.dp).background(NineRouterColors.Surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.labelMedium, color = NineRouterColors.TextMuted)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "+$count more providers",
            style = MaterialTheme.typography.bodySmall,
            color = NineRouterColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$requests req",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$share%",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

@Composable
private fun TopologySpine(active: Boolean, last: Boolean, muted: Boolean = false) {
    val color = if (muted) NineRouterColors.TextSubtle else NineRouterColors.Border
    Box(Modifier.width(30.dp).fillMaxHeight()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight(0.5f)
                .background(color),
        )
        if (!last) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(2.dp)
                    .fillMaxHeight(0.5f)
                    .background(color),
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(12.dp)
                .height(2.dp)
                .background(color),
        )
        if (active) {
            PulsingDot(
                if (muted) NineRouterColors.TextSubtle else NineRouterColors.BrandOrange,
                size = 4.dp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(5.dp)
                    .background(NineRouterColors.TextSubtle, CircleShape),
            )
        }
    }
}
