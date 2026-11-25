package com.example.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.StudySession
import com.example.data.Subject
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.SubjectPaletteHex
import com.example.ui.theme.parseHexColor
import com.example.ui.theme.subjectTint
import com.example.viewmodel.StudyAnalytics
import com.example.viewmodel.StudyAppState
import com.example.viewmodel.StudyTimerUiState
import com.example.viewmodel.StudyTimerViewModel
import com.example.viewmodel.StudyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Primary Study Timer UI screen featuring a large, always-visible tabular digital clock display
 * and Start / Pause / Resume / Stop controls driven by [StudyTimerViewModel].
 */
@Composable
fun StudyTimerScreen(
    timerViewModel: StudyTimerViewModel,
    state: StudyAppState,
    viewModel: StudyViewModel,
) {
    val timerState by timerViewModel.timerState.collectAsStateWithLifecycle()
    val colors = LocalStudyColors.current
    var showManualDialog by remember { mutableStateOf(false) }
    var showAddSubjectDialog by remember { mutableStateOf(false) }
    var editingSession by remember { mutableStateOf<StudySession?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("study_timer_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Primary Digital Clock & Start/Stop Controls Hero Card (always visible)
        item(key = "digital_clock_hero") {
            DigitalClockHeroCard(
                timerState = timerState,
                onStart = { timerViewModel.start() },
                onPause = { timerViewModel.pause() },
                onResume = { timerViewModel.resume() },
                onStop = { timerViewModel.stop() },
                onOpenFullscreen = { timerViewModel.openFullscreen() },
            )
        }

        // Daily Streak Card (increments on completing a 25+ minute session, synced to Firestore)
        item(key = "daily_streak_card") {
            DailyStreakCard(
                currentStreakDays = timerState.currentStreakDays,
                longestStreakDays = timerState.longestStreakDays,
                completedToday = timerState.streakCompletedToday,
            )
        }

        // Daily progress summary strip
        item(key = "daily_goal_summary") {
            StudyCard(contentPadding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${StudyAnalytics.formatDurationHuman(timerState.todayTotalMs)} studied today",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${(timerState.dailyProgress * 100).toInt()}% of ${StudyAnalytics.formatDurationHuman(timerState.dailyGoalMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (timerState.dailyProgress >= 1f) colors.success else colors.textDim,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { timerState.dailyProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (timerState.dailyProgress >= 1f) colors.success else colors.accent,
                    trackColor = colors.surface2,
                )
            }
        }

        // Session configuration (Subject selector, Stopwatch/Pomodoro toggle, Note) when idle
        if (!timerState.isActive) {
            item(key = "timer_setup_controls") {
                IdleSessionConfigSection(
                    state = state,
                    onSelectSubject = { timerViewModel.selectSubject(it) },
                    onSelectMode = { timerViewModel.selectMode(it) },
                    onNoteChange = { timerViewModel.updateDraftNote(it) },
                    onOpenManual = { showManualDialog = true },
                    onOpenAddSubject = { showAddSubjectDialog = true },
                )
            }
        }

        // Recent sessions header
        item(key = "recent_sessions_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${state.sessions.size} total",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textDim,
                )
            }
        }

        if (state.sessions.isEmpty()) {
            item(key = "empty_sessions") {
                FriendlyEmptyState(
                    icon = Icons.Outlined.History,
                    title = "No sessions logged yet",
                    subtitle = "Tap Start above to begin tracking your study time or log a completed session manually.",
                    actionLabel = "Add manual session",
                    onAction = { showManualDialog = true },
                    testTag = "empty_sessions_manual_btn",
                )
            }
        } else {
            items(state.sessions.take(15), key = { it.id }) { session ->
                val sub = state.subjects.firstOrNull { it.id == session.subjectId }
                SessionHistoryRow(
                    session = session,
                    subject = sub,
                    onEdit = { editingSession = session },
                    onDelete = { viewModel.deleteSession(session.id) },
                )
            }
        }
    }

    if (showManualDialog) {
        ManualSessionDialog(
            subjects = state.activeSubjects,
            defaultSubjectId = state.effectiveSelectedSubjectId,
            onDismiss = { showManualDialog = false },
            onSave = { subjectId, startMs, durationMin, note ->
                viewModel.addManualSession(subjectId, startMs, durationMin, note)
                showManualDialog = false
            },
        )
    }

    if (showAddSubjectDialog) {
        AddSubjectDialog(
            onDismiss = { showAddSubjectDialog = false },
            onSave = { name, colorHex, emoji, courseCode ->
                viewModel.addSubject(name, colorHex, emoji, courseCode)
                showAddSubjectDialog = false
            },
        )
    }

    editingSession?.let { sess ->
        EditSessionDialog(
            session = sess,
            subjects = state.activeSubjects,
            onDismiss = { editingSession = null },
            onSave = { updated ->
                viewModel.updateSession(updated)
                editingSession = null
            },
            onDelete = {
                viewModel.deleteSession(sess.id)
                editingSession = null
            },
        )
    }
}

