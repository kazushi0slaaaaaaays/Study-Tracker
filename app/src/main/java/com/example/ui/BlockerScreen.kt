package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BlockRule
import com.example.data.LocalDeviceStore
import com.example.data.computePeriodBounds
import com.example.nativeandroid.InstalledAppInfo
import com.example.nativeandroid.InstalledAppsHelper
import com.example.ui.theme.LocalStudyColors
import com.example.viewmodel.StudyAnalytics
import com.example.viewmodel.StudyAppState
import com.example.viewmodel.StudyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BlockerScreen(
    state: StudyAppState,
    viewModel: StudyViewModel,
    localStore: LocalDeviceStore,
) {
    val colors = LocalStudyColors.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 During study, 1 Usage limits
    var showSessionPicker by remember { mutableStateOf(false) }
    var showAddLimitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
        viewModel.loadInstalledAppsIfNeeded()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Red banner at top if any required permission is missing (PDF Section 7.8)
            if (state.hasRedPermission || state.hasMissingBlockerPermission) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.danger.copy(alpha = 0.16f))
                        .border(1.dp, colors.danger.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .clickable { viewModel.openSetupChecklist() }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("blocker_permission_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = "Missing blocker permission",
                        tint = colors.danger,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Blocker permissions incomplete",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.danger,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Tap to open Setup Checklist and enable Accessibility & Overlay.",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.text,
                        )
                    }
                }
            }

            // Two tabs at top: During study | Usage limits
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                StudySegmentedControl(
                    options = listOf("During study", "Usage limits"),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                    testTagPrefix = "blocker_tab",
                )
            }

            if (selectedTab == 0) {
                DuringStudyTab(
                    state = state,
                    onOpenPicker = { showSessionPicker = true },
                    onRemovePackage = { viewModel.toggleSessionBlockedPackage(it) },
                )
            } else {
                UsageLimitsTab(
                    state = state,
                    localStore = localStore,
                    onOpenAddLimit = { showAddLimitDialog = true },
                    onDeleteRule = { viewModel.deleteBlockRule(it) },
                    onToggleStrictLock = { enabled ->
                        viewModel.updateSettings { it.copy(strictLock = enabled) }
                    },
                )
            }
        }

        if (selectedTab == 1) {
            FloatingActionButton(
                onClick = { showAddLimitDialog = true },
                containerColor = colors.danger,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .testTag("blocker_add_limit_fab"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add usage limit")
            }
        }
    }

    if (showSessionPicker) {
        AppMultiPickerDialog(
            installedApps = state.installedApps,
            selectedPackages = state.sessionBlocklist.android.toSet(),
            onToggle = { viewModel.toggleSessionBlockedPackage(it) },
            onDismiss = { showSessionPicker = false },
        )
    }

    if (showAddLimitDialog) {
        AddLimitDialog(
            installedApps = state.installedApps,
            onDismiss = { showAddLimitDialog = false },
            onSave = { pkg, label, allowanceMin, periodType, periodCount ->
                viewModel.addBlockRule(pkg, label, allowanceMin, periodType, periodCount)
                showAddLimitDialog = false
            },
        )
    }
}

