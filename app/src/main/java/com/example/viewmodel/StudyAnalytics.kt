package com.example.viewmodel

import com.example.data.StudySession
import com.example.data.Subject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

enum class StatsRange(val label: String) {
    TODAY("Today"),
    WEEK("Week"),
    MONTH("Month"),
    ALL("All"),
}

data class DailyBarPoint(
    val dayKey: String,
    val label: String,
    val minutes: Float,
)

data class SubjectBreakdownItem(
    val subjectId: String,
    val name: String,
    val emoji: String,
    val colorHex: String,
    val durationMs: Long,
    val percent: Float,
)

data class HeatmapDayCell(
    val dayKey: String,
    val minutes: Int,
    val level: Int, // 0..4
    val monthLabel: String?,
)

data class StreakInfo(
    val currentStreak: Int,
    val bestStreak: Int,
    val last14Days: List<Pair<String, Boolean>>, // dayLabel -> reached goal
)

data class StatsSnapshot(
    val totalMs: Long,
    val dailyAverageMs: Long,
    val sessionCount: Int,
    val bestDayMs: Long,
    val bars: List<DailyBarPoint>,
    val subjectBreakdown: List<SubjectBreakdownItem>,
    val heatmapWeeks: List<List<HeatmapDayCell>>, // 20 weeks x 7 days
    val streak: StreakInfo,
)

object StudyAnalytics {
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val shortDayFormat = SimpleDateFormat("EEE", Locale.US)
    private val dayNumFormat = SimpleDateFormat("d", Locale.US)
    private val monthShortFormat = SimpleDateFormat("MMM", Locale.US)

    fun dayKeyOf(epochMs: Long): String = synchronized(dayKeyFormat) {
        dayKeyFormat.format(Date(epochMs))
    }

    fun startOfTodayMs(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun startOfWeekMs(nowMs: Long = System.currentTimeMillis(), weekStartsOn: String = "sat"): Long {
        val targetDow = when (weekStartsOn) {
            "sun" -> Calendar.SUNDAY
            "mon" -> Calendar.MONDAY
            "tue" -> Calendar.TUESDAY
            "wed" -> Calendar.WEDNESDAY
            "thu" -> Calendar.THURSDAY
            "fri" -> Calendar.FRIDAY
            else -> Calendar.SATURDAY
        }
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val diff = (get(Calendar.DAY_OF_WEEK) - targetDow + 7) % 7
            add(Calendar.DAY_OF_YEAR, -diff)
        }
        return cal.timeInMillis
    }

    fun totalStudyMsInRange(
        sessions: List<StudySession>,
        fromMs: Long,
        toMs: Long = Long.MAX_VALUE,
        subjectId: String? = null,
    ): Long {
        return sessions.asSequence()
            .filter { it.startAtMs in fromMs until toMs }
            .filter { subjectId == null || it.subjectId == subjectId }
            .sumOf { it.durationMs }
    }

    fun daysUntilExam(examDateStr: String, nowMs: Long = System.currentTimeMillis()): Int {
        val target = try {
            synchronized(dayKeyFormat) { dayKeyFormat.parse(examDateStr) }
        } catch (_: Exception) {
            null
        } ?: return 0
        val todayStart = startOfTodayMs(nowMs)
        val diffMs = target.time - todayStart
        return ceil(diffMs / 86_400_000.0).toInt()
    }

