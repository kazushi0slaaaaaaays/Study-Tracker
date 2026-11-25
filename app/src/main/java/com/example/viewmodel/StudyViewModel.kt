package com.example.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActiveSession
import com.example.data.BlockRule
import com.example.data.Chapter
import com.example.data.DailyStreak
import com.example.data.DeviceRecord
import com.example.data.Exam
import com.example.data.LinkedFileEntry
import com.example.data.LocalDeviceStore
import com.example.data.LocalFileStatus
import com.example.data.SessionBlocklist
import com.example.data.StudyGoal
import com.example.data.StudyRepository
import com.example.data.StudySession
import com.example.data.Subject
import com.example.data.UserSettings
import com.example.data.computePeriodBounds
import com.example.nativeandroid.InstalledAppInfo
import com.example.nativeandroid.InstalledAppsHelper
import com.example.nativeandroid.PermissionHelper
import com.example.nativeandroid.ReminderScheduler
import com.example.nativeandroid.SetupPermissionItem
import com.example.nativeandroid.StudyTimerWidgetProvider
import com.example.nativeandroid.StudyWatchdogJobService
import com.example.nativeandroid.TimerForegroundService
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudyAppState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
    val isOffline: Boolean = false,
    val offlineBannerDismissed: Boolean = false,
    val settings: UserSettings = UserSettings(),
    val subjects: List<Subject> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val sessions: List<StudySession> = emptyList(),
    val activeSession: ActiveSession = ActiveSession(),
    val goals: List<StudyGoal> = emptyList(),
    val exams: List<Exam> = emptyList(),
    val files: List<LinkedFileEntry> = emptyList(),
    val blockRules: List<BlockRule> = emptyList(),
    val sessionBlocklist: SessionBlocklist = SessionBlocklist(),
    val devices: List<DeviceRecord> = emptyList(),
    val dailyStreak: DailyStreak = DailyStreak(),
    val selectedSubjectId: String = "",
    val selectedMode: String = "stopwatch",
    val draftNote: String = "",
    val nowServerMs: Long = System.currentTimeMillis(),
    val nowGuardedMs: Long = System.currentTimeMillis(),
    val ruleUsageMs: Map<String, Long> = emptyMap(),
    val fileStatuses: Map<String, LocalFileStatus> = emptyMap(),
    val permissions: List<SetupPermissionItem> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val appsLoaded: Boolean = false,
    val showSetupChecklist: Boolean = false,
    val isFullscreenTimer: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
) {
    val activeSubjects: List<Subject>
        get() = subjects.filter { !it.archived }

    val effectiveSelectedSubjectId: String
        get() = selectedSubjectId.takeIf { id -> activeSubjects.any { it.id == id } }
            ?: activeSubjects.firstOrNull()?.id.orEmpty()

    val isBlockingActiveNow: Boolean
        get() = activeSession.active &&
            activeSession.status == "running" &&
            (activeSession.phase == "focus" || settings.blockDuringBreaks) &&
            sessionBlocklist.android.isNotEmpty()

    val hasRedPermission: Boolean
        get() = permissions.any { it.status == com.example.nativeandroid.PermissionStatusLevel.RED }

    val hasMissingBlockerPermission: Boolean
        get() = permissions.any {
            it.requiredForBlocking && it.status != com.example.nativeandroid.PermissionStatusLevel.GREEN
        }
}

