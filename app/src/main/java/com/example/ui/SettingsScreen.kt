package com.example.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.auth.signOutUser
import com.example.ui.theme.AccentSwatchesHex
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.parseHexColor
import com.example.viewmodel.StudyAppState
import com.example.viewmodel.StudyViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    state: StudyAppState,
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val colors = LocalStudyColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val settings = state.settings

    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var dataStatusMessage by remember { mutableStateOf<String?>(null) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val payload = viewModel.buildBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }
                dataStatusMessage = "Full JSON backup exported."
            } catch (e: Exception) {
                dataStatusMessage = "Export failed: ${e.localizedMessage ?: "error"}"
            }
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = viewModel.buildSessionsCsv()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                dataStatusMessage = "Study sessions CSV exported."
            } catch (e: Exception) {
                dataStatusMessage = "CSV export failed: ${e.localizedMessage ?: "error"}"
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (!content.isNullOrBlank()) {
                    pendingImportJson = content
                } else {
                    dataStatusMessage = "Selected file was empty."
                }
            } catch (e: Exception) {
                dataStatusMessage = "Could not read file: ${e.localizedMessage ?: "error"}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding(),
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("settings_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.text,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. APPEARANCE
            item(key = "section_appearance") {
                SettingsSectionLabel("APPEARANCE")
                Spacer(modifier = Modifier.height(8.dp))
                StudyCard {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "AMOLED uses true #000000 black to save battery on OLED displays.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textDim,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val themeKeys = listOf("dark", "amoled", "light", "system")
                    val themeLabels = listOf("Dark", "AMOLED", "Light", "System")
                    val selectedIdx = themeKeys.indexOf(settings.theme).coerceAtLeast(0)
                    StudySegmentedControl(
                        options = themeLabels,
                        selectedIndex = selectedIdx,
                        onSelect = { idx ->
                            viewModel.updateSettings { it.copy(theme = themeKeys[idx]) }
                        },
                        testTagPrefix = "theme_option",
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "Accent color",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccentSwatchesHex.forEach { hex ->
                            val swatch = parseHexColor(hex)
                            val isSelected = settings.accentHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) colors.text else colors.border,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        viewModel.updateSettings { it.copy(accentHex = hex) }
                                    }
                                    .testTag("accent_swatch_${hex.removePrefix("#")}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = "Selected accent",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. TIMER
            item(key = "section_timer") {
                SettingsSectionLabel("TIMER")
                Spacer(modifier = Modifier.height(8.dp))
                StudyCard {
                    NumberStepperRow(
                        label = "Pomodoro focus",
                        description = "Length of each deep-work focus block",
                        valueText = "${settings.focusMin}m",
                        onDecrement = {
                            viewModel.updateSettings { it.copy(focusMin = (it.focusMin - 5L).coerceAtLeast(5L)) }
                        },
                        onIncrement = {
                            viewModel.updateSettings { it.copy(focusMin = (it.focusMin + 5L).coerceAtMost(180L)) }
                        },
                    )
                    HorizontalDivider(color = colors.border)
                    NumberStepperRow(
                        label = "Short break",
                        description = "Rest duration between focus blocks",
                        valueText = "${settings.breakMin}m",
                        onDecrement = {
                            viewModel.updateSettings { it.copy(breakMin = (it.breakMin - 1L).coerceAtLeast(1L)) }
                        },
                        onIncrement = {
                            viewModel.updateSettings { it.copy(breakMin = (it.breakMin + 1L).coerceAtMost(60L)) }
                        },
                    )
                    HorizontalDivider(color = colors.border)
                    NumberStepperRow(
                        label = "Long break",
                        description = "Extended recharge break after a full cycle",
                        valueText = "${settings.longBreakMin}m",
                        onDecrement = {
                            viewModel.updateSettings { it.copy(longBreakMin = (it.longBreakMin - 5L).coerceAtLeast(5L)) }
                        },
                        onIncrement = {
                            viewModel.updateSettings { it.copy(longBreakMin = (it.longBreakMin + 5L).coerceAtMost(60L)) }
                        },
                    )
                    HorizontalDivider(color = colors.border)
                    NumberStepperRow(
                        label = "Long break every",
                        description = "Focus blocks completed before a long break",
                        valueText = "${settings.longEvery}",
                        onDecrement = {
                            viewModel.updateSettings { it.copy(longEvery = (it.longEvery - 1L).coerceAtLeast(2L)) }
                        },
                        onIncrement = {
                            viewModel.updateSettings { it.copy(longEvery = (it.longEvery + 1L).coerceAtMost(8L)) }
                        },
                    )
                    HorizontalDivider(color = colors.border)
                    SettingsSwitchRow(
                        label = "Sound alerts",
                        description = "Play chime when a Pomodoro phase or goal finishes",
                        checked = settings.alertSound,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings { it.copy(alertSound = checked) }
                        },
                        testTag = "switch_alert_sound",
                    )
                    HorizontalDivider(color = colors.border)
                    SettingsSwitchRow(
                        label = "Vibration alerts",
                        description = "Haptic pulse when focus blocks and breaks switch",
                        checked = settings.alertVibration,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings { it.copy(alertVibration = checked) }
                        },
                        testTag = "switch_alert_vibration",
                    )
                    HorizontalDivider(color = colors.border)
                    SettingsSwitchRow(
                        label = "Block apps during breaks",
                        description = "Keep study-session app blocking enforced during Pomodoro breaks",
                        checked = settings.blockDuringBreaks,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings { it.copy(blockDuringBreaks = checked) }
                        },
                        testTag = "switch_block_breaks",
                    )
                }
            }

            // 3. GOALS
            item(key = "section_goals") {
                SettingsSectionLabel("GOALS & REMINDERS")
                Spacer(modifier = Modifier.height(8.dp))
                StudyCard {
                    NumberStepperRow(
                        label = "Daily goal",
                        description = "Target study time per day",
                        valueText = formatMinutesCompact(settings.dailyGoalMin),
                        onDecrement = {
                            viewModel.updateSettings { it.copy(dailyGoalMin = (it.dailyGoalMin - 15L).coerceAtLeast(15L)) }
                        },
                        onIncrement = {
                            viewModel.updateSettings { it.copy(dailyGoalMin = (it.dailyGoalMin + 15L).coerceAtMost(720L)) }
                        },
                    )
                    HorizontalDivider(color = colors.border)
                    NumberStepperRow(
                        label = "Weekly goal",
                        description = "Target study time per week",
                        valueText = formatMinutesCompact(settings.weeklyGoalMin),
                        onDecrement = {
                            viewModel.updateSettings { it.copy(weeklyGoalMin = (it.weeklyGoalMin - 60L).coerceAtLeast(60L)) }
                        },
                        onIncrement = {
                            viewModel.updateSettings { it.copy(weeklyGoalMin = (it.weeklyGoalMin + 60L).coerceAtMost(4200L)) }
                        },
                    )
                    HorizontalDivider(color = colors.border)
                    NumberStepperRow(
                        label = "Streak minimum",
                        description = "Minimum minutes studied per day to keep your streak",
                        valueText = "${settings.streakMinMinutes}m",
                        onDecrement = {
                            viewModel.updateSettings { it.copy(streakMinMinutes = (it.streakMinMinutes - 5L).coerceAtLeast(5L)) }
                        },
                        onIncrement = {
                            viewModel.updateSettings { it.copy(streakMinMinutes = (it.streakMinMinutes + 5L).coerceAtMost(180L)) }
                        },
                    )
                    HorizontalDivider(color = colors.border)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Week starts on",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.text,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Applies to weekly goals, stats charts, and weekly app limits",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textDim,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val dayOptions = listOf(
                        "sat" to "Sat",
                        "sun" to "Sun",
                        "mon" to "Mon",
                        "tue" to "Tue",
                        "wed" to "Wed",
                        "thu" to "Thu",
                        "fri" to "Fri",
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dayOptions, key = { it.first }) { (dayKey, dayLabel) ->
                            val selected = settings.weekStartsOn.equals(dayKey, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) colors.accent else colors.surface2)
                                    .border(
                                        1.dp,
                                        if (selected) colors.accent else colors.border,
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable {
                                        viewModel.updateSettings { it.copy(weekStartsOn = dayKey) }
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = dayLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) Color.White else colors.text,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = colors.border)
                    val reminderEnabled = settings.dailyReminderHour >= 0L
                    SettingsSwitchRow(
                        label = "Daily study reminder",
                        description = if (reminderEnabled) {
                            "Fires at %02d:%02d if you have not studied yet".format(
                                settings.dailyReminderHour,
                                settings.dailyReminderMinute,
                            )
                        } else {
                            "Disabled"
                        },
                        checked = reminderEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateSettings {
                                it.copy(dailyReminderHour = if (enabled) 20L else -1L, dailyReminderMinute = 0L)
                            }
                        },
                        testTag = "switch_daily_reminder",
                    )
                    if (reminderEnabled) {
                        NumberStepperRow(
                            label = "Reminder hour",
                            description = "24-hour clock time for daily nudge",
                            valueText = "%02d:00".format(settings.dailyReminderHour),
                            onDecrement = {
                                viewModel.updateSettings {
                                    it.copy(dailyReminderHour = (it.dailyReminderHour - 1L + 24L) % 24L)
                                }
                            },
                            onIncrement = {
                                viewModel.updateSettings {
                                    it.copy(dailyReminderHour = (it.dailyReminderHour + 1L) % 24L)
                                }
                            },
                        )
                    }
                }
            }

            // 4. BLOCKER
            item(key = "section_blocker") {
                SettingsSectionLabel("APP BLOCKER")
                Spacer(modifier = Modifier.height(8.dp))
                StudyCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface2)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .clickable { viewModel.openSetupChecklist() }
                            .padding(14.dp)
                            .testTag("settings_open_setup_checklist"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Setup checklist",
                            tint = if (state.hasMissingBlockerPermission) colors.danger else colors.success,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Setup Checklist",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.text,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (state.hasMissingBlockerPermission) {
                                    "Action needed — required Android permissions missing"
                                } else {
                                    "All required blocking permissions granted"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.hasMissingBlockerPermission) colors.danger else colors.success,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsSwitchRow(
                        label = "Strict lock",
                        description = "Prevent deleting or loosening usage limit rules mid-period",
                        checked = settings.strictLock,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings { it.copy(strictLock = checked) }
                        },
                        testTag = "switch_settings_strict_lock",
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface2)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Blocker limits",
                            tint = colors.textDim,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Honest limits: Blocking runs locally via Android Accessibility & Device Admin and protects against casual bypasses (Settings, Accessibility, Uninstall). On stock Android without MDM, booting into Android Safe Mode or performing a factory reset can still override any user-installed blocker.",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textDim,
                        )
                    }
                }
            }

            // 5. DATA
            item(key = "section_data") {
                SettingsSectionLabel("DATA & BACKUP")
                Spacer(modifier = Modifier.height(8.dp))
                StudyCard {
                    Text(
                        text = "Export or restore your subjects, chapters, sessions, goals, exams, and rules.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textDim,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportJsonLauncher.launch("study_tracker_backup_$stamp.json")
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("export_json_button"),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export JSON", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportCsvLauncher.launch("study_sessions_$stamp.csv")
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("export_csv_button"),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sessions CSV", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { importJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.surface2,
                            contentColor = colors.text,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .testTag("import_json_button"),
                    ) {
                        Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import JSON backup", fontWeight = FontWeight.SemiBold)
                    }
                    if (dataStatusMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = dataStatusMessage!!,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accent,
                        )
                    }
                }
            }

            // 6. DEVICES
            item(key = "section_devices") {
                SettingsSectionLabel("CONNECTED DEVICES")
                Spacer(modifier = Modifier.height(8.dp))
                StudyCard {
                    if (state.devices.isEmpty()) {
                        Text(
                            text = "This device: ${state.deviceName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textDim,
                        )
                    } else {
                        val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
                        state.devices.forEachIndexed { idx, device ->
                            if (idx > 0) HorizontalDivider(color = colors.border)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (device.platform == "android") Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer,
                                    contentDescription = device.platform,
                                    tint = colors.accent,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = device.name.ifBlank { "Android Device" },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = colors.text,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (device.id == state.deviceId) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "This device",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.success,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(colors.success.copy(alpha = 0.14f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    val lastSeenText = device.lastSeen?.toDate()?.let { fmt.format(it) } ?: "Active now"
                                    Text(
                                        text = "${device.platform.replaceFirstChar { it.uppercase() }} · Last seen $lastSeenText",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.textDim,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. ACCOUNT
            item(key = "section_account") {
                SettingsSectionLabel("ACCOUNT")
                Spacer(modifier = Modifier.height(8.dp))
                StudyCard {
                    val user = Firebase.auth.currentUser
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(colors.accent.copy(alpha = 0.16f), CircleShape)
                                .border(1.dp, colors.accent.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = "Account",
                                tint = colors.accent,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.displayName?.takeIf { it.isNotBlank() } ?: "Google Account",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.text,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = user?.email ?: "Synced via Firebase",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { showSignOutConfirm = true },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.danger),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("sign_out_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = "Sign out",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign out", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // Import mode dialog (Merge vs Replace)
    pendingImportJson?.let { rawJson ->
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            title = { Text("Import JSON Backup") },
            text = {
                Text(
                    "Choose whether to merge items into your existing data or replace existing unlocked records. Local file URIs stay device-specific."
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.importBackupJson(rawJson, replaceExisting = true)
                            pendingImportJson = null
                        },
                        modifier = Modifier.testTag("import_replace_button"),
                    ) {
                        Text("Replace", color = colors.danger)
                    }
                    Button(
                        onClick = {
                            viewModel.importBackupJson(rawJson, replaceExisting = false)
                            pendingImportJson = null
                        },
                        modifier = Modifier.testTag("import_merge_button"),
                    ) {
                        Text("Merge")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportJson = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out?") },
            text = {
                Text("Your study data stays safely synced in Firebase. Sign back in anytime with the same Google account.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutConfirm = false
                        signOutUser(credentialManager, scope)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_sign_out_button"),
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionLabel(title: String) {
    val colors = LocalStudyColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = colors.textDim,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val colors = LocalStudyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textDim,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

private fun formatMinutesCompact(minutes: Long): String {
    val h = minutes / 60L
    val m = minutes % 60L
    return when {
        h > 0L && m > 0L -> "${h}h ${m}m"
        h > 0L -> "${h}h"
        else -> "${m}m"
    }
}
