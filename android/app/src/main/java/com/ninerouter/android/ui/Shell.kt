package com.ninerouter.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
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

@Composable
fun AppShell(refreshTrigger: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var showChat by remember { mutableStateOf(false) }
    val running by GatewayState.running.collectAsState()

    Scaffold(
        containerColor = NineRouterColors.Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = NineRouterColors.BgAlt, shadowElevation = 0.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showChat) {
                        IconButton(onClick = { showChat = false }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(android.R.drawable.ic_menu_revert),
                                contentDescription = "Back",
                                tint = NineRouterColors.TextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Image(
                        painter = painterResource(R.drawable.brand_mark),
                        contentDescription = "9Router logo",
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (showChat) "Basic Chat" else "9Router",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = NineRouterColors.TextMain,
                        )
                        Text(
                            "v0.5.123 · ${if (running) "running" else "stopped"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (running) NineRouterColors.Success else NineRouterColors.TextSubtle,
                        )
                    }
                    if (!showChat) {
                        IconButton(onClick = { showChat = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat),
                                contentDescription = "Basic Chat",
                                tint = NineRouterColors.TextMuted,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = NineRouterColors.Surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(54.dp),
                ) {
                    navItems.forEachIndexed { index, item ->
                        val selected = !showChat && tab == index
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clickable { showChat = false; tab = index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(item.icon),
                                contentDescription = item.title,
                                tint = if (selected) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.title,
                                fontSize = 10.sp,
                                color = if (selected) NineRouterColors.TextMain else NineRouterColors.TextSubtle,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showChat) {
                ChatScreen()
            } else {
                when (tab) {
                    0 -> EndpointScreen(refreshTrigger)
                    1 -> ProvidersScreen(refreshTrigger)
                    2 -> CombosScreen(refreshTrigger)
                    3 -> UsageScreen(refreshTrigger)
                    4 -> SettingsScreen(refreshTrigger)
                }
            }
        }
    }
}