/**
 * Convenience alias preserving backward compatibility with existing callers.
 */
@Composable
fun TimerScreen(
    state: StudyAppState,
    viewModel: StudyViewModel,
    timerViewModel: StudyTimerViewModel,
) {
    StudyTimerScreen(
        timerViewModel = timerViewModel,
        state = state,
        viewModel = viewModel,
    )
}

@Composable
private fun DailyStreakCard(
    currentStreakDays: Long,
    longestStreakDays: Long,
    completedToday: Boolean,
) {
    val colors = LocalStudyColors.current
    val flameColor = if (completedToday || currentStreakDays > 0L) colors.warning else colors.textDim

    StudyCard(
        modifier = Modifier.testTag("daily_streak_card"),
        contentPadding = 14.dp,
        borderColor = if (completedToday) colors.success.copy(alpha = 0.45f) else colors.border,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(flameColor.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                    .border(1.dp, flameColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocalFireDepartment,
                    contentDescription = "Daily study streak",
                    tint = flameColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "$currentStreakDays day streak",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("daily_streak_count"),
                    )
                    if (longestStreakDays > 0L) {
                        Text(
                            text = "Best $longestStreakDays",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface2)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (completedToday) {
                        "Today's 25+ min session complete · Saved to cloud"
                    } else {
                        "Complete a 25+ min study session today to increment"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (completedToday) colors.success else colors.textDim,
                )
            }
        }
    }
}

