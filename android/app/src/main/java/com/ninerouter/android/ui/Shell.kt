package com.ninerouter.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninerouter.android.R
import com.ninerouter.android.server.GatewayState

private data class NavItem(val title: String, val icon: Int)

private val navItems = listOf(
    NavItem("Endpoint", R.drawable.ic_nav_endpoint),
    NavItem("Providers", R.drawable.ic_nav_providers),
    NavItem("Combos", R.drawable.ic_nav_combos),
    NavItem("Usage", R.drawable.ic_nav_usage),
    NavItem("Settings", R.drawable.ic_nav_settings),
)

private val overflowItems = listOf(
    "Basic Chat",
    "Console Log",
    "Quota Tracker",
    "MITM",
    "CLI Tools",
    "Proxy Pools",
    "Token Saver",
    "Translator",
    "Skills",
)

@Composable
fun AppShell(refreshTrigger: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var infoScreen by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    val running by GatewayState.running.collectAsState()

    Scaffold(
        containerColor = NineRouterColors.Bg,
        topBar = {
            Surface(color = NineRouterColors.BgAlt, shadowElevation = 0.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (infoScreen != null) {
                        IconButton(onClick = { infoScreen = null }) {
                            Icon(
                                painter = painterResource(android.R.drawable.ic_menu_revert),
                                contentDescription = "Back",
                                tint = NineRouterColors.TextMuted,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Image(
                        painter = painterResource(R.drawable.brand_mark),
                        contentDescription = "9Router logo",
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            infoScreen ?: "Endpoint Proxy",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = NineRouterColors.TextMain,
                        )
                        Text(
                            if (infoScreen == null) "v0.5.123 · Android" else "9Router",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.TextSubtle,
                        )
                    }
                    StatusChip(running)
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text("⋮", color = NineRouterColors.TextMuted, style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = NineRouterColors.Surface,
                        ) {
                            overflowItems.forEach { label ->
                                DropdownMenuItem(
                                    text = { Text(label, color = NineRouterColors.TextMain) },
                                    onClick = {
                                        infoScreen = label
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = NineRouterColors.Surface,
                tonalElevation = 0.dp,
            ) {
                navItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = infoScreen == null && tab == index,
                        onClick = { infoScreen = null; tab = index },
                        icon = {
                            Icon(
                                painter = painterResource(item.icon),
                                contentDescription = item.title,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = { Text(item.title, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NineRouterColors.BrandOrange,
                            selectedTextColor = NineRouterColors.TextMain,
                            unselectedIconColor = NineRouterColors.TextSubtle,
                            unselectedTextColor = NineRouterColors.TextSubtle,
                            indicatorColor = NineRouterColors.Surface2,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val screen = infoScreen) {
                "Basic Chat" -> ChatScreen()
                null -> when (tab) {
                    0 -> EndpointScreen(refreshTrigger)
                    1 -> ProvidersScreen(refreshTrigger)
                    2 -> CombosScreen(refreshTrigger)
                    3 -> UsageScreen(refreshTrigger)
                    4 -> SettingsScreen(refreshTrigger)
                    else -> EndpointScreen(refreshTrigger)
                }
                else -> InfoScreen(screen)
            }
        }
    }
}

@Composable
private fun StatusChip(running: Boolean) {
    Surface(
        color = if (running) NineRouterColors.Success.copy(alpha = 0.12f) else NineRouterColors.Surface2,
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp).background(
                    if (running) NineRouterColors.Success else NineRouterColors.TextSubtle,
                    RoundedCornerShape(50),
                )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (running) "Running" else "Stopped",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) NineRouterColors.Success else NineRouterColors.TextMuted,
            )
        }
    }
}
