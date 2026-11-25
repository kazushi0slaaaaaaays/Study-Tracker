package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.authStateFlow
import com.example.data.LocalDeviceStore
import com.example.data.StudyRepository
import com.example.nativeandroid.StudyBlockerAccessibilityService
import com.example.nativeandroid.TimerForegroundService
import com.example.ui.AuthScreen
import com.example.ui.BlockerScreen
import com.example.ui.FilesScreen
import com.example.ui.FullscreenTimerOverlay
import com.example.ui.OfflineBanner
import com.example.ui.PlanScreen
import com.example.ui.SettingsScreen
import com.example.ui.SetupChecklistScreen
import com.example.ui.SkeletonLoadingBlock
import com.example.ui.StatsScreen
import com.example.ui.StudyTimerScreen
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.StudyTrackerTheme
import com.example.ui.theme.parseHexColor
import com.example.viewmodel.StudyAnalytics
import com.example.viewmodel.StudyAppState
import com.example.viewmodel.StudyTimerViewModel
import com.example.viewmodel.StudyViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

enum class MainTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    TIMER("timer", "Timer", Icons.Rounded.Timer, Icons.Outlined.Timer),
    PLAN("plan", "Plan", Icons.Rounded.CheckCircle, Icons.Outlined.CheckCircle),
    STATS("stats", "Stats", Icons.Rounded.BarChart, Icons.Outlined.BarChart),
    FILES("files", "Files", Icons.Rounded.FolderOpen, Icons.Outlined.FolderOpen),
    BLOCKER("blocker", "Blocker", Icons.Rounded.Shield, Icons.Outlined.Shield),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudyTrackerRoot()
        }
    }
}

@Composable
fun StudyTrackerRoot() {
    val authFlow = remember { Firebase.auth.authStateFlow() }
    val currentUser by authFlow.collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    if (currentUser == null) {
        StudyTrackerTheme(themeMode = "dark", accentHex = "#6C7BFF") {
            AuthScreen(onAuthSuccess = {})
        }
    } else {
        val uid = currentUser!!.uid
        key(uid) {
            AuthenticatedStudyApp(uid = uid)
        }
    }
}