@Composable
private fun DigitalClockHeroCard(
    timerState: StudyTimerUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    val colors = LocalStudyColors.current
    val subjectColor = parseHexColor(timerState.subjectColorHex, colors.accent)

    val pulseTransition = rememberInfiniteTransition(label = "clock_live_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (timerState.isRunning) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_dot_alpha",
    )

    val borderColor = when {
        timerState.isRunning -> subjectColor.copy(alpha = 0.55f)
        timerState.isPaused -> colors.warning.copy(alpha = 0.5f)
        else -> colors.border
    }

    val statusDotColor = when {
        timerState.isRunning -> subjectColor.copy(alpha = pulseAlpha)
        timerState.isPaused -> colors.warning
        else -> colors.textDim.copy(alpha = 0.5f)
    }

    val statusText = when {
        timerState.isRunning -> "LIVE"
        timerState.isPaused -> "PAUSED"
        else -> "READY"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surface)
            .drawBehind {
                // Subtle atmospheric glow anchored behind the digital clock
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            subjectColor.copy(alpha = if (timerState.isRunning) 0.16f else 0.08f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.38f),
                        radius = size.maxDimension * 0.72f,
                    )
                )
            }
            .border(1.dp, borderColor, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: Status pill + Fullscreen expand button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusDotColor, CircleShape)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (timerState.isRunning) subjectColor else colors.textDim,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        text = "·  ${timerState.phaseLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (timerState.isActive) {
                    IconButton(
                        onClick = onOpenFullscreen,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface2)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .testTag("open_fullscreen_timer_btn"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Fullscreen,
                            contentDescription = "Open full-screen timer",
                            tint = colors.text,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Active subject caption
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (timerState.subjectEmoji.isNotBlank()) {
                    Text(text = timerState.subjectEmoji, fontSize = 16.sp)
                }
                Text(
                    text = timerState.subjectName.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = subjectColor,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Large, readable tabular digital clock display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .then(
                        if (timerState.isActive) Modifier.clickable { onOpenFullscreen() }
                        else Modifier
                    )
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "Study timer ${timerState.formattedTime}" }
                    .testTag("digital_clock_display"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.text)) {
                            append(timerState.prefixText)
                        }
                        withStyle(SpanStyle(color = subjectColor)) {
                            append(timerState.secondsText)
                        }
                    },
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.ExtraLight,
                    fontSize = if (timerState.hasHours) 54.sp else 74.sp,
                    letterSpacing = (-2.5).sp,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Thin horological progress rail
            LinearProgressIndicator(
                progress = { timerState.progress },
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (timerState.isPaused) colors.warning else subjectColor,
                trackColor = colors.surface2,
            )

            // Blocking / remote device indicators
            if (timerState.isBlockingActive) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = colors.success,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Blocking active (${timerState.blockedAppCount} apps)",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.success,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            timerState.remoteDeviceName?.let { remote ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Synced live with $remote",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Start / Pause / Resume & Stop Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    !timerState.isActive -> {
                        Button(
                            onClick = onStart,
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = subjectColor,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(58.dp)
                                .testTag("timer_start_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Start timer",
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Start",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Button(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = colors.surface2,
                                disabledContentColor = colors.textDim.copy(alpha = 0.55f),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp)
                                .border(1.dp, colors.border, RoundedCornerShape(28.dp))
                                .testTag("timer_stop_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "Stop timer",
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Stop",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    else -> {
                        Button(
                            onClick = { if (timerState.isRunning) onPause() else onResume() },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (timerState.isRunning) colors.surface2 else subjectColor,
                                contentColor = if (timerState.isRunning) colors.text else Color.White,
                            ),
                            modifier = Modifier
                                .weight(1.1f)
                                .height(58.dp)
                                .then(
                                    if (timerState.isRunning) Modifier.border(1.dp, colors.border, RoundedCornerShape(28.dp))
                                    else Modifier
                                )
                                .testTag("timer_pause_resume_btn"),
                        ) {
                            Icon(
                                imageVector = if (timerState.isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (timerState.isRunning) "Pause timer" else "Resume timer",
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (timerState.isRunning) "Pause" else "Resume",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Instant Stop under 60s for quick control; 600ms press-and-hold >= 60s to prevent accidental loss
                        if (timerState.elapsedMs < 60_000L) {
                            Button(
                                onClick = onStop,
                                shape = RoundedCornerShape(28.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.danger.copy(alpha = 0.16f),
                                    contentColor = colors.danger,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp)
                                    .border(1.5.dp, colors.danger.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                                    .testTag("timer_stop_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = "Stop timer",
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Stop",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else {
                            HoldToStopButton(
                                onConfirmedStop = onStop,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleSessionConfigSection(
    state: StudyAppState,
    onSelectSubject: (String) -> Unit,
    onSelectMode: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onOpenManual: () -> Unit,
    onOpenAddSubject: () -> Unit,
) {
    val colors = LocalStudyColors.current
    val subjects = state.activeSubjects
    val selectedId = state.effectiveSelectedSubjectId

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Stopwatch / Pomodoro segmented control
        StudySegmentedControl(
            options = listOf("Stopwatch", "Pomodoro"),
            selectedIndex = if (state.selectedMode == "pomodoro") 1 else 0,
            onSelect = { idx -> onSelectMode(if (idx == 1) "pomodoro" else "stopwatch") },
            testTagPrefix = "timer_mode",
        )

        if (state.selectedMode == "pomodoro") {
            Text(
                text = "Focus ${state.settings.focusMin}m · Break ${state.settings.breakMin}m · Long ${state.settings.longBreakMin}m every ${state.settings.longEvery}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textDim,
            )
        }

        // Horizontal subject chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(subjects, key = { it.id }) { subject ->
                val selected = subject.id == selectedId
                val subColor = parseHexColor(subject.colorHex)
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) subColor.subjectTint() else colors.surface)
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) subColor else colors.border,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelectSubject(subject.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("subject_chip_${subject.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(subColor, CircleShape)
                    )
                    if (subject.emoji.isNotBlank()) {
                        Text(text = subject.emoji, fontSize = 15.sp)
                    }
                    Text(
                        text = subject.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .clickable { onOpenAddSubject() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("timer_add_subject_chip"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Add subject",
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Subject",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Optional session note
        OutlinedTextField(
            value = state.draftNote,
            onValueChange = onNoteChange,
            placeholder = { Text("Optional focus note (e.g. Ch. 4 problems)", color = colors.textDim) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("timer_note_input"),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val blockedCount = state.sessionBlocklist.android.size
            if (blockedCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = colors.textDim,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Blocking $blockedCount app${if (blockedCount == 1) "" else "s"} during study",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textDim,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            TextButton(
                onClick = onOpenManual,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("timer_add_manual_button"),
            ) {
                Text(
                    text = "+ Manual session",
                    color = colors.accent,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 600ms press-and-hold Stop button with live filling outline to prevent accidental stops during long sessions.
 */
@Composable
private fun HoldToStopButton(
    onConfirmedStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStudyColors.current
    val scope = rememberCoroutineScope()
    var holdProgress by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(colors.danger.copy(alpha = 0.14f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val holdJob = scope.launch {
                            val start = System.currentTimeMillis()
                            while (true) {
                                val pct = ((System.currentTimeMillis() - start) / 600f).coerceIn(0f, 1f)
                                holdProgress = pct
                                if (pct >= 1f) {
                                    onConfirmedStop()
                                    break
                                }
                                delay(16L)
                            }
                        }
                        tryAwaitRelease()
                        holdJob.cancel()
                        holdProgress = 0f
                    }
                )
            }
            .testTag("timer_stop_button"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = 28.dp.toPx()
            drawRoundRect(
                color = colors.danger.copy(alpha = 0.4f),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            if (holdProgress > 0f) {
                drawRoundRect(
                    color = colors.danger.copy(alpha = 0.35f),
                    size = Size(size.width * holdProgress, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
                drawRoundRect(
                    color = colors.danger,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = "Hold to stop timer",
                tint = colors.danger,
            )
            Text(
                text = if (holdProgress > 0f) "Holding..." else "Hold to Stop",
                color = colors.danger,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SessionHistoryRow(
    session: StudySession,
    subject: Subject?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalStudyColors.current
    val subColor = parseHexColor(subject?.colorHex ?: "#6C7BFF")
    val timeFmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }

    StudyCard(contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(subColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${subject?.emoji.orEmpty()} ${subject?.name ?: "Unknown Subject"}".trim(),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(timeFmt.format(Date(session.startAtMs)))
                        append(" · ")
                        append(if (session.manual) "Manual" else session.mode.replaceFirstChar { it.uppercase() })
                        if (session.note.isNotBlank()) {
                            append(" · ")
                            append(session.note)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = StudyAnalytics.formatDurationHuman(session.durationMs),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit session", tint = colors.textDim, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete session", tint = colors.danger.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ManualSessionDialog(
    subjects: List<Subject>,
    defaultSubjectId: String,
    onDismiss: () -> Unit,
    onSave: (subjectId: String, startAtMs: Long, durationMin: Long, note: String) -> Unit,
) {
    val colors = LocalStudyColors.current
    var selectedSubId by remember { mutableStateOf(defaultSubjectId.ifBlank { subjects.firstOrNull()?.id.orEmpty() }) }
    var minutesText by remember { mutableStateOf("45") }
    var hoursAgoText by remember { mutableStateOf("0") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Add Manual Session", color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (subjects.isEmpty()) {
                    Text("Please add a subject first.", color = colors.danger)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subjects, key = { it.id }) { sub ->
                            val selected = sub.id == selectedSubId
                            val c = parseHexColor(sub.colorHex)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) c.subjectTint() else colors.surface2)
                                    .border(1.dp, if (selected) c else colors.border, RoundedCornerShape(14.dp))
                                    .clickable { selectedSubId = sub.id }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("${sub.emoji} ${sub.name}", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Duration (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hoursAgoText,
                    onValueChange = { hoursAgoText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Ended how many hours ago? (0 = now)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = minutesText.toLongOrNull()?.coerceIn(1L, 1440L) ?: 30L
                    val hrsAgo = hoursAgoText.toLongOrNull()?.coerceIn(0L, 720L) ?: 0L
                    val endMs = System.currentTimeMillis() - (hrsAgo * 3_600_000L)
                    val startMs = (endMs - mins * 60_000L).coerceAtLeast(1L)
                    if (selectedSubId.isNotBlank()) {
                        onSave(selectedSubId, startMs, mins, note)
                    }
                },
                enabled = selectedSubId.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Log Session")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditSessionDialog(
    session: StudySession,
    subjects: List<Subject>,
    onDismiss: () -> Unit,
    onSave: (StudySession) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalStudyColors.current
    var subjectId by remember { mutableStateOf(session.subjectId) }
    var durationText by remember { mutableStateOf(((session.durationMs / 60_000L).coerceAtLeast(1L)).toString()) }
    var note by remember { mutableStateOf(session.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Edit Session", color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(subjects, key = { it.id }) { sub ->
                        val selected = sub.id == subjectId
                        val c = parseHexColor(sub.colorHex)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) c.subjectTint() else colors.surface2)
                                .border(1.dp, if (selected) c else colors.border, RoundedCornerShape(14.dp))
                                .clickable { subjectId = sub.id }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("${sub.emoji} ${sub.name}", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Duration (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("Note") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = durationText.toLongOrNull()?.coerceIn(1L, 1440L) ?: 1L
                    val newDurMs = mins * 60_000L
                    onSave(
                        session.copy(
                            subjectId = subjectId,
                            durationMs = newDurMs,
                            endAtMs = session.startAtMs + newDurMs,
                            note = note.trim(),
                        )
                    )
                },
                shape = RoundedCornerShape(14.dp),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = colors.danger) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun AddSubjectDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, colorHex: String, emoji: String, courseCode: String) -> Unit,
) {
    val colors = LocalStudyColors.current
    var name by remember { mutableStateOf("") }
    var courseCode by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("⚙️") }
    var selectedColor by remember { mutableStateOf(SubjectPaletteHex.first()) }
    val quickEmojis = listOf("⚙️", "🔥", "💧", "📐", "⚡", "🧪", "💻", "📘", "🛠️", "📊")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("New Subject", color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Subject name (e.g. Thermodynamics)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("new_subject_name_input"),
                )
                OutlinedTextField(
                    value = courseCode,
                    onValueChange = { courseCode = it.take(32) },
                    label = { Text("Course code (optional, e.g. ME-201)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Emoji", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickEmojis) { em ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (emoji == em) colors.accent.copy(alpha = 0.2f) else colors.surface2)
                                .border(
                                    1.dp,
                                    if (emoji == em) colors.accent else colors.border,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { emoji = em },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(em, fontSize = 18.sp)
                        }
                    }
                }
                Text("Subject Color", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SubjectPaletteHex) { hex ->
                        val c = parseHexColor(hex)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (selectedColor == hex) 3.dp else 0.dp,
                                    color = if (selectedColor == hex) colors.text else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), selectedColor, emoji, courseCode.trim()) },
                enabled = name.trim().isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("confirm_add_subject_btn"),
            ) {
                Text("Add Subject")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