    fun computeStreak(
        sessions: List<StudySession>,
        streakMinMinutes: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): StreakInfo {
        val thresholdMs = (streakMinMinutes.coerceAtLeast(1L)) * 60_000L
        val byDay = sessions.groupBy { dayKeyOf(it.startAtMs) }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } }

        // Last 14 days mini calendar
        val last14 = mutableListOf<Pair<String, Boolean>>()
        val cal = Calendar.getInstance().apply { timeInMillis = startOfTodayMs(nowMs) }
        cal.add(Calendar.DAY_OF_YEAR, -13)
        for (i in 0 until 14) {
            val key = dayKeyOf(cal.timeInMillis)
            val label = synchronized(dayNumFormat) { dayNumFormat.format(cal.time) }
            val hit = (byDay[key] ?: 0L) >= thresholdMs
            last14.add(label to hit)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Current streak (counting back from today, or yesterday if today not yet hit)
        var currentStreak = 0
        val checkCal = Calendar.getInstance().apply { timeInMillis = startOfTodayMs(nowMs) }
        val todayKey = dayKeyOf(checkCal.timeInMillis)
        if ((byDay[todayKey] ?: 0L) < thresholdMs) {
            checkCal.add(Calendar.DAY_OF_YEAR, -1)
        }
        while (true) {
            val k = dayKeyOf(checkCal.timeInMillis)
            if ((byDay[k] ?: 0L) >= thresholdMs) {
                currentStreak++
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }

        // Best streak across all recorded days
        var bestStreak = currentStreak
        if (byDay.isNotEmpty()) {
            val qualifyingDays = byDay.filterValues { it >= thresholdMs }.keys.sorted()
            var run = 0
            var prevDayMs = Long.MIN_VALUE
            for (dayStr in qualifyingDays) {
                val parsedMs = try {
                    synchronized(dayKeyFormat) { dayKeyFormat.parse(dayStr)?.time } ?: continue
                } catch (_: Exception) {
                    continue
                }
                if (prevDayMs != Long.MIN_VALUE && (parsedMs - prevDayMs) in 75_000_000L..98_000_000L) {
                    run++
                } else {
                    run = 1
                }
                if (run > bestStreak) bestStreak = run
                prevDayMs = parsedMs
            }
        }

        return StreakInfo(
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            last14Days = last14,
        )
    }

    fun computeStats(
        sessions: List<StudySession>,
        subjects: List<Subject>,
        range: StatsRange,
        weekStartsOn: String,
        streakMinMinutes: Long,
        dailyGoalMin: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): StatsSnapshot {
        val todayStart = startOfTodayMs(nowMs)
        val weekStart = startOfWeekMs(nowMs, weekStartsOn)
        val monthStart = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -29)
        }.timeInMillis

        val filtered = when (range) {
            StatsRange.TODAY -> sessions.filter { it.startAtMs >= todayStart }
            StatsRange.WEEK -> sessions.filter { it.startAtMs >= weekStart }
            StatsRange.MONTH -> sessions.filter { it.startAtMs >= monthStart }
            StatsRange.ALL -> sessions
        }

        val totalMs = filtered.sumOf { it.durationMs }
        val byDayFiltered = filtered.groupBy { dayKeyOf(it.startAtMs) }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } }

        val daysInRange = when (range) {
            StatsRange.TODAY -> 1
            StatsRange.WEEK -> (((todayStart - weekStart) / 86_400_000L).toInt() + 1).coerceIn(1, 7)
            StatsRange.MONTH -> 30
            StatsRange.ALL -> {
                val earliest = filtered.minOfOrNull { it.startAtMs } ?: todayStart
                (((todayStart - startOfTodayMs(earliest)) / 86_400_000L).toInt() + 1).coerceAtLeast(1)
            }
        }
        val dailyAvgMs = if (daysInRange > 0) totalMs / daysInRange else 0L
        val bestDayMs = byDayFiltered.values.maxOrNull() ?: 0L

        // Bar chart points
        val bars = when (range) {
            StatsRange.TODAY -> {
                // 7-day context ending today so bar chart is always meaningful
                buildDailyBars(sessions, 7, todayStart)
            }
            StatsRange.WEEK -> {
                buildDailyBarsFromStart(sessions, 7, weekStart)
            }
            StatsRange.MONTH -> {
                buildDailyBars(sessions, 14, todayStart)
            }
            StatsRange.ALL -> {
                buildDailyBars(sessions, 14, todayStart)
            }
        }

        // Subject donut breakdown
        val subjectMap = subjects.associateBy { it.id }
        val bySubject = filtered.groupBy { it.subjectId }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } }
            .entries
            .sortedByDescending { it.value }
            .map { (subId, dur) ->
                val sub = subjectMap[subId]
                SubjectBreakdownItem(
                    subjectId = subId,
                    name = sub?.name ?: "Other",
                    emoji = sub?.emoji ?: "📘",
                    colorHex = sub?.colorHex ?: "#6C7BFF",
                    durationMs = dur,
                    percent = if (totalMs > 0L) (dur.toFloat() / totalMs.toFloat()) else 0f,
                )
            }

        // 20-week x 7-day heatmap
        val byDayAll = sessions.groupBy { dayKeyOf(it.startAtMs) }
            .mapValues { (_, list) -> (list.sumOf { it.durationMs } / 60_000L).toInt() }
        val goalMin = dailyGoalMin.coerceAtLeast(15L).toInt()
        val heatmapWeeks = mutableListOf<List<HeatmapDayCell>>()
        val heatCal = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -(20 * 7 - 1))
        }
        var lastMonth = -1
        for (w in 0 until 20) {
            val weekCells = mutableListOf<HeatmapDayCell>()
            for (d in 0 until 7) {
                val key = dayKeyOf(heatCal.timeInMillis)
                val mins = byDayAll[key] ?: 0
                val level = when {
                    mins <= 0 -> 0
                    mins < goalMin / 3 -> 1
                    mins < (goalMin * 2) / 3 -> 2
                    mins < goalMin -> 3
                    else -> 4
                }
                val m = heatCal.get(Calendar.MONTH)
                val monthLbl = if (d == 0 && m != lastMonth) {
                    lastMonth = m
                    synchronized(monthShortFormat) { monthShortFormat.format(heatCal.time) }
                } else null
                weekCells.add(HeatmapDayCell(key, mins, level, monthLbl))
                heatCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            heatmapWeeks.add(weekCells)
        }

        return StatsSnapshot(
            totalMs = totalMs,
            dailyAverageMs = dailyAvgMs,
            sessionCount = filtered.size,
            bestDayMs = bestDayMs,
            bars = bars,
            subjectBreakdown = bySubject,
            heatmapWeeks = heatmapWeeks,
            streak = computeStreak(sessions, streakMinMinutes, nowMs),
        )
    }

    private fun buildDailyBars(sessions: List<StudySession>, days: Int, todayStart: Long): List<DailyBarPoint> {
        val byDay = sessions.groupBy { dayKeyOf(it.startAtMs) }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } / 60_000f }
        val cal = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -(days - 1))
        }
        return (0 until days).map {
            val key = dayKeyOf(cal.timeInMillis)
            val label = if (days <= 7) {
                synchronized(shortDayFormat) { shortDayFormat.format(cal.time).take(2) }
            } else {
                synchronized(dayNumFormat) { dayNumFormat.format(cal.time) }
            }
            val pt = DailyBarPoint(key, label, byDay[key] ?: 0f)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            pt
        }
    }

    private fun buildDailyBarsFromStart(sessions: List<StudySession>, days: Int, startMs: Long): List<DailyBarPoint> {
        val byDay = sessions.groupBy { dayKeyOf(it.startAtMs) }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } / 60_000f }
        val cal = Calendar.getInstance().apply { timeInMillis = startMs }
        return (0 until days).map {
            val key = dayKeyOf(cal.timeInMillis)
            val label = synchronized(shortDayFormat) { shortDayFormat.format(cal.time).take(2) }
            val pt = DailyBarPoint(key, label, byDay[key] ?: 0f)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            pt
        }
    }

    fun formatDurationHuman(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMin / 60L
        val mins = totalMin % 60L
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h 0m"
            else -> "${mins}m"
        }
    }

    fun formatTimerParts(ms: Long): Triple<String, String, Boolean> {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            Triple("%d:%02d:".format(hours, mins), "%02d".format(secs), true)
        } else {
            Triple("%02d:".format(mins), "%02d".format(secs), false)
        }
    }

    fun formatCountdownMs(remainingMs: Long): String {
        val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val mins = (totalSec % 3600L) / 60L
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }
}
