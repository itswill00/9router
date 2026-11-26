package com.ninerouter.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.ninerouter.android.AppInfo
import com.ninerouter.android.R
import com.ninerouter.android.data.Store
import com.ninerouter.android.server.GatewayState
import com.ninerouter.android.update.UpdateManager
import kotlinx.coroutines.launch

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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { UpdateManager.autoCheck(context) }
    LaunchedEffect(updateState) {
        val available = updateState as? UpdateManager.State.Available ?: return@LaunchedEffect
        if (UpdateManager.shouldAnnounce(context, available.release.version)) {
            updatePrompt = available.release
        }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    // Predictive Back: close the drawer first, then chat, then return home.
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = drawerState.isOpen || showChat || tab != 0,
        onBackCompleted = {
            when {
                drawerState.isOpen -> closeDrawer()
                showChat -> showChat = false
                else -> tab = 0
            }
        },
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = NineRouterColors.BgAlt,
                drawerContentColor = NineRouterColors.TextMain,
                modifier = Modifier.width(292.dp),
            ) {
                DrawerContent(
                    selectedTab = tab,
                    chatOpen = showChat,
                    onSelectTab = { index ->
                        closeDrawer()
                        showChat = false
                        tab = index
                    },
                    onOpenChat = {
                        closeDrawer()
                        showChat = true
                    },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            val updatePending = updateState is UpdateManager.State.Available ||
                updateState is UpdateManager.State.Downloading ||
                updateState is UpdateManager.State.Ready
            val updateLabel = if (updatePending) {
                (updateState as? UpdateManager.State.Available)?.let { "Update ${it.release.version}" } ?: "Update"
            } else null
            TopBar(
                title = if (showChat) "Basic Chat" else navItems[tab].title,
                subtitle = if (running) "Gateway running" else "Gateway stopped",
                running = running,
                showBack = showChat,
                onBack = { showChat = false },
                onMenu = { scope.launch { drawerState.open() } },
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

@Composable
private fun DrawerContent(
    selectedTab: Int,
    chatOpen: Boolean,
    onSelectTab: (Int) -> Unit,
    onOpenChat: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        BrandHeader()
        Spacer(Modifier.height(14.dp))
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
                selected = chatOpen,
                onClick = onOpenChat,
            )
            SidebarDivider()
            navItems.forEachIndexed { index, item ->
                SidebarItem(
                    icon = item.icon,
                    label = item.title,
                    selected = !chatOpen && selectedTab == index,
                    onClick = { onSelectTab(index) },
                )
            }
        }
        SidebarDivider()
        Text(
            "9Router ${AppInfo.VERSION}",
            style = MaterialTheme.typography.labelSmall,
            color = NineRouterColors.TextSubtle,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BrandHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "9Router",
                style = MaterialTheme.typography.titleMedium,
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
}

@Composable
private fun SidebarItem(
    icon: Int,
    label: String,
    selected: Boolean,
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(NineRouterShapes.small)
            .background(background)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun TopBar(
    title: String,
    subtitle: String,
    running: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    updateLabel: String?,
    onUpdateClick: () -> Unit,
) {
    Surface(color = NineRouterColors.BgAlt) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = !showBack,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            ) {
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Open navigation",
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = showBack,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_revert),
                        contentDescription = "Close chat",
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
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

@Preview(name = "Drawer", widthDp = 292, heightDp = 720, showBackground = true, backgroundColor = 0xFF1F1F1E)
@Composable
private fun DrawerPreview() {
    NineRouterTheme {
        Surface(color = NineRouterColors.BgAlt) {
            DrawerContent(selectedTab = 1, chatOpen = false, onSelectTab = {}, onOpenChat = {})
        }
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
            onMenu = {},
            updateLabel = "Update 0.1.18",
            onUpdateClick = {},
        )
    }
}
