package com.example.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.parseHexColor
import com.example.viewmodel.StudyAnalytics
import com.example.viewmodel.StudyAppState
import kotlinx.coroutines.delay

/**
 * Giant Full-Screen Timer (PDF Section 5B & 7.4):
 * - Immersive edge-to-edge pure black (or theme bg), system bars hidden, screen kept awake
 * - Giant weight-200 tabular digits filling viewport width/height (MM:SS -> H:MM:SS at >=60m)
 * - Subject color at 100% for seconds and 85% white for the rest
 * - Subject name in small caps + emoji above; very thin progress line across width below
 * - 3 ghost buttons (Pause/Resume, Stop, Exit) fading in on tap and auto-hiding after 3s
 * - Tiny lock icon in corner while blocking is active
 * - Burn-in protection: shifts layout 2-4 dp every minute
 */
@Composable
fun FullscreenTimerOverlay(
    state: StudyAppState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onExit() }

    val view = LocalView.current
    val colors = LocalStudyColors.current
    val active = state.activeSession
    val subject = state.subjects.firstOrNull { it.id == active.subjectId }
    val subjectColor = parseHexColor(subject?.colorHex ?: state.settings.accentHex)

    // Immersive full-screen mode + keep screen awake
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Controls auto-hide after 3 seconds
    var showControls by remember { mutableStateOf(true) }
    var tapCounter by remember { mutableIntStateOf(0) }
    LaunchedEffect(tapCounter, showControls) {
        if (showControls) {
            delay(3000L)
            showControls = false
        }
    }

    // AMOLED burn-in protection: shift 2-4 dp every minute
    var shiftStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            shiftStep = (shiftStep + 1) % 6
        }
    }
    val (offsetX, offsetY) = when (shiftStep) {
        0 -> 0.dp to 0.dp
        1 -> 3.dp to (-2).dp
        2 -> (-3).dp to 2.dp
        3 -> 2.dp to 3.dp
        4 -> (-2).dp to (-3).dp
        else -> 4.dp to 0.dp
    }

    val elapsedMs = active.elapsedAt(state.nowServerMs)
    val displayMs = if (active.mode == "pomodoro" && active.targetMs > 0L) {
        active.remainingAt(state.nowServerMs)
    } else {
        elapsedMs
    }
    val (prefixText, secondsText, hasHours) = StudyAnalytics.formatTimerParts(displayMs)

    val progress = if (active.mode == "pomodoro" && active.targetMs > 0L) {
        (elapsedMs.toFloat() / active.targetMs.toFloat()).coerceIn(0f, 1f)
    } else {
        val todayBase = StudyAnalytics.totalStudyMsInRange(
            state.sessions,
            StudyAnalytics.startOfTodayMs(state.nowServerMs),
        )
        val goalMs = (state.settings.dailyGoalMin * 60_000L).coerceAtLeast(60_000L)
        ((todayBase + elapsedMs).toFloat() / goalMs.toFloat()).coerceIn(0f, 1f)
    }

    val bgColor = if (colors.isDark) Color.Black else colors.bg
    val mainDigitColor = if (colors.isDark) Color.White.copy(alpha = 0.85f) else colors.text.copy(alpha = 0.85f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                showControls = true
                tapCounter++
            }
            .testTag("fullscreen_timer_root"),
    ) {
        val isLandscape = maxWidth > maxHeight
        val baseDimDp = if (isLandscape) maxHeight.value else maxWidth.value
        val digitSp = if (hasHours) {
            (baseDimDp * (if (isLandscape) 0.34f else 0.195f)).coerceIn(52f, 220f).sp
        } else {
            (baseDimDp * (if (isLandscape) 0.44f else 0.275f)).coerceIn(72f, 280f).sp
        }

        // Tiny lock icon in top-left corner when blocking is active
        if (state.isBlockingActiveNow) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = "Blocking active",
                tint = colors.success.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .size(18.dp),
            )
        }

        // Centered giant timer with AMOLED burn-in pixel shift
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = offsetX, y = offsetY)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val subjectHeader = buildString {
                if (!subject?.emoji.isNullOrBlank()) {
                    append(subject?.emoji)
                    append("  ")
                }
                append((subject?.name ?: "STUDY").uppercase())
                if (active.mode == "pomodoro") {
                    append("  ·  ")
                    append(
                        when (active.phase) {
                            "focus" -> "FOCUS ${active.cycleCount}/${state.settings.longEvery}"
                            "long_break" -> "LONG BREAK"
                            else -> "BREAK"
                        }
                    )
                }
            }

            Text(
                text = subjectHeader,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                letterSpacing = 2.4.sp,
                color = mainDigitColor.copy(alpha = 0.65f),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = mainDigitColor)) {
                        append(prefixText)
                    }
                    withStyle(SpanStyle(color = subjectColor)) {
                        append(secondsText)
                    }
                },
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.ExtraLight,
                fontSize = digitSp,
                letterSpacing = (-2).sp,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
            )

            Spacer(modifier = Modifier.height(20.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = subjectColor,
                trackColor = mainDigitColor.copy(alpha = 0.14f),
            )
        }

        // Three ghost buttons fading in on tap & auto-hiding after 3s
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isRunning = active.status == "running"
                GhostTimerButton(
                    icon = if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    label = if (isRunning) "Pause" else "Resume",
                    tint = mainDigitColor,
                    onClick = {
                        tapCounter++
                        if (isRunning) onPause() else onResume()
                    },
                    testTag = "fullscreen_pause_resume_btn",
                )
                GhostTimerButton(
                    icon = Icons.Rounded.Stop,
                    label = "Stop",
                    tint = colors.danger,
                    onClick = {
                        onStop()
                    },
                    testTag = "fullscreen_stop_btn",
                )
                GhostTimerButton(
                    icon = Icons.Outlined.FullscreenExit,
                    label = "Exit",
                    tint = mainDigitColor,
                    onClick = onExit,
                    testTag = "fullscreen_exit_btn",
                )
            }
        }
    }
}

@Composable
private fun GhostTimerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(text = label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
