package com.ninerouter.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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

/** Width thresholds follow the Material window-size classes. */
private val SidebarCompactWidth = 76.dp
private val SidebarExpandedWidth = 232.dp
private val SidebarWideBreakpoint = 600.dp

@Composable
fun AppShell(refreshTrigger: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = Store.revision
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
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

    BoxWithConstraints(Modifier.fillMaxSize().background(NineRouterColors.Bg)) {
        val wide = maxWidth >= SidebarWideBreakpoint
        val sidebarExpanded = wide && !sidebarCollapsed

        Row(Modifier.fillMaxSize()) {
            Sidebar(
                expanded = sidebarExpanded,
                canToggle = wide,
                selectedTab = tab,
                chatOpen = showChat,
                onSelectTab = {
                    showChat = false
                    tab = it
                },
                onOpenChat = { showChat = true },
                onToggle = { sidebarCollapsed = !sidebarCollapsed },
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(NineRouterColors.BorderSubtle))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val updatePending = updateState is UpdateManager.State.Available ||
                    updateState is UpdateManager.State.Downloading ||
                    updateState is UpdateManager.State.Ready
                val updateLabel = if (updatePending) {
                    (updateState as? UpdateManager.State.Available)
                        ?.let { "Update ${it.release.version}" }
                        ?: "Update"
                } else null
                TopBar(
                    title = if (showChat) "Basic Chat" else navItems[tab].title,
                    subtitle = if (running) "Gateway running" else "Gateway stopped",
                    running = running,
                    showBack = showChat,
                    onBack = { showChat = false },
                    updateLabel = updateLabel,
                    onUpdateClick = {
                        showChat = false
                        tab = 5
                    },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    AnimatedContent(
                        targetState = if (showChat) -1 else tab,
                        modifier = Modifier.fillMaxSize(),
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
                        overflow = TextOverflow.Ellipsis,
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

/**
 * Persistent edge navigation. Compact widths get a narrow icon+label rail;
 * wide windows get a full sidebar that can collapse. Chat sits first as a
 * primary destination, separated from the configuration sections.
 */
@Composable
private fun Sidebar(
    expanded: Boolean,
    canToggle: Boolean,
    selectedTab: Int,
    chatOpen: Boolean,
    onSelectTab: (Int) -> Unit,
    onOpenChat: () -> Unit,
    onToggle: () -> Unit,
) {
    val targetWidth = if (expanded) SidebarExpandedWidth else SidebarCompactWidth
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "sidebarWidth",
    )
    Surface(
        modifier = Modifier.width(width).fillMaxHeight(),
        color = NineRouterColors.BgAlt,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showLabels = maxWidth >= 150.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                BrandHeader(showLabels)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SidebarItem(
                        icon = R.drawable.ic_chat,
                        label = "Basic Chat",
                        compactLabel = "Chat",
                        selected = chatOpen,
                        showLabels = showLabels,
                        onClick = onOpenChat,
                    )
                    SidebarDivider()
                    navItems.forEachIndexed { index, item ->
                        SidebarItem(
                            icon = item.icon,
                            label = item.title,
                            selected = !chatOpen && selectedTab == index,
                            showLabels = showLabels,
                            onClick = { onSelectTab(index) },
                        )
                    }
                }
                if (canToggle) {
                    Spacer(Modifier.height(6.dp))
                    SidebarDivider()
                    SidebarToggle(expanded = expanded, showLabels = showLabels, onClick = onToggle)
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(showLabels: Boolean) {
    if (showLabels) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.brand_mark),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "9Router",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NineRouterColors.TextMain,
                )
                Text(
                    "Android gateway",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                )
            }
        }
    } else {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.brand_mark),
                contentDescription = "9Router",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun SidebarItem(
    icon: Int,
    label: String,
    compactLabel: String = label,
    selected: Boolean,
    showLabels: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(200),
        label = "navBackground",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) NineRouterColors.BrandOrange else NineRouterColors.TextMuted,
        animationSpec = tween(200),
        label = "navTint",
    )
    val base = Modifier
        .fillMaxWidth()
        .clip(NineRouterShapes.small)
        .background(background)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)

    if (showLabels) {
        Row(
            base.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(11.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column(
            base.heightIn(min = 52.dp).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                compactLabel,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SidebarDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(1.dp)
            .background(NineRouterColors.BorderSubtle),
    )
}

@Composable
private fun SidebarToggle(expanded: Boolean, showLabels: Boolean, onClick: () -> Unit) {
    val base = Modifier
        .fillMaxWidth()
        .clip(NineRouterShapes.small)
        .clickable(role = Role.Button, onClick = onClick)
    if (showLabels) {
        Row(
            base.heightIn(min = 48.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_left),
                contentDescription = null,
                tint = NineRouterColors.TextSubtle,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Collapse",
                style = MaterialTheme.typography.labelMedium,
                color = NineRouterColors.TextSubtle,
            )
        }
    } else {
        Box(base.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = "Expand sidebar",
                tint = NineRouterColors.TextSubtle,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    running: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    updateLabel: String?,
    onUpdateClick: () -> Unit,
) {
    Surface(color = NineRouterColors.BgAlt) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = showBack,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            ) {
                Row {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_revert),
                            contentDescription = "Close chat",
                            tint = NineRouterColors.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NineRouterColors.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (running) NineRouterColors.Success else NineRouterColors.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (updateLabel != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = onUpdateClick,
                    color = NineRouterColors.BrandOrange.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        updateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = NineRouterColors.BrandOrange,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Preview(name = "Sidebar expanded", widthDp = 232, heightDp = 760, showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
private fun SidebarExpandedPreview() {
    NineRouterTheme {
        Sidebar(
            expanded = true,
            canToggle = true,
            selectedTab = 1,
            chatOpen = false,
            onSelectTab = {},
            onOpenChat = {},
            onToggle = {},
        )
    }
}

@Preview(name = "Sidebar compact", widthDp = 76, heightDp = 640, showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
private fun SidebarCompactPreview() {
    NineRouterTheme {
        Sidebar(
            expanded = false,
            canToggle = false,
            selectedTab = 2,
            chatOpen = false,
            onSelectTab = {},
            onOpenChat = {},
            onToggle = {},
        )
    }
}

@Preview(name = "Top bar", widthDp = 360, heightDp = 64, showBackground = true, backgroundColor = 0xFF1F1F1E)
@Composable
private fun TopBarPreview() {
    NineRouterTheme {
        TopBar(
            title = "Endpoint",
            subtitle = "Gateway running",
            running = true,
            showBack = false,
            onBack = {},
            updateLabel = "Update 0.1.17",
            onUpdateClick = {},
        )
    }
}
