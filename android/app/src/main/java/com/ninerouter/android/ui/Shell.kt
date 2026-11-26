package com.ninerouter.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.ninerouter.android.AppInfo
import com.ninerouter.android.R
import com.ninerouter.android.data.Store
import com.ninerouter.android.server.GatewayState
import com.ninerouter.android.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * Navigation mirrors the full 9Router dashboard sidebar: the same groups,
 * labels, order and destinations, with Basic Chat moved to the top bar because
 * the upstream sidebar keeps it hidden.
 */
private enum class Screen(
    val label: String,
    val title: String,
    val subtitle: String,
    val icon: Int,
) {
    Endpoint("Endpoint & Key", "Endpoint & Key", "API endpoint configuration", R.drawable.ic_nav_endpoint),
    Providers("Providers", "Providers", "Manage your AI provider connections", R.drawable.ic_nav_providers),
    Combos("Combo & Vision Adapter", "Combos", "Fallback, round robin, smart router and fusion", R.drawable.ic_nav_combos),
    Usage("Usage", "Usage & Analytics", "Monitor your API usage, token consumption, and request logs", R.drawable.ic_nav_usage),
    Quota("Quota Tracker", "Quota Tracker", "Track and manage your API quota limits", R.drawable.ic_nav_quota),
    TokenSaver("Token Saver", "Token Saver", "Compress prompts and outputs to save tokens", R.drawable.ic_nav_savings),
    CliTools("CLI Tools", "CLI Tools", "Configure CLI tools", R.drawable.ic_nav_terminal),
    MediaEmbedding("Embedding", "Embedding", "Manage your Embedding providers", R.drawable.ic_nav_embedding),
    MediaImage("Text to Image", "Text to Image", "Manage your Text to Image providers", R.drawable.ic_nav_image),
    MediaVideo("Video", "Video", "Manage your Video providers", R.drawable.ic_nav_video),
    MediaTts("Text To Speech", "Text To Speech", "Manage your Text To Speech providers", R.drawable.ic_nav_tts),
    MediaStt("Speech To Text", "Speech To Text", "Manage your Speech To Text providers", R.drawable.ic_nav_stt),
    MediaSystemOne("System One", "System One", "Manage your System One providers", R.drawable.ic_nav_systemone),
    MediaWeb("Web Fetch & Search", "Web Fetch & Search", "Manage your web search and fetch providers", R.drawable.ic_nav_web),
    ProxyPools("Proxy Pools", "Proxy Pools", "Manage your proxy pool configurations", R.drawable.ic_nav_proxy),
    Skills("Skills", "Agent Skills", "Copy a link and paste to your AI to use 9Router — no install needed", R.drawable.ic_nav_skills),
    ConsoleLog("Console Log", "Console Log", "Live request output from the gateway", R.drawable.ic_nav_logs),
    Translator("Translator", "Translator", "Debug translation flow between formats", R.drawable.ic_nav_translate),
    About("About", "About", "Project and developer", R.drawable.ic_nav_info),
    Settings("Settings", "Settings", "Manage your preferences", R.drawable.ic_nav_settings),
    ;

    companion object {
        fun from(name: String): Screen = values().firstOrNull { it.name == name } ?: Endpoint
    }
}

private val mainScreens = listOf(
    Screen.Endpoint,
    Screen.Providers,
    Screen.Combos,
    Screen.Usage,
    Screen.Quota,
    Screen.TokenSaver,
    Screen.CliTools,
)

private val mediaScreens = listOf(
    Screen.MediaEmbedding,
    Screen.MediaImage,
    Screen.MediaVideo,
    Screen.MediaTts,
    Screen.MediaStt,
    Screen.MediaSystemOne,
    Screen.MediaWeb,
)

private val systemScreens = listOf(
    Screen.ProxyPools,
    Screen.Skills,
    Screen.ConsoleLog,
    Screen.Translator,
)

private fun Screen.mediaKind(): MediaKind? = when (this) {
    Screen.MediaEmbedding -> MediaKind.Embedding
    Screen.MediaImage -> MediaKind.TextToImage
    Screen.MediaVideo -> MediaKind.Video
    Screen.MediaTts -> MediaKind.TextToSpeech
    Screen.MediaStt -> MediaKind.SpeechToText
    Screen.MediaSystemOne -> MediaKind.SystemOne
    Screen.MediaWeb -> MediaKind.Web
    else -> null
}

