package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserSettings(
    val theme: String = "dark",
    val accentHex: String = "#6C7BFF",
    val focusMin: Long = 25L,
    val breakMin: Long = 5L,
    val longBreakMin: Long = 15L,
    val longEvery: Long = 4L,
    val weekStartsOn: String = "sat",
    val dailyGoalMin: Long = 120L,
    val weeklyGoalMin: Long = 840L,
    val streakMinMinutes: Long = 120L,
    val blockDuringBreaks: Boolean = false,
    val strictLock: Boolean = false,
    val alertSound: Boolean = true,
    val alertVibration: Boolean = true,
    val dailyReminderHour: Long = -1L,
    val dailyReminderMinute: Long = 0L,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toWriteMap(): Map<String, Any> = mapOf(
        "theme" to theme.ifBlank { "dark" },
        "accentHex" to accentHex.ifBlank { "#6C7BFF" },
        "focusMin" to focusMin.coerceIn(1L, 180L),
        "breakMin" to breakMin.coerceIn(1L, 60L),
        "longBreakMin" to longBreakMin.coerceIn(1L, 90L),
        "longEvery" to longEvery.coerceIn(1L, 12L),
        "weekStartsOn" to weekStartsOn.ifBlank { "sat" },
        "dailyGoalMin" to dailyGoalMin.coerceIn(5L, 1440L),
        "weeklyGoalMin" to weeklyGoalMin.coerceIn(30L, 10080L),
        "streakMinMinutes" to streakMinMinutes.coerceIn(5L, 1440L),
        "blockDuringBreaks" to blockDuringBreaks,
        "strictLock" to strictLock,
        "alertSound" to alertSound,
        "alertVibration" to alertVibration,
        "dailyReminderHour" to dailyReminderHour.coerceIn(-1L, 23L),
        "dailyReminderMinute" to dailyReminderMinute.coerceIn(0L, 59L),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class Subject(
    @DocumentId val id: String = "",
    val name: String = "",
    val colorHex: String = "#6C7BFF",
    val emoji: String = "📐",
    val courseCode: String = "",
    val order: Long = 0L,
    val archived: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "name" to name.trim().take(80),
        "colorHex" to colorHex.ifBlank { "#6C7BFF" }.take(16),
        "emoji" to emoji.take(16),
        "courseCode" to courseCode.trim().take(32),
        "order" to order.coerceIn(0L, 100000L),
        "archived" to archived,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "name" to name.trim().take(80),
        "colorHex" to colorHex.ifBlank { "#6C7BFF" }.take(16),
        "emoji" to emoji.take(16),
        "courseCode" to courseCode.trim().take(32),
        "order" to order.coerceIn(0L, 100000L),
        "archived" to archived,
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class Chapter(
    @DocumentId val id: String = "",
    val subjectId: String = "",
    val parentId: String = "",
    val title: String = "",
    val order: Long = 0L,
    val done: Boolean = false,
    val doneAtMs: Long = 0L,
    val dueDate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "subjectId" to subjectId,
        "parentId" to parentId.take(128),
        "title" to title.trim().take(200),
        "order" to order.coerceIn(0L, 100000L),
        "done" to done,
        "doneAtMs" to doneAtMs.coerceAtLeast(0L),
        "dueDate" to dueDate.trim().take(32),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "subjectId" to subjectId,
        "parentId" to parentId.take(128),
        "title" to title.trim().take(200),
        "order" to order.coerceIn(0L, 100000L),
        "done" to done,
        "doneAtMs" to doneAtMs.coerceAtLeast(0L),
        "dueDate" to dueDate.trim().take(32),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class StudySession(
    @DocumentId val id: String = "",
    val subjectId: String = "",
    val startAtMs: Long = 0L,
    val endAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val mode: String = "stopwatch",
    val note: String = "",
    val manual: Boolean = false,
    val deviceId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> {
        val safeStart = startAtMs.coerceAtLeast(1L)
        val safeEnd = endAtMs.coerceAtLeast(safeStart)
        val safeDur = durationMs.coerceIn(0L, 86400000L)
        return mapOf(
            "subjectId" to subjectId,
            "startAtMs" to safeStart,
            "endAtMs" to safeEnd,
            "durationMs" to safeDur,
            "mode" to if (mode == "pomodoro") "pomodoro" else "stopwatch",
            "note" to note.trim().take(500),
            "manual" to manual,
            "deviceId" to deviceId.ifBlank { "android_device" }.take(128),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
    }

    fun toUpdateMap(): Map<String, Any> {
        val safeStart = startAtMs.coerceAtLeast(1L)
        val safeEnd = endAtMs.coerceAtLeast(safeStart)
        val safeDur = durationMs.coerceIn(0L, 86400000L)
        return mapOf(
            "subjectId" to subjectId,
            "startAtMs" to safeStart,
            "endAtMs" to safeEnd,
            "durationMs" to safeDur,
            "mode" to if (mode == "pomodoro") "pomodoro" else "stopwatch",
            "note" to note.trim().take(500),
            "manual" to manual,
            "deviceId" to deviceId.ifBlank { "android_device" }.take(128),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
    }
}

@Keep
@IgnoreExtraProperties
data class ActiveSession(
    val active: Boolean = false,
    val subjectId: String = "",
    val mode: String = "stopwatch",
    val status: String = "idle", // "idle", "running", "paused"
    val phase: String = "focus", // "focus", "break", "long_break"
    val phaseStartedAtMs: Long = 0L,
    val accumulatedMs: Long = 0L,
    val targetMs: Long = 0L,
    val cycleCount: Long = 1L,
    val note: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun elapsedAt(serverAdjustedNowMs: Long): Long {
        return if (active && status == "running" && phaseStartedAtMs > 0L) {
            (accumulatedMs + (serverAdjustedNowMs - phaseStartedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L)
        } else {
            accumulatedMs.coerceAtLeast(0L)
        }
    }

    fun remainingAt(serverAdjustedNowMs: Long): Long {
        if (mode != "pomodoro" || targetMs <= 0L) return 0L
        return (targetMs - elapsedAt(serverAdjustedNowMs)).coerceAtLeast(0L)
    }

    fun toWriteMap(): Map<String, Any> = mapOf(
        "active" to active,
        "subjectId" to subjectId.take(128),
        "mode" to if (mode == "pomodoro") "pomodoro" else "stopwatch",
        "status" to if (status in listOf("idle", "running", "paused")) status else "idle",
        "phase" to if (phase in listOf("focus", "break", "long_break")) phase else "focus",
        "phaseStartedAtMs" to phaseStartedAtMs.coerceAtLeast(0L),
        "accumulatedMs" to accumulatedMs.coerceAtLeast(0L),
        "targetMs" to targetMs.coerceAtLeast(0L),
        "cycleCount" to cycleCount.coerceIn(0L, 100L),
        "note" to note.trim().take(500),
        "deviceId" to deviceId.ifBlank { "android_device" }.take(128),
        "deviceName" to deviceName.take(100),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class StudyGoal(
    @DocumentId val id: String = "",
    val scope: String = "overall", // "overall" or subjectId
    val period: String = "daily",  // "daily" or "weekly"
    val targetMin: Long = 60L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "scope" to scope.ifBlank { "overall" }.take(128),
        "period" to if (period == "weekly") "weekly" else "daily",
        "targetMin" to targetMin.coerceIn(1L, 10080L),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "scope" to scope.ifBlank { "overall" }.take(128),
        "period" to if (period == "weekly") "weekly" else "daily",
        "targetMin" to targetMin.coerceIn(1L, 10080L),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class Exam(
    @DocumentId val id: String = "",
    val title: String = "",
    val subjectId: String = "",
    val date: String = "", // YYYY-MM-DD
    val reminderDays: List<Long> = listOf(7L, 3L, 1L),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "title" to title.trim().take(120),
        "subjectId" to subjectId.take(128),
        "date" to date.trim().take(32),
        "reminderDays" to reminderDays.take(5),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "title" to title.trim().take(120),
        "subjectId" to subjectId.take(128),
        "date" to date.trim().take(32),
        "reminderDays" to reminderDays.take(5),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class LinkedFileEntry(
    @DocumentId val id: String = "",
    val subjectId: String = "",
    val displayName: String = "",
    val mime: String = "",
    val order: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "subjectId" to subjectId,
        "displayName" to displayName.trim().take(200),
        "mime" to mime.trim().take(120),
        "order" to order.coerceIn(0L, 100000L),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "subjectId" to subjectId,
        "displayName" to displayName.trim().take(200),
        "mime" to mime.trim().take(120),
        "order" to order.coerceIn(0L, 100000L),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class BlockRule(
    @DocumentId val id: String = "",
    val platform: String = "android",
    val kind: String = "limit",
    val targetId: String = "",
    val targetLabel: String = "",
    val allowanceMin: Long = 30L,
    val periodType: String = "day", // "hours", "day", "week", "month"
    val periodCount: Long = 1L,
    val lockedUntil: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun lockedUntilMs(): Long = lockedUntil?.toDate()?.time ?: 0L

    fun currentlyLocked(nowMs: Long): Boolean = lockedUntilMs() > nowMs

    fun periodLabel(): String = when (periodType) {
        "hours" -> if (periodCount == 1L) "per hour" else "per ${periodCount}h"
        "day" -> "per day"
        "week" -> "per week"
        "month" -> "per month"
        else -> "per day"
    }

    fun toCreateMap(): Map<String, Any> {
        val base = mutableMapOf<String, Any>(
            "platform" to if (platform == "windows") "windows" else "android",
            "kind" to "limit",
            "targetId" to targetId.trim().take(200),
            "targetLabel" to targetLabel.trim().take(120),
            "allowanceMin" to allowanceMin.coerceIn(1L, 1440L),
            "periodType" to if (periodType in listOf("hours", "day", "week", "month")) periodType else "day",
            "periodCount" to periodCount.coerceIn(1L, 24L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (lockedUntil != null) {
            base["lockedUntil"] = lockedUntil
        }
        return base
    }
}

@Keep
@IgnoreExtraProperties
data class SessionBlocklist(
    val android: List<String> = emptyList(),
    val windows: List<String> = emptyList(),
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toWriteMap(): Map<String, Any> = mapOf(
        "android" to android.distinct().take(200),
        "windows" to windows.distinct().take(200),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@Keep
@IgnoreExtraProperties
data class DeviceRecord(
    @DocumentId val id: String = "",
    val name: String = "",
    val platform: String = "android",
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class DailyStreak(
    val currentStreak: Long = 0L,
    val longestStreak: Long = 0L,
    val lastCompletedDate: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun effectiveStreakCount(todayKey: String, yesterdayKey: String): Long = when {
        lastCompletedDate == todayKey || lastCompletedDate == yesterdayKey -> currentStreak.coerceAtLeast(0L)
        else -> 0L
    }

    fun isCompletedToday(todayKey: String): Boolean =
        lastCompletedDate == todayKey && currentStreak > 0L

    fun toWriteMap(): Map<String, Any> {
        val safeCurrent = currentStreak.coerceIn(0L, 100000L)
        val safeLongest = maxOf(longestStreak.coerceIn(0L, 100000L), safeCurrent)
        return mapOf(
            "currentStreak" to safeCurrent,
            "longestStreak" to safeLongest,
            "lastCompletedDate" to lastCompletedDate.trim().take(16),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
    }

    companion object {
        const val MIN_STREAK_SESSION_MS: Long = 25L * 60_000L
    }
}

enum class LocalFileStatus {
    LINKED,
    NOT_LINKED,
    MISSING,
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