class StudyViewModel(
    private val repository: StudyRepository,
    private val localStore: LocalDeviceStore,
    private val appContext: Context,
    private val currentUserId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        StudyAppState(
            deviceId = localStore.getOrCreateDeviceId(),
            deviceName = localStore.getDeviceFriendlyName(),
            showSetupChecklist = !localStore.isSetupCompleted(),
            permissions = PermissionHelper.evaluateAll(appContext, localStore),
        )
    )
    val state: StateFlow<StudyAppState> = _state.asStateFlow()

    private var serverOffsetMs: Long = localStore.getServerTimeOffsetMs()
    private var lastPhaseCompletedKey: String = ""
    private var dailyGoalCelebratedDay: String = ""
    private var weeklyGoalCelebratedKey: String = ""
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        StudyWatchdogJobService.schedulePeriodic(appContext)
        registerNetworkMonitor()
        observeAllCollections()
        startTickerLoop()
        registerDeviceHeartbeat()
    }

    private fun registerNetworkMonitor() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val activeNet = cm.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _state.update { it.copy(isOffline = !connected) }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.update { it.copy(isOffline = false) }
                syncLocalLocksToFirestore()
            }

            override fun onLost(network: Network) {
                _state.update { it.copy(isOffline = true, offlineBannerDismissed = false) }
            }
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        } catch (_: Exception) {
        }
    }

    private fun registerDeviceHeartbeat() {
        viewModelScope.launch {
            val res = repository.registerDeviceAndMeasureOffset(
                deviceId = _state.value.deviceId,
                deviceName = _state.value.deviceName,
            )
            res.onSuccess { offset ->
                serverOffsetMs = offset
                localStore.saveServerTimeOffsetMs(offset)
            }
        }
    }

    private fun <T> collectFirestoreStream(
        label: String,
        flow: Flow<T>,
        onValue: (T) -> Unit,
    ) {
        viewModelScope.launch {
            flow.catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Stream error ($label)", root)
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = root.message ?: "Failed to sync $label",
                    )
                }
            }.collect { value ->
                onValue(value)
            }
        }
    }

    private fun observeAllCollections() {
        collectFirestoreStream("settings", repository.observeSettings(currentUserId)) { settings ->
            _state.update { it.copy(settings = settings, isLoading = false) }
            ReminderScheduler.scheduleDailyStudyReminder(
                appContext,
                settings.dailyReminderHour.toInt(),
                settings.dailyReminderMinute.toInt(),
            )
            pushBlockerSnapshotToLocal()
        }

        collectFirestoreStream("subjects", repository.observeSubjects(currentUserId)) { subjects ->
            _state.update { cur ->
                val nextSelected = cur.selectedSubjectId.takeIf { id -> subjects.any { it.id == id && !it.archived } }
                    ?: subjects.firstOrNull { !it.archived }?.id.orEmpty()
                cur.copy(subjects = subjects, selectedSubjectId = nextSelected, isLoading = false)
            }
            pushTimerSnapshotToLocal()
        }

        collectFirestoreStream("chapters", repository.observeChapters(currentUserId)) { chapters ->
            _state.update { it.copy(chapters = chapters, isLoading = false) }
        }

        collectFirestoreStream("sessions", repository.observeSessions(currentUserId)) { sessions ->
            _state.update { it.copy(sessions = sessions, isLoading = false) }
        }

        collectFirestoreStream("activeSession", repository.observeActiveSession(currentUserId)) { active ->
            _state.update { cur ->
                cur.copy(
                    activeSession = active,
                    selectedMode = if (active.active) active.mode else cur.selectedMode,
                    selectedSubjectId = if (active.active && active.subjectId.isNotBlank()) active.subjectId else cur.selectedSubjectId,
                    isFullscreenTimer = if (!active.active) false else cur.isFullscreenTimer,
                    isLoading = false,
                )
            }
            pushTimerSnapshotToLocal()
        }

        collectFirestoreStream("goals", repository.observeGoals(currentUserId)) { goals ->
            _state.update { it.copy(goals = goals, isLoading = false) }
        }

        collectFirestoreStream("exams", repository.observeExams(currentUserId)) { exams ->
            _state.update { it.copy(exams = exams, isLoading = false) }
            ReminderScheduler.scheduleExamReminders(appContext, exams)
        }

        collectFirestoreStream("files", repository.observeFiles(currentUserId)) { files ->
            val statuses = files.associate { it.id to localStore.resolveFileStatus(it.id) }
            _state.update { it.copy(files = files, fileStatuses = statuses, isLoading = false) }
        }

        collectFirestoreStream("blockRules", repository.observeBlockRules(currentUserId)) { rules ->
            _state.update { it.copy(blockRules = rules, isLoading = false) }
            pushBlockerSnapshotToLocal()
            syncLocalLocksToFirestore()
        }

        collectFirestoreStream("sessionBlocklist", repository.observeSessionBlocklist(currentUserId)) { bl ->
            _state.update { it.copy(sessionBlocklist = bl, isLoading = false) }
            pushBlockerSnapshotToLocal()
        }

        collectFirestoreStream("devices", repository.observeDevices(currentUserId)) { devices ->
            _state.update { it.copy(devices = devices, isLoading = false) }
        }

        collectFirestoreStream("dailyStreak", repository.observeDailyStreak(currentUserId)) { streak ->
            _state.update { it.copy(dailyStreak = streak, isLoading = false) }
        }
    }

    private fun startTickerLoop() {
        viewModelScope.launch {
            while (true) {
                val nowServer = System.currentTimeMillis() + serverOffsetMs
                val nowGuarded = localStore.getGuardedNowMs()
                val cur = _state.value

                // Consume commands initiated from lock-screen notification or home-screen widget
                when (localStore.consumePendingTimerCommand()) {
                    TimerForegroundService.CMD_PAUSE -> pauseTimer()
                    TimerForegroundService.CMD_RESUME -> resumeTimer()
                    TimerForegroundService.CMD_STOP -> stopTimerAndLog()
                }

                // Refresh live foreground usage counters for Blocker tab
                val usageMap = localStore.getAllCurrentUsageMs(
                    cur.blockRules,
                    nowGuarded,
                    cur.settings.weekStartsOn,
                )

                _state.update {
                    it.copy(
                        nowServerMs = nowServer,
                        nowGuardedMs = nowGuarded,
                        ruleUsageMs = usageMap,
                    )
                }

                // Auto-advance Pomodoro phase when countdown reaches 0
                val active = cur.activeSession
                if (active.active && active.status == "running" && active.mode == "pomodoro" && active.targetMs > 0L) {
                    val elapsed = active.elapsedAt(nowServer)
                    if (elapsed >= active.targetMs) {
                        val phaseKey = "${active.phaseStartedAtMs}_${active.phase}_${active.cycleCount}"
                        if (phaseKey != lastPhaseCompletedKey) {
                            lastPhaseCompletedKey = phaseKey
                            onPomodoroPhaseComplete(active, nowServer)
                        }
                    }
                }

                delay(1000L)
            }
        }
    }

    private fun onPomodoroPhaseComplete(active: ActiveSession, nowServerMs: Long) {
        viewModelScope.launch {
            val s = _state.value.settings
            if (active.phase == "focus") {
                // Record completed focus session
                val durationMs = active.targetMs
                val startMs = (nowServerMs - durationMs).coerceAtLeast(1L)
                if (active.subjectId.isNotBlank()) {
                    repository.createSession(
                        StudySession(
                            subjectId = active.subjectId,
                            startAtMs = startMs,
                            endAtMs = nowServerMs,
                            durationMs = durationMs,
                            mode = "pomodoro",
                            note = active.note,
                            manual = false,
                            deviceId = _state.value.deviceId,
                        )
                    )
                    checkGoalCompletionAlerts(durationMs)
                    maybeIncrementDailyStreak(durationMs, nowServerMs)
                }
                val isLongBreak = active.cycleCount >= s.longEvery
                val nextPhase = if (isLongBreak) "long_break" else "break"
                val nextTargetMs = (if (isLongBreak) s.longBreakMin else s.breakMin) * 60_000L
                val nextSession = active.copy(
                    phase = nextPhase,
                    phaseStartedAtMs = nowServerMs,
                    accumulatedMs = 0L,
                    targetMs = nextTargetMs,
                    deviceId = _state.value.deviceId,
                    deviceName = _state.value.deviceName,
                )
                repository.setActiveSession(nextSession)
                ReminderScheduler.triggerPhaseOrGoalAlert(
                    appContext,
                    title = "Focus block complete",
                    body = if (isLongBreak) "Time for a ${s.longBreakMin}m long break." else "Take a ${s.breakMin}m break.",
                    sound = s.alertSound,
                    vibrate = s.alertVibration,
                )
            } else {
                // Break finished -> start next focus cycle
                val nextCycle = if (active.phase == "long_break") 1L else active.cycleCount + 1L
                val nextSession = active.copy(
                    phase = "focus",
                    phaseStartedAtMs = nowServerMs,
                    accumulatedMs = 0L,
                    targetMs = s.focusMin * 60_000L,
                    cycleCount = nextCycle,
                    deviceId = _state.value.deviceId,
                    deviceName = _state.value.deviceName,
                )
                repository.setActiveSession(nextSession)
                ReminderScheduler.triggerPhaseOrGoalAlert(
                    appContext,
                    title = "Break finished",
                    body = "Starting Focus cycle $nextCycle/${s.longEvery}.",
                    sound = s.alertSound,
                    vibrate = s.alertVibration,
                )
            }
        }
    }

    private fun checkGoalCompletionAlerts(addedMs: Long) {
        val cur = _state.value
        val nowMs = cur.nowServerMs
        val todayKey = StudyAnalytics.dayKeyOf(nowMs)
        val todayTotal = StudyAnalytics.totalStudyMsInRange(
            cur.sessions,
            StudyAnalytics.startOfTodayMs(nowMs),
        ) + addedMs
        val dailyGoalMs = cur.settings.dailyGoalMin * 60_000L
        if (todayTotal >= dailyGoalMs && dailyGoalCelebratedDay != todayKey) {
            dailyGoalCelebratedDay = todayKey
            ReminderScheduler.triggerPhaseOrGoalAlert(
                appContext,
                title = "Daily study goal reached!",
                body = "You hit ${cur.settings.dailyGoalMin} minutes today.",
                sound = cur.settings.alertSound,
                vibrate = cur.settings.alertVibration,
            )
        }
        val weekStart = StudyAnalytics.startOfWeekMs(nowMs, cur.settings.weekStartsOn)
        val weekKey = StudyAnalytics.dayKeyOf(weekStart)
        val weekTotal = StudyAnalytics.totalStudyMsInRange(cur.sessions, weekStart) + addedMs
        val weeklyGoalMs = cur.settings.weeklyGoalMin * 60_000L
        if (weekTotal >= weeklyGoalMs && weeklyGoalCelebratedKey != weekKey) {
            weeklyGoalCelebratedKey = weekKey
            ReminderScheduler.triggerPhaseOrGoalAlert(
                appContext,
                title = "Weekly study goal reached!",
                body = "You reached ${StudyAnalytics.formatDurationHuman(weeklyGoalMs)} this week.",
                sound = cur.settings.alertSound,
                vibrate = cur.settings.alertVibration,
            )
        }
    }

    private suspend fun maybeIncrementDailyStreak(durationMs: Long, completedAtMs: Long) {
        if (durationMs < DailyStreak.MIN_STREAK_SESSION_MS) return
        val todayKey = StudyAnalytics.dayKeyOf(completedAtMs)
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = completedAtMs
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayKey = StudyAnalytics.dayKeyOf(cal.timeInMillis)
        repository.recordQualifyingSessionForStreak(
            todayKey = todayKey,
            yesterdayKey = yesterdayKey,
            cachedStreak = _state.value.dailyStreak,
        ).onSuccess { (updated, incremented) ->
            _state.update { cur ->
                cur.copy(
                    dailyStreak = updated,
                    snackbarMessage = if (incremented) {
                        "Daily streak updated: ${updated.currentStreak} day${if (updated.currentStreak == 1L) "" else "s"}!"
                    } else {
                        cur.snackbarMessage
                    },
                )
            }
        }
    }

    private fun pushTimerSnapshotToLocal() {
        val cur = _state.value
        val active = cur.activeSession
        val subject = cur.subjects.firstOrNull { it.id == active.subjectId }
        localStore.saveTimerSnapshot(
            LocalDeviceStore.LocalTimerSnapshot(
                active = active.active,
                subjectId = active.subjectId,
                subjectName = subject?.name ?: "Study Session",
                subjectColorHex = subject?.colorHex ?: cur.settings.accentHex,
                mode = active.mode,
                status = active.status,
                phase = active.phase,
                phaseStartedAtMs = active.phaseStartedAtMs,
                accumulatedMs = active.accumulatedMs,
                targetMs = active.targetMs,
                cycleCount = active.cycleCount,
                longEvery = cur.settings.longEvery,
                blockDuringBreaks = cur.settings.blockDuringBreaks,
            )
        )
        TimerForegroundService.syncWithSnapshot(appContext, active.active)
        StudyTimerWidgetProvider.refreshAllWidgets(appContext)
    }

    private fun pushBlockerSnapshotToLocal() {
        val cur = _state.value
        localStore.saveBlockerConfig(
            sessionPackages = cur.sessionBlocklist.android,
            rules = cur.blockRules,
            weekStartsOn = cur.settings.weekStartsOn,
            strictLock = cur.settings.strictLock,
        )
    }

    fun syncLocalLocksToFirestore() {
        val localLocks = localStore.getLocalLockedUntilMap()
        if (localLocks.isEmpty()) return
        val rulesById = _state.value.blockRules.associateBy { it.id }
        viewModelScope.launch {
            for ((ruleId, localLockMs) in localLocks) {
                val remoteRule = rulesById[ruleId] ?: continue
                if (localLockMs > remoteRule.lockedUntilMs()) {
                    repository.lockBlockRuleUntil(ruleId, localLockMs)
                }
            }
        }
    }

    // --- UI STATE & NAVIGATION HELPERS ---

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
    fun clearSnackbar() = _state.update { it.copy(snackbarMessage = null) }
    fun dismissOfflineBanner() = _state.update { it.copy(offlineBannerDismissed = true) }
    fun setFullscreenTimer(enabled: Boolean) = _state.update { it.copy(isFullscreenTimer = enabled) }
    fun openSetupChecklist() = _state.update { it.copy(showSetupChecklist = true) }
    fun closeSetupChecklist(markComplete: Boolean) {
        if (markComplete) localStore.setSetupCompleted(true)
        _state.update { it.copy(showSetupChecklist = false) }
    }

    fun refreshPermissions() {
        _state.update { it.copy(permissions = PermissionHelper.evaluateAll(appContext, localStore)) }
    }

    fun refreshFileStatuses() {
        val statuses = _state.value.files.associate { it.id to localStore.resolveFileStatus(it.id) }
        _state.update { it.copy(fileStatuses = statuses) }
    }

    fun loadInstalledAppsIfNeeded() {
        if (_state.value.appsLoaded) return
        viewModelScope.launch {
            val apps = InstalledAppsHelper.loadInstalledLaunchableApps(appContext)
            _state.update { it.copy(installedApps = apps, appsLoaded = true) }
        }
    }

    fun selectSubject(subjectId: String) = _state.update { it.copy(selectedSubjectId = subjectId) }
    fun selectMode(mode: String) = _state.update { it.copy(selectedMode = mode) }
    fun updateDraftNote(note: String) = _state.update { it.copy(draftNote = note.take(500)) }

    // --- TIMER ACTIONS ---

    fun startTimer() {
        val cur = _state.value
        val existingSubjectId = cur.effectiveSelectedSubjectId
        val nowServer = System.currentTimeMillis() + serverOffsetMs
        val targetMs = if (cur.selectedMode == "pomodoro") cur.settings.focusMin * 60_000L else 0L

        viewModelScope.launch {
            val subjectId = if (existingSubjectId.isNotBlank()) {
                existingSubjectId
            } else {
                val created = repository.createSubject(
                    Subject(
                        name = "General Study",
                        colorHex = cur.settings.accentHex,
                        emoji = "📘",
                        courseCode = "",
                        order = 0L,
                    )
                )
                val newId = created.getOrElse {
                    showError(it)
                    return@launch
                }
                _state.update { it.copy(selectedSubjectId = newId) }
                newId
            }

            val session = ActiveSession(
                active = true,
                subjectId = subjectId,
                mode = cur.selectedMode,
                status = "running",
                phase = "focus",
                phaseStartedAtMs = nowServer,
                accumulatedMs = 0L,
                targetMs = targetMs,
                cycleCount = 1L,
                note = cur.draftNote.trim(),
                deviceId = cur.deviceId,
                deviceName = cur.deviceName,
            )
            repository.setActiveSession(session).onFailure { showError(it) }
        }
    }

    fun pauseTimer() {
        val active = _state.value.activeSession
        if (!active.active || active.status != "running") return
        val nowServer = System.currentTimeMillis() + serverOffsetMs
        val elapsed = active.elapsedAt(nowServer)
        val paused = active.copy(
            status = "paused",
            accumulatedMs = elapsed,
            phaseStartedAtMs = 0L,
            deviceId = _state.value.deviceId,
            deviceName = _state.value.deviceName,
        )
        viewModelScope.launch {
            repository.setActiveSession(paused).onFailure { showError(it) }
        }
    }

    fun resumeTimer() {
        val active = _state.value.activeSession
        if (!active.active || active.status != "paused") return
        val nowServer = System.currentTimeMillis() + serverOffsetMs
        val resumed = active.copy(
            status = "running",
            phaseStartedAtMs = nowServer,
            deviceId = _state.value.deviceId,
            deviceName = _state.value.deviceName,
        )
        viewModelScope.launch {
            repository.setActiveSession(resumed).onFailure { showError(it) }
        }
    }

    fun stopTimerAndLog() {
        val cur = _state.value
        val active = cur.activeSession
        if (!active.active) return
        val nowServer = System.currentTimeMillis() + serverOffsetMs
        val elapsedMs = active.elapsedAt(nowServer)
        viewModelScope.launch {
            // Log study session if it was a focus phase (or stopwatch) with >= 10 seconds
            if (active.phase == "focus" && elapsedMs >= 10_000L && active.subjectId.isNotBlank()) {
                val startMs = (nowServer - elapsedMs).coerceAtLeast(1L)
                repository.createSession(
                    StudySession(
                        subjectId = active.subjectId,
                        startAtMs = startMs,
                        endAtMs = nowServer,
                        durationMs = elapsedMs,
                        mode = active.mode,
                        note = active.note,
                        manual = false,
                        deviceId = cur.deviceId,
                    )
                ).onSuccess {
                    checkGoalCompletionAlerts(elapsedMs)
                    maybeIncrementDailyStreak(elapsedMs, nowServer)
                    _state.update {
                        it.copy(
                            draftNote = "",
                            snackbarMessage = it.snackbarMessage
                                ?: "Saved ${StudyAnalytics.formatDurationHuman(elapsedMs)} session",
                        )
                    }
                }.onFailure { showError(it) }
            }
            repository.setActiveSession(
                ActiveSession(
                    active = false,
                    subjectId = active.subjectId,
                    mode = active.mode,
                    status = "idle",
                    phase = "focus",
                    deviceId = cur.deviceId,
                    deviceName = cur.deviceName,
                )
            ).onFailure { showError(it) }
            _state.update { it.copy(isFullscreenTimer = false) }
        }
    }

    fun addManualSession(subjectId: String, startAtMs: Long, durationMin: Long, note: String) {
        val durMs = (durationMin.coerceIn(1L, 1440L)) * 60_000L
        val endMs = startAtMs + durMs
        viewModelScope.launch {
            repository.createSession(
                StudySession(
                    subjectId = subjectId,
                    startAtMs = startAtMs,
                    endAtMs = endMs,
                    durationMs = durMs,
                    mode = "stopwatch",
                    note = note.trim(),
                    manual = true,
                    deviceId = _state.value.deviceId,
                )
            ).onSuccess {
                checkGoalCompletionAlerts(durMs)
                maybeIncrementDailyStreak(durMs, endMs)
                _state.update {
                    it.copy(
                        snackbarMessage = it.snackbarMessage ?: "Manual session logged"
                    )
                }
            }.onFailure { showError(it) }
        }
    }

    fun updateSession(session: StudySession) {
        viewModelScope.launch {
            repository.updateSession(session)
                .onSuccess { _state.update { it.copy(snackbarMessage = "Session updated") } }
                .onFailure { showError(it) }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
                .onSuccess { _state.update { it.copy(snackbarMessage = "Session deleted") } }
                .onFailure { showError(it) }
        }
    }

    // --- SUBJECTS & CHAPTERS ---

    fun addSubject(name: String, colorHex: String, emoji: String, courseCode: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val nextOrder = (_state.value.subjects.maxOfOrNull { it.order } ?: -1L) + 1L
        viewModelScope.launch {
            repository.createSubject(
                Subject(
                    name = trimmed,
                    colorHex = colorHex,
                    emoji = emoji.ifBlank { "📘" },
                    courseCode = courseCode.trim(),
                    order = nextOrder,
                )
            ).onSuccess { newId ->
                _state.update {
                    it.copy(
                        selectedSubjectId = it.selectedSubjectId.ifBlank { newId },
                        snackbarMessage = "Added $trimmed",
                    )
                }
            }.onFailure { showError(it) }
        }
    }

    fun updateSubject(subject: Subject) {
        viewModelScope.launch {
            repository.updateSubject(subject).onFailure { showError(it) }
        }
    }

    fun deleteSubject(subjectId: String) {
        viewModelScope.launch {
            repository.deleteSubject(subjectId).onFailure { showError(it) }
        }
    }

    fun addChapter(subjectId: String, parentId: String, title: String, dueDate: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || subjectId.isBlank()) return
        val siblings = _state.value.chapters.filter { it.subjectId == subjectId && it.parentId == parentId }
        val nextOrder = (siblings.maxOfOrNull { it.order } ?: -1L) + 1L
        viewModelScope.launch {
            repository.createChapter(
                Chapter(
                    subjectId = subjectId,
                    parentId = parentId,
                    title = trimmed,
                    order = nextOrder,
                    done = false,
                    doneAtMs = 0L,
                    dueDate = dueDate.trim(),
                )
            ).onFailure { showError(it) }
        }
    }

    fun toggleChapterDone(chapter: Chapter) {
        val nextDone = !chapter.done
        val updated = chapter.copy(
            done = nextDone,
            doneAtMs = if (nextDone) System.currentTimeMillis() else 0L,
        )
        viewModelScope.launch {
            repository.updateChapter(updated).onFailure { showError(it) }
        }
    }

    fun reorderChapters(orderedIds: List<String>) {
        viewModelScope.launch {
            repository.reorderChapters(orderedIds).onFailure { showError(it) }
        }
    }

    fun deleteChapter(chapterId: String) {
        viewModelScope.launch {
            repository.deleteChapter(chapterId).onFailure { showError(it) }
        }
    }

    // --- GOALS & EXAMS ---

    fun saveGoal(goal: StudyGoal) {
        viewModelScope.launch {
            repository.upsertGoal(goal)
                .onSuccess { _state.update { it.copy(snackbarMessage = "Goal saved") } }
                .onFailure { showError(it) }
        }
    }

    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            repository.deleteGoal(goalId).onFailure { showError(it) }
        }
    }

    fun addExam(title: String, subjectId: String, date: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || date.length < 8) return
        viewModelScope.launch {
            repository.createExam(
                Exam(
                    title = trimmed,
                    subjectId = subjectId,
                    date = date,
                    reminderDays = listOf(7L, 3L, 1L),
                )
            ).onSuccess {
                _state.update { it.copy(snackbarMessage = "Exam added with 7, 3, and 1-day reminders") }
            }.onFailure { showError(it) }
        }
    }

    fun deleteExam(examId: String) {
        viewModelScope.launch {
            repository.deleteExam(examId).onFailure { showError(it) }
        }
    }

    // --- LINKED FILES (URI stays strictly local in LocalDeviceStore) ---

    fun addLinkedFile(subjectId: String, displayName: String, mime: String, localUriString: String) {
        val order = (_state.value.files.filter { it.subjectId == subjectId }.maxOfOrNull { it.order } ?: -1L) + 1L
        viewModelScope.launch {
            repository.createFileEntry(
                LinkedFileEntry(
                    subjectId = subjectId,
                    displayName = displayName.ifBlank { "Untitled File" },
                    mime = mime,
                    order = order,
                )
            ).onSuccess { fileId ->
                localStore.saveFileUri(fileId, localUriString)
                refreshFileStatuses()
                _state.update { it.copy(snackbarMessage = "File linked on this device") }
            }.onFailure { showError(it) }
        }
    }

    fun relinkLocalFile(fileId: String, localUriString: String) {
        localStore.saveFileUri(fileId, localUriString)
        refreshFileStatuses()
        _state.update { it.copy(snackbarMessage = "File re-linked on this device") }
    }

    fun getLocalFileUri(fileId: String): String? = localStore.getFileUri(fileId)

    fun renameFile(entry: LinkedFileEntry, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.updateFileEntry(entry.copy(displayName = trimmed)).onFailure { showError(it) }
        }
    }

    fun moveFileToSubject(entry: LinkedFileEntry, newSubjectId: String) {
        viewModelScope.launch {
            repository.updateFileEntry(entry.copy(subjectId = newSubjectId)).onFailure { showError(it) }
        }
    }

    fun removeFileFromList(fileId: String) {
        localStore.removeFileUri(fileId)
        viewModelScope.launch {
            repository.deleteFileEntry(fileId)
                .onSuccess { _state.update { it.copy(snackbarMessage = "Removed from list (original file untouched)") } }
                .onFailure { showError(it) }
        }
    }

    // --- APP BLOCKER ---

    fun toggleSessionBlockedPackage(packageName: String) {
        val current = _state.value.sessionBlocklist
        val nextAndroid = if (packageName in current.android) {
            current.android - packageName
        } else {
            current.android + packageName
        }
        val updated = current.copy(android = nextAndroid)
        viewModelScope.launch {
            repository.saveSessionBlocklist(updated).onFailure { showError(it) }
        }
    }

    fun addBlockRule(targetId: String, targetLabel: String, allowanceMin: Long, periodType: String, periodCount: Long) {
        val cur = _state.value
        val existing = cur.blockRules.firstOrNull { it.platform == "android" && it.targetId == targetId }
        if (existing != null) {
            _state.update { it.copy(snackbarMessage = "A limit rule already exists for $targetLabel") }
            return
        }
        viewModelScope.launch {
            val strictLockUntil = if (cur.settings.strictLock) {
                val bounds = computePeriodBounds(cur.nowGuardedMs, periodType, periodCount, cur.settings.weekStartsOn)
                com.google.firebase.Timestamp(java.util.Date(bounds.endMs))
            } else null
            repository.createBlockRule(
                BlockRule(
                    platform = "android",
                    kind = "limit",
                    targetId = targetId,
                    targetLabel = targetLabel,
                    allowanceMin = allowanceMin.coerceIn(1L, 1440L),
                    periodType = periodType,
                    periodCount = periodCount.coerceIn(1L, 24L),
                    lockedUntil = strictLockUntil,
                )
            ).onSuccess {
                _state.update { it.copy(snackbarMessage = "Usage limit added for $targetLabel") }
            }.onFailure { showError(it) }
        }
    }

    fun deleteBlockRule(rule: BlockRule) {
        val nowGuarded = _state.value.nowGuardedMs
        val localLock = localStore.getLocalLockedUntilMap()[rule.id] ?: 0L
        val effectiveLock = maxOf(rule.lockedUntilMs(), localLock)
        if (effectiveLock > nowGuarded) {
            _state.update {
                it.copy(snackbarMessage = "Locked until period ends. Rules cannot be removed while locked.")
            }
            return
        }
        if (_state.value.settings.strictLock) {
            val bounds = computePeriodBounds(nowGuarded, rule.periodType, rule.periodCount, _state.value.settings.weekStartsOn)
            val used = _state.value.ruleUsageMs[rule.id] ?: 0L
            if (used > 0L && nowGuarded < bounds.endMs) {
                _state.update {
                    it.copy(snackbarMessage = "Strict lock is active — rules cannot be deleted mid-period.")
                }
                return
            }
        }
        viewModelScope.launch {
            repository.deleteBlockRule(rule.id)
                .onSuccess { _state.update { it.copy(snackbarMessage = "Removed limit for ${rule.targetLabel}") } }
                .onFailure { showError(it) }
        }
    }

    // --- SETTINGS & DATA EXPORT/IMPORT ---

    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val updated = transform(_state.value.settings)
        _state.update { it.copy(settings = updated) }
        viewModelScope.launch {
            repository.saveSettings(updated).onFailure { showError(it) }
        }
    }

    fun buildBackupJson(): String {
        val s = _state.value
        return repository.exportAllToJson(
            settings = s.settings,
            subjects = s.subjects,
            chapters = s.chapters,
            sessions = s.sessions,
            goals = s.goals,
            exams = s.exams,
            files = s.files,
            blockRules = s.blockRules,
            sessionBlocklist = s.sessionBlocklist,
        )
    }

    fun buildSessionsCsv(): String {
        val s = _state.value
        return repository.exportSessionsToCsv(s.sessions, s.subjects)
    }

    fun importBackupJson(jsonText: String, replaceExisting: Boolean) {
        viewModelScope.launch {
            repository.importFromJson(jsonText, replaceExisting)
                .onSuccess { count ->
                    _state.update { it.copy(snackbarMessage = "Imported $count items successfully") }
                }
                .onFailure { showError(it) }
        }
    }

    private fun showError(e: Throwable) {
        _state.update {
            it.copy(snackbarMessage = e.localizedMessage ?: "Operation failed")
        }
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let { cb ->
            try {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "StudyViewModel"
    }
}
