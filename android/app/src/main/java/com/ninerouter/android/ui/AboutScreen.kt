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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninerouter.android.AppInfo
import com.ninerouter.android.R
import com.ninerouter.android.data.Catalog
import com.ninerouter.android.data.Store

@Composable
fun AboutScreen() {
    @Suppress("UNUSED_VARIABLE") val revision by storeRevision()
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
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
                DeveloperCard(
                    onGitHub = { open("https://github.com/itswill00") },
                )
            }
            item { AboutHeading("What the app does", "A gateway, not another chat client") }
            item { FeatureGrid() }
            item { AboutHeading("Why native", "One process, no runtime under it") }
            item { StoryCard() }
            item { AboutHeading("Under the hood", "The small stack behind the app") }
            item { TechCard() }
            item { AboutHeading("Project", "Source, releases and issues") }
            item {
                LinksCard(
                    onSource = { open(AppInfo.GITHUB_URL) },
                    onReleases = { open(AppInfo.RELEASES_URL) },
                    onIssues = { open("${AppInfo.GITHUB_URL}/issues") },
                    onLicense = { open("${AppInfo.GITHUB_URL}/blob/master/LICENSE") },
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
                "One local endpoint for the AI providers you already use.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = NineRouterColors.TextMain,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A native Android build of the 9Router gateway. It routes OpenAI, Claude, Gemini and Responses traffic across your connected providers, with combos, fallback, vision and chat on the device.",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Kotlin")
                Pill("Loopback")
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
private fun DeveloperCard(onGitHub: () -> Unit) {
    Surface(
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(88.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(NineRouterColors.Brand, NineRouterColors.BrandOrange),
                            ),
                            CircleShape,
                        )
                        .padding(3.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.itswill00),
                        contentDescription = "itswill00 GitHub profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Built by",
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.TextSubtle,
                    )
                    Text(
                        "itswill00",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = NineRouterColors.TextMain,
                    )
                    Text(
                        "Creator & maintainer",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "github.com/itswill00",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = NineRouterColors.BrandOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "The native Android client, loopback gateway and in-app chat are designed and maintained by itswill00.",
                style = MaterialTheme.typography.bodySmall,
                color = NineRouterColors.TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryButton("GitHub profile", modifier = Modifier.fillMaxWidth(), onClick = onGitHub)
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
private fun LinksCard(
    onSource: () -> Unit,
    onReleases: () -> Unit,
    onIssues: () -> Unit,
    onLicense: () -> Unit,
) {
    Surface(
        color = NineRouterColors.Surface,
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Source code", modifier = Modifier.weight(1f), onClick = onSource)
                SecondaryButton("Releases", modifier = Modifier.weight(1f), onClick = onReleases)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Issues", modifier = Modifier.weight(1f), onClick = onIssues)
                SecondaryButton("License (MIT)", modifier = Modifier.weight(1f), onClick = onLicense)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Links open in your browser.",
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
            "Native Android build by itswill00. 9Router is MIT licensed.",
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