@Composable
private fun AuthenticatedStudyApp(uid: String) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val localStore = remember(appContext) { LocalDeviceStore(appContext) }
    val repository = remember(appContext) { StudyRepository(appContext) }

    val viewModel: StudyViewModel = viewModel(
        key = uid,
        factory = viewModelFactory {
            initializer {
                StudyViewModel(
                    repository = repository,
                    localStore = localStore,
                    appContext = appContext,
                    currentUserId = uid,
                )
            }
        },
    )

    val timerViewModel: StudyTimerViewModel = viewModel(
        key = "timer_$uid",
        factory = viewModelFactory {
            initializer {
                StudyTimerViewModel(studyViewModel = viewModel)
            }
        },
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Sync permissions, file statuses, and any locally triggered rule locks on resume
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
                viewModel.refreshFileStatuses()
                viewModel.syncLocalLocksToFirestore()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Listen to local service broadcasts (notification timer buttons & newly locked blocker rules)
    DisposableEffect(appContext, viewModel) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    TimerForegroundService.BROADCAST_TIMER_COMMAND -> {
                        when (localStore.consumePendingTimerCommand()) {
                            TimerForegroundService.CMD_PAUSE -> viewModel.pauseTimer()
                            TimerForegroundService.CMD_RESUME -> viewModel.resumeTimer()
                            TimerForegroundService.CMD_STOP -> viewModel.stopTimerAndLog()
                        }
                    }
                    StudyBlockerAccessibilityService.BROADCAST_RULE_LOCKED -> {
                        viewModel.syncLocalLocksToFirestore()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(TimerForegroundService.BROADCAST_TIMER_COMMAND)
            addAction(StudyBlockerAccessibilityService.BROADCAST_RULE_LOCKED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    var selectedTab by remember(uid) { mutableStateOf(MainTab.TIMER) }
    var showSettings by remember(uid) { mutableStateOf(false) }

    StudyTrackerTheme(
        themeMode = state.settings.theme,
        accentHex = state.settings.accentHex,
    ) {
        // Overlay priority: Fullscreen Timer > Setup Checklist > Settings > Main Shell
        when {
            state.isFullscreenTimer && state.activeSession.active -> {
                FullscreenTimerOverlay(
                    state = state,
                    onPause = { viewModel.pauseTimer() },
                    onResume = { viewModel.resumeTimer() },
                    onStop = { viewModel.stopTimerAndLog() },
                    onExit = { viewModel.setFullscreenTimer(false) },
                )
            }

            state.showSetupChecklist -> {
                SetupChecklistScreen(
                    permissions = state.permissions,
                    localStore = localStore,
                    onRefreshPermissions = { viewModel.refreshPermissions() },
                    onContinue = { viewModel.closeSetupChecklist(markComplete = true) },
                    onSkipForNow = { viewModel.closeSetupChecklist(markComplete = true) },
                    canNavigateBack = localStore.isSetupCompleted(),
                )
            }

            showSettings -> {
                SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { showSettings = false },
                )
            }

            else -> {
                BackHandler(enabled = selectedTab != MainTab.TIMER) {
                    selectedTab = MainTab.TIMER
                }
                MainAdaptiveShell(
                    state = state,
                    viewModel = viewModel,
                    timerViewModel = timerViewModel,
                    localStore = localStore,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }
}

@Composable
private fun MainAdaptiveShell(
    state: StudyAppState,
    viewModel: StudyViewModel,
    timerViewModel: StudyTimerViewModel,
    localStore: LocalDeviceStore,
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalStudyColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        val isExpanded = maxWidth >= 840.dp

        if (isExpanded) {
            // Tablet / Desktop-like canonical NavigationRail layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                NavigationRail(
                    containerColor = colors.surface,
                    contentColor = colors.text,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(92.dp),
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MainTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        NavigationRailItem(
                            selected = isSelected,
                            onClick = { onSelectTab(tab) },
                            icon = {
                                TabIconWithBadge(
                                    icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                    showRedDot = tab == MainTab.BLOCKER && state.hasMissingBlockerPermission,
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                unselectedIconColor = colors.textDim,
                                unselectedTextColor = colors.textDim,
                                indicatorColor = colors.accent.copy(alpha = 0.14f),
                            ),
                            modifier = Modifier.testTag("nav_tab_${tab.route}"),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Persistent live mini-timer chip in rail when studying
                    if (state.activeSession.active) {
                        RailMiniTimerChip(
                            state = state,
                            onClick = { onSelectTab(MainTab.TIMER) },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("settings_button"),
                    ) {
                        TabIconWithBadge(
                            icon = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            showRedDot = state.hasRedPermission,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.border),
                )

                Scaffold(
                    containerColor = colors.bg,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.weight(1f),
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 960.dp)
                                .fillMaxWidth(),
                        ) {
                            StatusBanners(state = state, viewModel = viewModel)
                            TabContentBody(
                                state = state,
                                viewModel = viewModel,
                                timerViewModel = timerViewModel,
                                localStore = localStore,
                                selectedTab = selectedTab,
                            )
                        }
                    }
                }
            }
        } else {
            // Compact mobile layout with bottom NavigationBar
            Scaffold(
                containerColor = colors.bg,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.bg)
                            .windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedTab.label,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = colors.text,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (state.activeSession.active && selectedTab != MainTab.TIMER) {
                                CompactLiveTimerPill(
                                    state = state,
                                    onClick = { onSelectTab(MainTab.TIMER) },
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("settings_button"),
                            ) {
                                TabIconWithBadge(
                                    icon = Icons.Outlined.Settings,
                                    contentDescription = "Settings",
                                    showRedDot = state.hasRedPermission,
                                )
                            }
                        }
                        StatusBanners(state = state, viewModel = viewModel)
                    }
                },
                bottomBar = {
                    Column {
                        HorizontalDivider(color = colors.border, thickness = 1.dp)
                        NavigationBar(
                            containerColor = colors.surface,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets.navigationBars,
                        ) {
                            MainTab.entries.forEach { tab ->
                                val isSelected = tab == selectedTab
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = { onSelectTab(tab) },
                                    icon = {
                                        TabIconWithBadge(
                                            icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label,
                                            showRedDot = tab == MainTab.BLOCKER && state.hasMissingBlockerPermission,
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = colors.accent,
                                        selectedTextColor = colors.accent,
                                        unselectedIconColor = colors.textDim,
                                        unselectedTextColor = colors.textDim,
                                        indicatorColor = colors.accent.copy(alpha = 0.14f),
                                    ),
                                    modifier = Modifier.testTag("nav_tab_${tab.route}"),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    TabContentBody(
                        state = state,
                        viewModel = viewModel,
                        timerViewModel = timerViewModel,
                        localStore = localStore,
                        selectedTab = selectedTab,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanners(
    state: StudyAppState,
    viewModel: StudyViewModel,
) {
    val colors = LocalStudyColors.current
    if (state.isOffline && !state.offlineBannerDismissed) {
        OfflineBanner(
            onDismiss = { viewModel.dismissOfflineBanner() },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    if (state.errorMessage != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.dangerSurface)
                .border(1.dp, colors.danger.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.labelMedium,
                color = colors.danger,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { viewModel.dismissError() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss error",
                    tint = colors.danger,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TabContentBody(
    state: StudyAppState,
    viewModel: StudyViewModel,
    timerViewModel: StudyTimerViewModel,
    localStore: LocalDeviceStore,
    selectedTab: MainTab,
) {
    if (state.isLoading && state.subjects.isEmpty() && state.sessions.isEmpty()) {
        Box(modifier = Modifier.padding(20.dp)) {
            SkeletonLoadingBlock()
        }
        return
    }
    when (selectedTab) {
        MainTab.TIMER -> StudyTimerScreen(timerViewModel = timerViewModel, state = state, viewModel = viewModel)
        MainTab.PLAN -> PlanScreen(state = state, viewModel = viewModel)
        MainTab.STATS -> StatsScreen(state = state)
        MainTab.FILES -> FilesScreen(state = state, viewModel = viewModel)
        MainTab.BLOCKER -> BlockerScreen(state = state, viewModel = viewModel, localStore = localStore)
    }
}

@Composable
private fun TabIconWithBadge(
    icon: ImageVector,
    contentDescription: String,
    showRedDot: Boolean,
) {
    val colors = LocalStudyColors.current
    Box {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
        )
        if (showRedDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .background(colors.danger, CircleShape),
            )
        }
    }
}

@Composable
private fun CompactLiveTimerPill(
    state: StudyAppState,
    onClick: () -> Unit,
) {
    val colors = LocalStudyColors.current
    val active = state.activeSession
    val subject = state.subjects.firstOrNull { it.id == active.subjectId }
    val subColor = parseHexColor(subject?.colorHex ?: state.settings.accentHex)
    val displayMs = if (active.mode == "pomodoro" && active.targetMs > 0L) {
        active.remainingAt(state.nowServerMs)
    } else {
        active.elapsedAt(state.nowServerMs)
    }
    val (prefix, secs, _) = StudyAnalytics.formatTimerParts(displayMs)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(subColor.copy(alpha = 0.16f))
            .border(1.dp, subColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("compact_live_timer_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (active.status == "running") subColor else colors.warning, CircleShape),
        )
        Text(
            text = "$prefix$secs",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RailMiniTimerChip(
    state: StudyAppState,
    onClick: () -> Unit,
) {
    val colors = LocalStudyColors.current
    val active = state.activeSession
    val subject = state.subjects.firstOrNull { it.id == active.subjectId }
    val subColor = parseHexColor(subject?.colorHex ?: state.settings.accentHex)
    val displayMs = if (active.mode == "pomodoro" && active.targetMs > 0L) {
        active.remainingAt(state.nowServerMs)
    } else {
        active.elapsedAt(state.nowServerMs)
    }
    val (prefix, secs, _) = StudyAnalytics.formatTimerParts(displayMs)

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(subColor.copy(alpha = 0.16f))
            .border(1.dp, subColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (active.status == "running") subColor else colors.warning, CircleShape),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$prefix$secs",
            fontSize = 11.sp,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
