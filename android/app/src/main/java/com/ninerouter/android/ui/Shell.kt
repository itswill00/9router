package com.ninerouter.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.ninerouter.android.R
import com.ninerouter.android.data.Store
import com.ninerouter.android.server.GatewayState
import com.ninerouter.android.update.UpdateManager

private data class NavItem(val title: String, val icon: Int)

private val navItems = listOf(
    NavItem("Endpoint", R.drawable.ic_nav_endpoint),
    NavItem("Providers", R.drawable.ic_nav_providers),
    NavItem("Combos", R.drawable.ic_nav_combos),
    NavItem("Usage", R.drawable.ic_nav_usage),
    NavItem("Logs", R.drawable.ic_nav_logs),
    NavItem("Settings", R.drawable.ic_nav_settings),
)

@Composable
fun AppShell(refreshTrigger: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    val running by GatewayState.running.collectAsState()
    val updateState by UpdateManager.state.collectAsState()
    val context = LocalContext.current
    var updatePrompt by remember { mutableStateOf<UpdateManager.Release?>(null) }

    LaunchedEffect(Unit) { UpdateManager.autoCheck(context) }
    LaunchedEffect(updateState) {
        val available = updateState as? UpdateManager.State.Available ?: return@LaunchedEffect
        if (UpdateManager.shouldAnnounce(context, available.release.version)) {
            updatePrompt = available.release
        }
    }

    // Predictive Back: close chat, then return to Endpoint. Only the home tab
    // with no chat open lets Android finish the activity.
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = showChat || tab != 0,
        onBackCompleted = {
            if (showChat) showChat = false else tab = 0
        },
    )

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
                    AnimatedVisibility(
                        visible = showChat,
                        enter = fadeIn(tween(180)) + expandHorizontally(),
                        exit = fadeOut(tween(140)) + shrinkHorizontally(),
                    ) {
                        Row {
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
                            if (showChat) "Basic Chat · ${if (running) "running" else "stopped"}"
                            else "${navItems[tab].title} · ${if (running) "running" else "stopped"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (running) NineRouterColors.Success else NineRouterColors.TextSubtle,
                        )
                    }
                    val updatePending = updateState is UpdateManager.State.Available ||
                        updateState is UpdateManager.State.Downloading ||
                        updateState is UpdateManager.State.Ready
                    if (!showChat && updatePending) {
                        val updateLabel = (updateState as? UpdateManager.State.Available)
                            ?.let { "Update ${it.release.version}" }
                            ?: "Update"
                        Surface(
                            color = NineRouterColors.BrandOrange.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.clickable { showChat = false; tab = 5 },
                        ) {
                            Text(
                                updateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = NineRouterColors.BrandOrange,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    AnimatedVisibility(
                        visible = !showChat,
                        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.8f),
                        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
                    ) {
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
            AnimatedVisibility(
                visible = !showChat,
                enter = slideInVertically { it } + fadeIn(tween(180)),
                exit = slideOutVertically { it } + fadeOut(tween(140)),
            ) {
                Surface(color = NineRouterColors.Surface) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(54.dp),
                    ) {
                        navItems.forEachIndexed { index, item ->
                            val selected = !showChat && tab == index
                            val pillColor by animateColorAsState(
                                targetValue = if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.15f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                animationSpec = tween(220),
                                label = "navPill",
                            )
                            val iconTint by animateColorAsState(
                                targetValue = if (selected) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                                animationSpec = tween(220),
                                label = "navIcon",
                            )
                            val labelColor by animateColorAsState(
                                targetValue = if (selected) NineRouterColors.TextMain else NineRouterColors.TextSubtle,
                                animationSpec = tween(220),
                                label = "navLabel",
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clickable { showChat = false; tab = index },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            ) {
                                Box(
                                    Modifier
                                        .background(pillColor, RoundedCornerShape(999.dp))
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(item.icon),
                                        contentDescription = item.title,
                                        tint = iconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    item.title,
                                    fontSize = 10.sp,
                                    color = labelColor,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AnimatedContent(
                targetState = if (showChat) -1 else tab,
                transitionSpec = {
                    val forward = when {
                        targetState == -1 -> true
                        initialState == -1 -> false
                        else -> targetState > initialState
                    }
                    val enter = slideInHorizontally(tween(250)) { if (forward) it / 5 else -it / 5 } +
                        fadeIn(tween(220))
                    val exit = slideOutHorizontally(tween(200)) { if (forward) -it / 5 else it / 5 } +
                        fadeOut(tween(150))
                    enter togetherWith exit
                },
                label = "tabContent",
            ) { target ->
                when (target) {
                    -1 -> ChatScreen()
                    0 -> EndpointScreen(refreshTrigger)
                    1 -> ProvidersScreen(refreshTrigger)
                    2 -> CombosScreen(refreshTrigger)
                    3 -> UsageScreen(refreshTrigger)
                    4 -> LogsScreen()
                    5 -> SettingsScreen(refreshTrigger)
                }
            }
        }
    }

    updatePrompt?.let { release ->
        AlertDialog(
            onDismissRequest = {
                UpdateManager.markAnnounced(context, release.version)
                updatePrompt = null
            },
            containerColor = NineRouterColors.Surface,
            title = { Text("Update available", color = NineRouterColors.TextMain) },
            text = {
                Column {
                    Text(
                        "9Router ${release.version} is ready to install.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NineRouterColors.TextMain,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        release.notes.trim().ifBlank { "No release notes." }.take(700),
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                        maxLines = 8,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    UpdateManager.markAnnounced(context, release.version)
                    updatePrompt = null
                    showChat = false
                    tab = 5
                }) { Text("Update", color = NineRouterColors.BrandOrange) }
            },
            dismissButton = {
                TextButton(onClick = {
                    UpdateManager.markAnnounced(context, release.version)
                    updatePrompt = null
                }) { Text("Later") }
            },
        )
    }
}
