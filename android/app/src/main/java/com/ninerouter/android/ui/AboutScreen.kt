package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ninerouter.android.AppInfo
import com.ninerouter.android.R
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Store

@Composable
fun AboutScreen() {
    @Suppress("UNUSED_VARIABLE")
    val revision = storeRevision().value
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, tr("No browser available"), Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { AboutHero() }
            item {
                LineageCard(
                    onUpstream = { open("https://github.com/decolua/9router") },
                    onPort = { open(AppInfo.GITHUB_URL) },
                )
            }
            item { AboutHeading("What the app does", "A gateway, not another chat client") }
            item { FeatureGrid() }
            item { AboutHeading("Why native", "One process, no runtime under it") }
            item { StoryCard() }
            item { AboutHeading("Under the hood", "The small stack behind the app") }
            item { TechCard() }
            item { AboutHeading("Credits & license", "The people and terms behind the project") }
            item {
                CreditsCard(
                    onContributors = { open("https://github.com/decolua/9router/graphs/contributors") },
                    onLicense = { open("https://github.com/decolua/9router/blob/master/LICENSE") },
                    onSource = { open(AppInfo.GITHUB_URL) },
                    onReleases = { open(AppInfo.RELEASES_URL) },
                    onIssues = { open("${AppInfo.GITHUB_URL}/issues") },
                    onProfile = { open("https://github.com/itswill00") },
                )
            }
            item { AboutFooter() }
        }
    }
}

@Composable
private fun AboutHero() {
    val shape = RoundedCornerShape(24.dp)
    val providerCount = Catalog.providers().size
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        NineRouterColors.BrandOrange.copy(alpha = 0.28f),
                        NineRouterColors.Surface,
                        NineRouterColors.Surface,
                    ),
                ),
            )
            .border(1.dp, NineRouterColors.BrandOrange.copy(alpha = 0.22f), shape)
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.brand_mark),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "9Router",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NineRouterColors.TextMain,
                    )
                    Text(
                        "for Android",
                        style = MaterialTheme.typography.labelMedium,
                        color = NineRouterColors.BrandOrange,
                    )
                }
                Pill("v${AppInfo.VERSION}")
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "A native Android port of 9Router.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = NineRouterColors.TextMain,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The open-source gateway by decolua and contributors now runs on your phone. This app rebuilds the runtime in Kotlin and Compose: provider routing, combos, vision and chat without Node, Termux or root.",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Open source")
                Pill("MIT")
                Pill("No Node.js")
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutMetric("$providerCount", "providers", Modifier.weight(1f))
                AboutMetric("${Store.state.connections.size}", "connections", Modifier.weight(1f))
                AboutMetric("${Store.state.combos.size}", "combos", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AboutMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = NineRouterColors.Surface2.copy(alpha = 0.55f),
        shape = NineRouterShapes.medium,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NineRouterColors.BrandOrange,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
        }
    }
}

@Composable
private fun LineageCard(
    onUpstream: () -> Unit,
    onPort: () -> Unit,
) {
    Surface(
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Project lineage",
                style = MaterialTheme.typography.titleSmall,
                color = NineRouterColors.TextMain,
            )
            Text(
                "9Router existed before this app. Here is who made what.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
            )
            Spacer(Modifier.height(14.dp))
            CreditProfileRow(
                avatar = R.drawable.decolua,
                name = "decolua",
                badge = "Founder",
                role = "Original 9Router",
                body = "Created the upstream gateway, provider registry and dashboard that this port builds on.",
                linkLabel = "Upstream project",
                ring = NineRouterColors.BorderStrong,
                badgeColor = NineRouterColors.TextMain,
                badgeBackground = NineRouterColors.Surface2,
                onOpen = onUpstream,
            )
            Spacer(Modifier.height(12.dp))
            ContributorStrip()
            LineageConnector()
            CreditProfileRow(
                avatar = R.drawable.itswill00,
                name = "itswill00",
                badge = "Android port",
                role = "Creator of this Android app",
                body = "Rebuilt the runtime in Kotlin and Compose and adapted the gateway to run on-device.",
                linkLabel = "Android source",
                ring = NineRouterColors.BrandOrange,
                badgeColor = NineRouterColors.BrandOrange,
                badgeBackground = NineRouterColors.BrandOrange.copy(alpha = 0.14f),
                onOpen = onPort,
            )
        }
    }
}

@Composable
private fun CreditProfileRow(
    avatar: Int,
    name: String,
    badge: String,
    role: String,
    body: String,
    linkLabel: String,
    ring: androidx.compose.ui.graphics.Color,
    badgeColor: androidx.compose.ui.graphics.Color,
    badgeBackground: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        ProfileAvatar(avatar, name, ring, size = 64.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NineRouterColors.TextMain,
                )
                Spacer(Modifier.width(6.dp))
                Pill(badge, badgeColor, badgeBackground)
            }
            Text(
                role,
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
            )
            TextButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) {
                Text(linkLabel, color = NineRouterColors.BrandOrange, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ProfileAvatar(avatar: Int, name: String, ring: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .background(ring.copy(alpha = 0.3f), CircleShape)
            .padding(3.dp),
    ) {
        Image(
            painter = painterResource(avatar),
            contentDescription = t("$name GitHub profile photo"),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
        )
    }
}

@Composable
private fun LineageConnector() {
    Row(
        Modifier.fillMaxWidth().padding(start = 31.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(14.dp).background(NineRouterColors.Border))
            Box(Modifier.size(5.dp).background(NineRouterColors.BrandOrange, CircleShape))
            Box(Modifier.width(2.dp).height(14.dp).background(NineRouterColors.Border))
        }
        Spacer(Modifier.width(18.dp))
        Text(
            "forked and ported for Android",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
        )
    }
}