@Composable
fun AppShell(refreshTrigger: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val revision = storeRevision().value
    var screenName by rememberSaveable { mutableStateOf(Screen.Endpoint.name) }
    val screen = Screen.from(screenName)
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

    fun select(next: Screen) {
        closeDrawer()
        showChat = false
        screenName = next.name
    }

    // Predictive Back: close the drawer first, then chat, then return home.
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = drawerState.isOpen || showChat || screen != Screen.Endpoint,
        onBackCompleted = {
            when {
                drawerState.isOpen -> closeDrawer()
                showChat -> showChat = false
                else -> screenName = Screen.Endpoint.name
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
                modifier = Modifier.width(288.dp),
            ) {
                DrawerContent(
                    selected = screen,
                    chatOpen = showChat,
                    onSelect = ::select,
                    onClose = ::closeDrawer,
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
                title = if (showChat) "Basic Chat" else screen.title,
                subtitle = if (showChat) "Chat through your configured providers" else screen.subtitle,
                running = running,
                showBack = showChat,
                onBack = { showChat = false },
                onMenu = { scope.launch { drawerState.open() } },
                onOpenChat = {
                    showChat = true
                },
                updateLabel = updateLabel,
                onUpdateClick = {
                    showChat = false
                    screenName = Screen.Settings.name
                },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                val target = if (showChat) "chat" else screen.name
                AnimatedContent(
                    targetState = target,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        fun order(key: String) = if (key == "chat") -1 else Screen.from(key).ordinal
                        val forward = order(targetState) > order(initialState)
                        val enter = slideInHorizontally(tween(250)) { if (forward) it / 5 else -it / 5 } +
                            fadeIn(tween(220))
                        val exit = slideOutHorizontally(tween(200)) { if (forward) -it / 5 else it / 5 } +
                            fadeOut(tween(150))
                        enter togetherWith exit
                    },
                    label = "tabContent",
                ) { key ->
                    if (key == "chat") {
                        ChatScreen()
                    } else {
                        when (Screen.from(key)) {
                            Screen.Endpoint -> EndpointScreen(refreshTrigger)
                            Screen.Providers -> ProvidersScreen(refreshTrigger)
                            Screen.Combos -> CombosScreen(refreshTrigger)
                            Screen.Usage -> UsageScreen(refreshTrigger)
                            Screen.Quota -> QuotaScreen(refreshTrigger) { select(Screen.Providers) }
                            Screen.TokenSaver -> TokenSaverScreen(refreshTrigger)
                            Screen.CliTools -> CliToolsScreen()
                            Screen.MediaEmbedding,
                            Screen.MediaImage,
                            Screen.MediaVideo,
                            Screen.MediaTts,
                            Screen.MediaStt,
                            Screen.MediaSystemOne,
                            Screen.MediaWeb,
                            -> MediaProviderScreen(
                                kind = Screen.from(key).mediaKind() ?: MediaKind.Embedding,
                                onOpenProviders = { select(Screen.Providers) },
                            )
                            Screen.ProxyPools -> ProxyPoolsScreen()
                            Screen.Skills -> SkillsScreen()
                            Screen.ConsoleLog -> LogsScreen()
                            Screen.Translator -> TranslatorScreen()
                            Screen.About -> AboutScreen()
                            Screen.Settings -> SettingsScreen(
                                refresh = refreshTrigger,
                                onOpenTokenSaver = { select(Screen.TokenSaver) },
                                onOpenCombos = { select(Screen.Combos) },
                                onOpenAbout = { select(Screen.About) },
                            )
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
                    screenName = Screen.Settings.name
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
    selected: Screen,
    chatOpen: Boolean,
    onSelect: (Screen) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var mediaOpen by rememberSaveable { mutableStateOf(selected in mediaScreens) }
    LaunchedEffect(selected) {
        if (selected in mediaScreens) mediaOpen = true
    }

    fun openUrl(url: String) {
        onClose()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TrafficLights()
        Spacer(Modifier.height(2.dp))
        BrandHeader()
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            mainScreens.forEach { item ->
                SidebarItem(
                    icon = item.icon,
                    label = item.label,
                    selected = !chatOpen && selected == item,
                    onClick = { onSelect(item) },
                )
            }

            SidebarSectionLabel("System")
            SidebarItem(
                icon = R.drawable.ic_nav_media,
                label = "Media Providers",
                selected = selected in mediaScreens,
                trailing = {
                    val rotation by animateFloatAsState(
                        targetValue = if (mediaOpen) 180f else 0f,
                        animationSpec = tween(200),
                        label = "mediaChevron",
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = if (mediaOpen) "Collapse media providers" else "Expand media providers",
                        tint = if (selected in mediaScreens) NineRouterColors.BrandOrange else NineRouterColors.TextSubtle,
                        modifier = Modifier.size(16.dp).rotate(rotation),
                    )
                },
                onClick = { mediaOpen = !mediaOpen },
            )
            AnimatedVisibility(
                visible = mediaOpen,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    mediaScreens.forEach { item ->
                        SidebarSubItem(
                            icon = item.icon,
                            label = item.label,
                            selected = !chatOpen && selected == item,
                            onClick = { onSelect(item) },
                        )
                    }
                }
            }

            systemScreens.forEach { item ->
                SidebarItem(
                    icon = item.icon,
                    label = item.label,
                    selected = !chatOpen && selected == item,
                    onClick = { onSelect(item) },
                )
            }

            SidebarItem(
                icon = R.drawable.ic_nav_computer,
                label = "9Remote",
                selected = false,
                trailing = {
                    Surface(
                        color = NineRouterColors.BrandOrange.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "HOT",
                            style = MaterialTheme.typography.labelSmall,
                            color = NineRouterColors.BrandOrange,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                },
                onClick = { openUrl("https://9remote.cc") },
            )
            SidebarItem(
                icon = R.drawable.ic_nav_translate,
                label = "9English",
                selected = false,
                onClick = { openUrl("https://9english.net/") },
            )
            SidebarItem(
                icon = Screen.About.icon,
                label = "About",
                selected = !chatOpen && selected == Screen.About,
                onClick = { onSelect(Screen.About) },
            )
            SidebarItem(
                icon = Screen.Settings.icon,
                label = "Settings",
                selected = !chatOpen && selected == Screen.Settings,
                onClick = { onSelect(Screen.Settings) },
            )
        }
    }
}

@Composable
private fun TrafficLights() {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(Color(0xFFFF5F56), Color(0xFFFFBD2E), Color(0xFF27C93F)).forEach { color ->
            Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
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
                "v${AppInfo.VERSION}",
                style = MaterialTheme.typography.labelSmall,
                color = NineRouterColors.TextSubtle,
            )
        }
    }
}

@Composable
private fun SidebarSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = NineRouterColors.TextSubtle,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 5.dp),
    )
}

