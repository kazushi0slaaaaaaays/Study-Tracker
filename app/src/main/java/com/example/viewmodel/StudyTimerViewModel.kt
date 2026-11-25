package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Focused UI state for the primary digital clock and timer controls.
 */
data class StudyTimerUiState(
    val formattedTime: String = "00:00",
    val prefixText: String = "00:",
    val secondsText: String = "00",
    val hasHours: Boolean = false,
    val isActive: Boolean = false,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val mode: String = "stopwatch",
    val phaseLabel: String = "Ready",
    val progress: Float = 0f,
    val subjectName: String = "General Study",
    val subjectEmoji: String = "📘",
    val subjectColorHex: String = "#6C7BFF",
    val todayTotalMs: Long = 0L,
    val dailyGoalMs: Long = 120L * 60_000L,
    val dailyProgress: Float = 0f,
    val currentStreakDays: Long = 0L,
    val longestStreakDays: Long = 0L,
    val streakCompletedToday: Boolean = false,
    val isBlockingActive: Boolean = false,
    val blockedAppCount: Int = 0,
    val remoteDeviceName: String? = null,
)

/**
 * Dedicated ViewModel powering the primary Study Timer screen's digital clock display
 * and Start / Pause / Resume / Stop controls, synchronized with Firebase Firestore and
 * native lock-screen/widget/blocker services via [StudyViewModel].
 */
class StudyTimerViewModel(
    private val studyViewModel: StudyViewModel,
) : ViewModel() {

    val timerState: StateFlow<StudyTimerUiState> = studyViewModel.state
        .map { appState -> mapToTimerUiState(appState) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = mapToTimerUiState(studyViewModel.state.value),
        )

    fun start() {
        val active = studyViewModel.state.value.activeSession
        if (active.active && active.status == "paused") {
            studyViewModel.resumeTimer()
        } else if (!active.active) {
            studyViewModel.startTimer()
        }
    }

    fun pause() {
        studyViewModel.pauseTimer()
    }

    fun resume() {
        studyViewModel.resumeTimer()
    }

    fun toggleStartPause() {
        val active = studyViewModel.state.value.activeSession
        when {
            !active.active -> studyViewModel.startTimer()
            active.status == "running" -> studyViewModel.pauseTimer()
            else -> studyViewModel.resumeTimer()
        }
    }

    fun stop() {
        studyViewModel.stopTimerAndLog()
    }

    fun selectMode(mode: String) {
        studyViewModel.selectMode(mode)
    }

    fun selectSubject(subjectId: String) {
        studyViewModel.selectSubject(subjectId)
    }

    fun updateDraftNote(note: String) {
        studyViewModel.updateDraftNote(note)
    }

    fun openFullscreen() {
        if (studyViewModel.state.value.activeSession.active) {
            studyViewModel.setFullscreenTimer(true)
        }
    }

    private fun mapToTimerUiState(state: StudyAppState): StudyTimerUiState {
        val active = state.activeSession
        val activeSubjectId = if (active.active && active.subjectId.isNotBlank()) {
            active.subjectId
        } else {
            state.effectiveSelectedSubjectId
        }
        val subject = state.subjects.firstOrNull { it.id == activeSubjectId }
        val subjectColorHex = subject?.colorHex ?: state.settings.accentHex
        val subjectName = subject?.name ?: "General Study"
        val subjectEmoji = subject?.emoji ?: "📘"

        val isRunning = active.active && active.status == "running"
        val isPaused = active.active && active.status == "paused"

        val elapsedMs = if (active.active) active.elapsedAt(state.nowServerMs) else 0L
        val displayMs = when {
            active.active && active.mode == "pomodoro" && active.targetMs > 0L ->
                active.remainingAt(state.nowServerMs)
            active.active -> elapsedMs
            state.selectedMode == "pomodoro" -> state.settings.focusMin * 60_000L
            else -> 0L
        }

        val (prefix, secs, hasHours) = StudyAnalytics.formatTimerParts(displayMs)

        val progress = when {
            active.active && active.mode == "pomodoro" && active.targetMs > 0L ->
                (elapsedMs.toFloat() / active.targetMs.toFloat()).coerceIn(0f, 1f)
            active.active ->
                ((elapsedMs % 3_600_000L).toFloat() / 3_600_000f).coerceIn(0.01f, 1f)
            else -> 0f
        }

        val phaseLabel = when {
            !active.active && state.selectedMode == "pomodoro" ->
                "Ready · Pomodoro ${state.settings.focusMin}m"
            !active.active -> "Ready · Stopwatch"
            isPaused -> "Paused"
            active.mode == "pomodoro" && active.phase == "focus" ->
                "Focus ${active.cycleCount}/${state.settings.longEvery}"
            active.mode == "pomodoro" && active.phase == "long_break" -> "Long Break"
            active.mode == "pomodoro" -> "Short Break"
            else -> "Focusing · Stopwatch"
        }

        val todayStartMs = StudyAnalytics.startOfTodayMs(state.nowServerMs)
        val todaySavedMs = StudyAnalytics.totalStudyMsInRange(state.sessions, todayStartMs)
        val liveAddedMs = if (active.active && active.phase == "focus") elapsedMs else 0L
        val todayTotalMs = todaySavedMs + liveAddedMs
        val dailyGoalMs = (state.settings.dailyGoalMin * 60_000L).coerceAtLeast(60_000L)
        val dailyProgress = (todayTotalMs.toFloat() / dailyGoalMs.toFloat()).coerceIn(0f, 1f)

        val todayKey = StudyAnalytics.dayKeyOf(state.nowServerMs)
        val yesterdayCal = java.util.Calendar.getInstance().apply {
            timeInMillis = state.nowServerMs
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayKey = StudyAnalytics.dayKeyOf(yesterdayCal.timeInMillis)
        val effectiveStreak = state.dailyStreak.effectiveStreakCount(todayKey, yesterdayKey)
        val streakCompletedToday = state.dailyStreak.isCompletedToday(todayKey)

        val remoteDevice = if (active.active && active.deviceId.isNotBlank() && active.deviceId != state.deviceId) {
            active.deviceName.ifBlank { "another device" }
        } else null

        return StudyTimerUiState(
            formattedTime = "$prefix$secs",
            prefixText = prefix,
            secondsText = secs,
            hasHours = hasHours,
            isActive = active.active,
            isRunning = isRunning,
            isPaused = isPaused,
            elapsedMs = elapsedMs,
            mode = if (active.active) active.mode else state.selectedMode,
            phaseLabel = phaseLabel,
            progress = progress,
            subjectName = subjectName,
            subjectEmoji = subjectEmoji,
            subjectColorHex = subjectColorHex,
            todayTotalMs = todayTotalMs,
            dailyGoalMs = dailyGoalMs,
            dailyProgress = dailyProgress,
            currentStreakDays = effectiveStreak,
            longestStreakDays = state.dailyStreak.longestStreak,
            streakCompletedToday = streakCompletedToday,
            isBlockingActive = state.isBlockingActiveNow,
            blockedAppCount = state.sessionBlocklist.android.size,
            remoteDeviceName = remoteDevice,
        )
    }
}