@Composable
private fun ContributorStrip() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ProfileAvatar(R.drawable.decolua, "decolua", NineRouterColors.BorderStrong, size = 28.dp)
            ProfileAvatar(R.drawable.itswill00, "itswill00", NineRouterColors.BrandOrange, size = 28.dp)
            Box(
                Modifier
                    .size(28.dp)
                    .background(NineRouterColors.Surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = MaterialTheme.typography.labelMedium, color = NineRouterColors.TextMuted)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "decolua and the 9Router contributors",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMain,
            )
            Text(
                "The upstream community built the gateway this app ports.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
            )
        }
    }
}

@Composable
private fun FeatureGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureTile(
                icon = R.drawable.ic_nav_endpoint,
                title = "Local gateway",
                body = "OpenAI-compatible /v1 on 127.0.0.1, served from a foreground service.",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            FeatureTile(
                icon = R.drawable.ic_nav_providers,
                title = "Provider routing",
                body = "OpenAI, Claude, Gemini, Responses and custom compatible nodes.",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureTile(
                icon = R.drawable.ic_nav_combos,
                title = "Combo strategies",
                body = "Fallback, round robin, smart router and fusion with a judge.",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            FeatureTile(
                icon = R.drawable.ic_chat,
                title = "Native chat",
                body = "Streaming sessions with image attachments for vision models.",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun FeatureTile(
    icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(NineRouterColors.BrandOrange.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = NineRouterColors.BrandOrange,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = NineRouterColors.TextMain,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextMuted,
            )
        }
    }
}

@Composable
private fun StoryCard() {
    Surface(
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Most AI gateways assume a desktop runtime: Node, a terminal and a process you manage. This build moves the gateway into the phone itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = NineRouterColors.TextMain,
            )
            Spacer(Modifier.height(10.dp))
            AboutBullet("A foreground service holds the loopback socket.")
            AboutBullet("Credentials are encrypted with the Android Keystore.")
            AboutBullet("No Node.js, Termux, shell, root or bundled userland.")
            AboutBullet("A single sideloaded APK updates from GitHub releases.")
        }
    }
}

@Composable
private fun AboutBullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(4.dp)
                .background(NineRouterColors.BrandOrange, CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = NineRouterColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TechCard() {
    Surface(
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            TechRow("Language", "Kotlin")
            DividerLine()
            TechRow("UI", "Jetpack Compose, Material 3")
            DividerLine()
            TechRow("Transport", "OkHttp, loopback HTTP/1.1")
            DividerLine()
            TechRow("Storage", "Encrypted app state, Android Keystore")
            DividerLine()
            TechRow("Registry", "${Catalog.providers().size} providers, exported at build time")
            DividerLine()
            TechRow("Release", "Single plain APK, sideload updates")
        }
    }
}

@Composable
private fun TechRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
            modifier = Modifier.width(82.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = NineRouterColors.TextMain,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CreditsCard(
    onContributors: () -> Unit,
    onLicense: () -> Unit,
    onSource: () -> Unit,
    onReleases: () -> Unit,
    onIssues: () -> Unit,
    onProfile: () -> Unit,
) {
    Surface(
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "MIT License",
                style = MaterialTheme.typography.titleSmall,
                color = NineRouterColors.TextMain,
            )
            Text(
                "Copyright (c) 2024-2026 decolua and contributors. The original 9Router and this Android port are distributed under the MIT License.",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("License text", modifier = Modifier.weight(1f), onClick = onLicense)
                SecondaryButton("Contributors", modifier = Modifier.weight(1f), onClick = onContributors)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Android source", modifier = Modifier.weight(1f), onClick = onSource)
                SecondaryButton("Releases", modifier = Modifier.weight(1f), onClick = onReleases)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Issues", modifier = Modifier.weight(1f), onClick = onIssues)
                SecondaryButton("GitHub profile", modifier = Modifier.weight(1f), onClick = onProfile)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The Android runtime is independent Kotlin; it keeps the upstream provider registry and design tokens.",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
            )
        }
    }
}

@Composable
private fun AboutFooter() {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "9Router for Android",
            style = MaterialTheme.typography.labelMedium,
            color = NineRouterColors.TextMain,
        )
        Text(
            "Version ${AppInfo.VERSION_NAME} (build ${AppInfo.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Original 9Router by decolua and contributors.",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
            textAlign = TextAlign.Center,
        )
        Text(
            "Native Android port by itswill00. MIT licensed.",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
            textAlign = TextAlign.Center,
        )
        Text(
            "Provider names and marks belong to their owners.",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextFaint,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AboutHeading(title: String, subtitle: String) {
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
    }
}