@Composable
private fun SidebarItem(
    icon: Int,
    label: String,
    selected: Boolean,
    trailing: (@Composable () -> Unit)? = null,
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
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

@Composable
private fun SidebarSubItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) NineRouterColors.BrandOrange else NineRouterColors.TextMuted,
        animationSpec = tween(200),
        label = "subNavTint",
    )
    val background by animateColorAsState(
        targetValue = if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(200),
        label = "subNavBackground",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(NineRouterShapes.small)
            .background(background)
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    running: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onOpenChat: () -> Unit,
    updateLabel: String?,
    onUpdateClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Surface(modifier = Modifier.zIndex(1f), color = NineRouterColors.BgAlt) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = !showBack,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            ) {
                IconButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        onMenu()
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(NineRouterColors.Surface2.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Open navigation",
                            tint = NineRouterColors.TextMain,
                            modifier = Modifier.size(23.dp),
                        )
                    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NineRouterColors.TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (running) NineRouterColors.Success else NineRouterColors.TextSubtle,
                                RoundedCornerShape(50),
                            )
                            .semantics {
                                contentDescription = if (running) "Gateway running" else "Gateway stopped"
                            },
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!showBack) {
                IconButton(onClick = onOpenChat, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat),
                        contentDescription = "Open Basic Chat",
                        tint = NineRouterColors.TextMuted,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            if (updateLabel != null) {
                Spacer(Modifier.width(4.dp))
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

@Preview(name = "Drawer", widthDp = 288, heightDp = 760, showBackground = true, backgroundColor = 0xFF1F1F1E)
@Composable
private fun DrawerPreview() {
    NineRouterTheme {
        Surface(color = NineRouterColors.BgAlt) {
            DrawerContent(
                selected = Screen.Providers,
                chatOpen = false,
                onSelect = {},
                onClose = {},
            )
        }
    }
}

@Preview(name = "Top bar", widthDp = 360, heightDp = 64, showBackground = true, backgroundColor = 0xFF1F1F1E)
@Composable
private fun TopBarPreview() {
    NineRouterTheme {
        TopBar(
            title = "Endpoint & Key",
            subtitle = "API endpoint configuration",
            running = true,
            showBack = false,
            onBack = {},
            onMenu = {},
            onOpenChat = {},
            updateLabel = "Update 0.1.21",
            onUpdateClick = {},
        )
    }
}