@Composable
private fun DuringStudyTab(
    state: StudyAppState,
    onOpenPicker: () -> Unit,
    onRemovePackage: (String) -> Unit,
) {
    val colors = LocalStudyColors.current
    val context = LocalContext.current
    val blockedPkgs = state.sessionBlocklist.android

    val resolvedApps = remember(blockedPkgs, state.installedApps) {
        val byPkg = state.installedApps.associateBy { it.packageName }
        blockedPkgs.map { pkg ->
            byPkg[pkg] ?: InstalledAppsHelper.resolveAppInfo(context, pkg)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Status header card with danger-tinted surface
        item {
            StudyCard(
                containerColor = colors.dangerSurface,
                borderColor = if (state.isBlockingActiveNow) colors.success.copy(alpha = 0.55f) else colors.danger.copy(alpha = 0.35f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (state.isBlockingActiveNow) colors.success.copy(alpha = 0.16f)
                                else colors.danger.copy(alpha = 0.16f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = if (state.isBlockingActiveNow) colors.success else colors.danger,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.isBlockingActiveNow) "Session blocking active" else "Session blocking idle",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (state.isBlockingActiveNow) {
                                "Selected apps are blocked until you stop your focus timer."
                            } else {
                                "Selected apps will lock automatically whenever a study session starts."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textDim,
                        )
                    }
                }
            }
        }

        // Grid of selected app tiles + Plus tile (3 columns)
        item {
            val allTiles: List<InstalledAppInfo?> = resolvedApps + listOf(null)
            val rows = allTiles.chunked(3)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { app ->
                            if (app == null) {
                                // Plus tile to open app picker
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(104.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(colors.dangerSurface)
                                        .border(1.dp, colors.danger.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                        .clickable { onOpenPicker() }
                                        .testTag("blocker_add_session_apps_tile"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Outlined.Add, contentDescription = "Pick apps", tint = colors.danger, modifier = Modifier.size(28.dp))
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("Pick apps", style = MaterialTheme.typography.labelMedium, color = colors.text)
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(104.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(colors.surface)
                                        .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                                        .clickable {
                                            if (!state.isBlockingActiveNow) onRemovePackage(app.packageName)
                                        }
                                        .padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    if (app.icon != null) {
                                        Image(
                                            bitmap = app.icon,
                                            contentDescription = app.label,
                                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)),
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.Android,
                                            contentDescription = app.label,
                                            tint = colors.textDim,
                                            modifier = Modifier.size(34.dp),
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (app.isInstalled) app.label else "${app.label} (missing)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (app.isInstalled) colors.text else colors.danger,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageLimitsTab(
    state: StudyAppState,
    localStore: LocalDeviceStore,
    onOpenAddLimit: () -> Unit,
    onDeleteRule: (BlockRule) -> Unit,
    onToggleStrictLock: (Boolean) -> Unit,
) {
    val colors = LocalStudyColors.current
    val context = LocalContext.current
    val androidRules = state.blockRules.filter { it.platform == "android" }
    val localLocks = localStore.getLocalLockedUntilMap()
    val resetFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (androidRules.isEmpty()) {
            item {
                FriendlyEmptyState(
                    icon = Icons.Outlined.Lock,
                    title = "No usage limits set",
                    subtitle = "Cap distracting apps like Instagram or YouTube by hour, N hours, day, week, or month. Once a limit is hit, it locks until reset.",
                    actionLabel = "Add usage limit",
                    onAction = onOpenAddLimit,
                    testTag = "empty_limits_add_btn",
                )
            }
        } else {
            items(androidRules, key = { it.id }) { rule ->
                val bounds = computePeriodBounds(
                    state.nowGuardedMs,
                    rule.periodType,
                    rule.periodCount,
                    state.settings.weekStartsOn,
                )
                val usedMs = state.ruleUsageMs[rule.id] ?: 0L
                val allowanceMs = (rule.allowanceMin * 60_000L).coerceAtLeast(60_000L)
                val pct = (usedMs.toFloat() / allowanceMs.toFloat()).coerceIn(0f, 1f)
                val remainingMs = (allowanceMs - usedMs).coerceAtLeast(0L)
                val effectiveLockMs = maxOf(rule.lockedUntilMs(), localLocks[rule.id] ?: 0L)
                val isLocked = effectiveLockMs > state.nowGuardedMs || usedMs >= allowanceMs
                val resetInMs = (bounds.endMs - state.nowGuardedMs).coerceAtLeast(0L)
                val appInfo = remember(rule.targetId, state.installedApps) {
                    state.installedApps.firstOrNull { it.packageName == rule.targetId }
                        ?: InstalledAppsHelper.resolveAppInfo(context, rule.targetId, rule.targetLabel)
                }

                StudyCard(
                    containerColor = if (isLocked) colors.dangerSurface else colors.surface,
                    borderColor = if (isLocked) colors.danger else colors.border,
                    contentPadding = 16.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (appInfo.icon != null) {
                            Image(
                                bitmap = appInfo.icon,
                                contentDescription = appInfo.label,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(colors.surface2, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Android, contentDescription = null, tint = colors.textDim)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = rule.targetLabel.ifBlank { appInfo.label },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.text,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isLocked) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = "Rule locked",
                                        tint = colors.danger,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Text(
                                text = "${rule.allowanceMin} min ${rule.periodLabel()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textDim,
                            )
                        }
                        IconButton(
                            onClick = { onDeleteRule(rule) },
                            enabled = !isLocked,
                            modifier = Modifier.size(48.dp).testTag("delete_rule_${rule.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = if (isLocked) "Delete disabled while locked" else "Delete rule",
                                tint = if (isLocked) colors.textDim.copy(alpha = 0.3f) else colors.danger,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (isLocked) colors.danger else colors.accent,
                        trackColor = colors.surface2,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (isLocked) {
                                "Locked until ${resetFmt.format(Date(maxOf(effectiveLockMs, bounds.endMs)))}"
                            } else {
                                "${StudyAnalytics.formatDurationHuman(remainingMs)} left"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isLocked) colors.danger else colors.text,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Resets in ${StudyAnalytics.formatCountdownMs(resetInMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textDim,
                        )
                    }

                    if (isLocked) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Limit reached — rule cannot be deleted or loosened until the period resets.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.danger.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }

        // Strict lock toggle at bottom of Usage limits tab (PDF Section 7.8)
        item {
            StudyCard(containerColor = colors.dangerSurface, borderColor = colors.danger.copy(alpha = 0.3f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Strict lock",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "When on, rules can only be deleted or loosened right after a period resets, never mid-period.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textDim,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = state.settings.strictLock,
                        onCheckedChange = onToggleStrictLock,
                        modifier = Modifier.testTag("blocker_strict_lock_switch"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppMultiPickerDialog(
    installedApps: List<InstalledAppInfo>,
    selectedPackages: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalStudyColors.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) installedApps else installedApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Block During Study", color = colors.text) },
        text = {
            Column(modifier = Modifier.height(420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search installed apps...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onToggle(app.packageName) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Icon(Icons.Outlined.Android, contentDescription = null, tint = colors.textDim)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Checkbox(checked = checked, onCheckedChange = { onToggle(app.packageName) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp)) { Text("Done") }
        },
    )
}

@Composable
private fun AddLimitDialog(
    installedApps: List<InstalledAppInfo>,
    onDismiss: () -> Unit,
    onSave: (pkg: String, label: String, allowanceMin: Long, periodType: String, periodCount: Long) -> Unit,
) {
    val colors = LocalStudyColors.current
    var query by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var allowanceMin by remember { mutableLongStateOf(30L) }
    var periodIdx by remember { mutableIntStateOf(2) } // 0: 1h, 1: Nh, 2: Day, 3: Week, 4: Month
    var periodHoursCount by remember { mutableLongStateOf(4L) }

    val filtered = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) installedApps.take(40) else installedApps.filter {
            it.label.lowercase().contains(q)
        }.take(40)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Add Usage Limit", color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectedApp == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Pick an app to limit...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(modifier = Modifier.height(210.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable { selectedApp = app }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = app.label,
                                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(app.label, color = colors.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface2)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(selectedApp!!.label, color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedApp = null }) { Text("Change") }
                    }

                    NumberStepperRow(
                        label = "Allowance",
                        description = "Minutes allowed per period",
                        valueText = "${allowanceMin}m",
                        onDecrement = { allowanceMin = (allowanceMin - 5L).coerceAtLeast(1L) },
                        onIncrement = { allowanceMin = (allowanceMin + 5L).coerceAtMost(1440L) },
                    )

                    StudySegmentedControl(
                        options = listOf("1h", "Nh", "Day", "Wk", "Mo"),
                        selectedIndex = periodIdx,
                        onSelect = { periodIdx = it },
                    )

                    if (periodIdx == 1) {
                        NumberStepperRow(
                            label = "Every N hours",
                            description = "Repeating N-hour blocks from midnight",
                            valueText = "${periodHoursCount}h",
                            onDecrement = { periodHoursCount = (periodHoursCount - 1L).coerceAtLeast(2L) },
                            onIncrement = { periodHoursCount = (periodHoursCount + 1L).coerceAtMost(12L) },
                        )
                    }

                    Text(
                        text = "Once reached, this limit locks until the period ends and cannot be deleted or loosened.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.danger,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val app = selectedApp ?: return@Button
                    val (pType, pCount) = when (periodIdx) {
                        0 -> "hours" to 1L
                        1 -> "hours" to periodHoursCount
                        2 -> "day" to 1L
                        3 -> "week" to 1L
                        else -> "month" to 1L
                    }
                    onSave(app.packageName, app.label, allowanceMin, pType, pCount)
                },
                enabled = selectedApp != null,
                colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Add Limit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
