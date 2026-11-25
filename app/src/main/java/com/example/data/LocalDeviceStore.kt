package com.example.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Device-local only storage. NEVER synced to Firestore:
 * - fileId -> Android SAF content:// URI links
 * - live foreground app usage counters per rule window
 * - monotonic clock tamper guard pairs (wallClockMs vs SystemClock.elapsedRealtime())
 * - cached blocker rules + active session state for offline AccessibilityService & Widget enforcement
 * - onboarding/permission setup state & stable deviceId
 */
class LocalDeviceStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Stable Device Identity ---
    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "android_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun getDeviceFriendlyName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        return when {
            model.isEmpty() -> "Android Phone"
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }.take(60)
    }

    // --- Setup Checklist ---
    fun isSetupCompleted(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    fun setSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    fun isRestrictedSettingsConfirmed(): Boolean = prefs.getBoolean(KEY_RESTRICTED_CONFIRMED, false)
    fun setRestrictedSettingsConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean(KEY_RESTRICTED_CONFIRMED, confirmed).apply()
    }

    // --- Local SAF File URI Links (NEVER synced) ---
    fun saveFileUri(fileId: String, uriString: String) {
        val map = getAllFileUris().toMutableMap()
        map[fileId] = uriString
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_FILE_URIS, obj.toString()).apply()
    }

    fun removeFileUri(fileId: String) {
        val map = getAllFileUris().toMutableMap()
        if (map.remove(fileId) != null) {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            prefs.edit().putString(KEY_FILE_URIS, obj.toString()).apply()
        }
    }

    fun getFileUri(fileId: String): String? = getAllFileUris()[fileId]

    fun getAllFileUris(): Map<String, String> {
        val raw = prefs.getString(KEY_FILE_URIS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.optString(key, "")
            }
            result.filterValues { it.isNotBlank() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun resolveFileStatus(fileId: String): LocalFileStatus {
        val uriStr = getFileUri(fileId) ?: return LocalFileStatus.NOT_LINKED
        return try {
            val uri = Uri.parse(uriStr)
            appContext.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) LocalFileStatus.LINKED else LocalFileStatus.MISSING
            } ?: run {
                // Fallback: try opening file descriptor briefly
                appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    LocalFileStatus.LINKED
                } ?: LocalFileStatus.MISSING
            }
        } catch (_: Exception) {
            LocalFileStatus.MISSING
        }
    }

    // --- Monotonic Clock Tamper Guard ---
    /**
     * Returns a tamper-resistant wall-clock timestamp.
     * Records (wallClockMs, elapsedRealtimeMs) pairs:
     * - If the user moves the phone clock BACKWARD, the guarded clock refuses to go backward,
     *   so backward clock changes can never grant extra time or un-exhaust a limit.
     * - If the user jumps the phone clock FORWARD to try to prematurely end a locked period,
     *   period-expiry checks also verify monotonic elapsed time via getGuardedNowMs().
     */
    @Synchronized
    fun getGuardedNowMs(): Long {
        val sysWall = System.currentTimeMillis()
        val sysMono = SystemClock.elapsedRealtime()
        val lastWall = prefs.getLong(KEY_GUARD_WALL, 0L)
        val lastMono = prefs.getLong(KEY_GUARD_MONO, -1L)

        if (lastWall <= 0L || lastMono < 0L || sysMono < lastMono) {
            // Fresh init or device rebooted (elapsedRealtime reset)
            val safeWall = maxOf(sysWall, lastWall)
            prefs.edit()
                .putLong(KEY_GUARD_WALL, safeWall)
                .putLong(KEY_GUARD_MONO, sysMono)
                .apply()
            return safeWall
        }

        val monoDelta = (sysMono - lastMono).coerceAtLeast(0L)
        val expectedWall = lastWall + monoDelta
        val skew = sysWall - expectedWall

        // Allow small normal NTP / timezone jitter (< 60s), clamp large manual clock jumps
        val guarded = when {
            skew < -60_000L -> {
                // Backward clock tamper detected! Never allow time to move backward.
                expectedWall
            }
            skew > 120_000L -> {
                // Forward clock tamper detected! Advance only by monotonic elapsed delta.
                expectedWall
            }
            else -> sysWall
        }

        if (monoDelta >= 15_000L) {
            prefs.edit()
                .putLong(KEY_GUARD_WALL, guarded)
                .putLong(KEY_GUARD_MONO, sysMono)
                .apply()
        }
        return guarded
    }

    // --- Local Cached Blocker & Active Session Snapshot (for AccessibilityService, FGS, Widget) ---
    data class LocalBlockerRule(
        val id: String,
        val targetId: String,
        val targetLabel: String,
        val allowanceMin: Long,
        val periodType: String,
        val periodCount: Long,
        val lockedUntilMs: Long,
    )

    data class LocalTimerSnapshot(
        val active: Boolean,
        val subjectId: String,
        val subjectName: String,
        val subjectColorHex: String,
        val mode: String,
        val status: String,
        val phase: String,
        val phaseStartedAtMs: Long,
        val accumulatedMs: Long,
        val targetMs: Long,
        val cycleCount: Long,
        val longEvery: Long,
        val blockDuringBreaks: Boolean,
    )

    fun saveTimerSnapshot(snapshot: LocalTimerSnapshot) {
        val obj = JSONObject().apply {
            put("active", snapshot.active)
            put("subjectId", snapshot.subjectId)
            put("subjectName", snapshot.subjectName)
            put("subjectColorHex", snapshot.subjectColorHex)
            put("mode", snapshot.mode)
            put("status", snapshot.status)
            put("phase", snapshot.phase)
            put("phaseStartedAtMs", snapshot.phaseStartedAtMs)
            put("accumulatedMs", snapshot.accumulatedMs)
            put("targetMs", snapshot.targetMs)
            put("cycleCount", snapshot.cycleCount)
            put("longEvery", snapshot.longEvery)
            put("blockDuringBreaks", snapshot.blockDuringBreaks)
        }
        prefs.edit().putString(KEY_TIMER_SNAPSHOT, obj.toString()).apply()
    }

    fun getTimerSnapshot(): LocalTimerSnapshot {
        val raw = prefs.getString(KEY_TIMER_SNAPSHOT, null)
            ?: return LocalTimerSnapshot(
                active = false, subjectId = "", subjectName = "Study Tracker",
                subjectColorHex = "#6C7BFF", mode = "stopwatch", status = "idle",
                phase = "focus", phaseStartedAtMs = 0L, accumulatedMs = 0L,
                targetMs = 0L, cycleCount = 1L, longEvery = 4L, blockDuringBreaks = false,
            )
        return try {
            val o = JSONObject(raw)
            LocalTimerSnapshot(
                active = o.optBoolean("active", false),
                subjectId = o.optString("subjectId", ""),
                subjectName = o.optString("subjectName", "Study Tracker"),
                subjectColorHex = o.optString("subjectColorHex", "#6C7BFF"),
                mode = o.optString("mode", "stopwatch"),
                status = o.optString("status", "idle"),
                phase = o.optString("phase", "focus"),
                phaseStartedAtMs = o.optLong("phaseStartedAtMs", 0L),
                accumulatedMs = o.optLong("accumulatedMs", 0L),
                targetMs = o.optLong("targetMs", 0L),
                cycleCount = o.optLong("cycleCount", 1L),
                longEvery = o.optLong("longEvery", 4L),
                blockDuringBreaks = o.optBoolean("blockDuringBreaks", false),
            )
        } catch (_: Exception) {
            LocalTimerSnapshot(
                active = false, subjectId = "", subjectName = "Study Tracker",
                subjectColorHex = "#6C7BFF", mode = "stopwatch", status = "idle",
                phase = "focus", phaseStartedAtMs = 0L, accumulatedMs = 0L,
                targetMs = 0L, cycleCount = 1L, longEvery = 4L, blockDuringBreaks = false,
            )
        }
    }

    fun saveBlockerConfig(
        sessionPackages: List<String>,
        rules: List<BlockRule>,
        weekStartsOn: String,
        strictLock: Boolean,
    ) {
        val pkgArr = JSONArray()
        sessionPackages.distinct().forEach { pkgArr.put(it) }

        val existingLocalLocks = getLocalLockedUntilMap()
        val rulesArr = JSONArray()
        rules.filter { it.platform == "android" }.forEach { rule ->
            val effectiveLock = maxOf(rule.lockedUntilMs(), existingLocalLocks[rule.id] ?: 0L)
            rulesArr.put(JSONObject().apply {
                put("id", rule.id)
                put("targetId", rule.targetId)
                put("targetLabel", rule.targetLabel)
                put("allowanceMin", rule.allowanceMin)
                put("periodType", rule.periodType)
                put("periodCount", rule.periodCount)
                put("lockedUntilMs", effectiveLock)
            })
        }

        prefs.edit()
            .putString(KEY_SESSION_BLOCKLIST, pkgArr.toString())
            .putString(KEY_BLOCK_RULES, rulesArr.toString())
            .putString(KEY_WEEK_STARTS_ON, weekStartsOn)
            .putBoolean(KEY_STRICT_LOCK, strictLock)
            .apply()
    }

    fun getSessionBlocklistPackages(): Set<String> {
        val raw = prefs.getString(KEY_SESSION_BLOCKLIST, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val pkg = arr.optString(i, "")
                    if (pkg.isNotBlank()) add(pkg)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun getCachedBlockRules(): List<LocalBlockerRule> {
        val raw = prefs.getString(KEY_BLOCK_RULES, null) ?: return emptyList()
        val localLocks = getLocalLockedUntilMap()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id", "")
                    if (id.isNotBlank()) {
                        add(
                            LocalBlockerRule(
                                id = id,
                                targetId = o.optString("targetId", ""),
                                targetLabel = o.optString("targetLabel", ""),
                                allowanceMin = o.optLong("allowanceMin", 30L),
                                periodType = o.optString("periodType", "day"),
                                periodCount = o.optLong("periodCount", 1L),
                                lockedUntilMs = maxOf(o.optLong("lockedUntilMs", 0L), localLocks[id] ?: 0L),
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getWeekStartsOn(): String = prefs.getString(KEY_WEEK_STARTS_ON, "sat") ?: "sat"
    fun isStrictLockEnabled(): Boolean = prefs.getBoolean(KEY_STRICT_LOCK, false)

    // --- Local Lock State & Pending Sync (works offline; syncs lockedUntil when connected) ---
    fun setLocalRuleLockedUntil(ruleId: String, lockedUntilMs: Long) {
        val map = getLocalLockedUntilMap().toMutableMap()
        val current = map[ruleId] ?: 0L
        if (lockedUntilMs > current) {
            map[ruleId] = lockedUntilMs
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            prefs.edit().putString(KEY_LOCAL_LOCKS, obj.toString()).apply()
        }
    }

    fun getLocalLockedUntilMap(): Map<String, Long> {
        val raw = prefs.getString(KEY_LOCAL_LOCKS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.optLong(k, 0L)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // --- Live Foreground App Usage Counters (per ruleId & windowStartMs) ---
    fun addRuleUsageMs(ruleId: String, windowStartMs: Long, deltaMs: Long): Long {
        if (deltaMs <= 0L) return getRuleUsageMs(ruleId, windowStartMs)
        val obj = readUsageJson()
        val entry = obj.optJSONObject(ruleId)
        val storedWindow = entry?.optLong("w", 0L) ?: 0L
        val prevMs = if (storedWindow == windowStartMs) entry?.optLong("u", 0L) ?: 0L else 0L
        val nextMs = prevMs + deltaMs
        obj.put(ruleId, JSONObject().apply {
            put("w", windowStartMs)
            put("u", nextMs)
        })
        prefs.edit().putString(KEY_USAGE_COUNTERS, obj.toString()).apply()
        return nextMs
    }

    fun getRuleUsageMs(ruleId: String, windowStartMs: Long): Long {
        val obj = readUsageJson()
        val entry = obj.optJSONObject(ruleId) ?: return 0L
        return if (entry.optLong("w", 0L) == windowStartMs) entry.optLong("u", 0L) else 0L
    }

    fun getAllCurrentUsageMs(rules: List<BlockRule>, nowMs: Long, weekStartsOn: String): Map<String, Long> {
        val obj = readUsageJson()
        return rules.associate { rule ->
            val bounds = computePeriodBounds(nowMs, rule.periodType, rule.periodCount, weekStartsOn)
            val entry = obj.optJSONObject(rule.id)
            val used = if (entry != null && entry.optLong("w", 0L) == bounds.startMs) {
                entry.optLong("u", 0L)
            } else {
                0L
            }
            rule.id to used
        }
    }

    private fun readUsageJson(): JSONObject {
        val raw = prefs.getString(KEY_USAGE_COUNTERS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    // --- Pending Notification / Widget Actions (written by FGS/Widget, picked up by ViewModel) ---
    fun setPendingTimerCommand(command: String?) {
        if (command == null) {
            prefs.edit().remove(KEY_PENDING_TIMER_CMD).apply()
        } else {
            prefs.edit().putString(KEY_PENDING_TIMER_CMD, command).apply()
        }
    }

    fun consumePendingTimerCommand(): String? {
        val cmd = prefs.getString(KEY_PENDING_TIMER_CMD, null)
        if (cmd != null) {
            prefs.edit().remove(KEY_PENDING_TIMER_CMD).apply()
        }
        return cmd
    }

    // --- Server Clock Offset (measured on Firestore connect) ---
    fun saveServerTimeOffsetMs(offsetMs: Long) {
        prefs.edit().putLong(KEY_SERVER_OFFSET_MS, offsetMs).apply()
    }

    fun getServerTimeOffsetMs(): Long = prefs.getLong(KEY_SERVER_OFFSET_MS, 0L)

    companion object {
        private const val PREFS_NAME = "study_tracker_local_store"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_RESTRICTED_CONFIRMED = "restricted_confirmed"
        private const val KEY_FILE_URIS = "file_uris_json"
        private const val KEY_GUARD_WALL = "clock_guard_wall"
        private const val KEY_GUARD_MONO = "clock_guard_mono"
        private const val KEY_TIMER_SNAPSHOT = "timer_snapshot_json"
        private const val KEY_SESSION_BLOCKLIST = "session_blocklist_json"
        private const val KEY_BLOCK_RULES = "block_rules_json"
        private const val KEY_WEEK_STARTS_ON = "week_starts_on"
        private const val KEY_STRICT_LOCK = "strict_lock"
        private const val KEY_LOCAL_LOCKS = "local_locks_json"
        private const val KEY_USAGE_COUNTERS = "usage_counters_json"
        private const val KEY_PENDING_TIMER_CMD = "pending_timer_cmd"
        private const val KEY_SERVER_OFFSET_MS = "server_offset_ms"
    }
}

data class PeriodBounds(
    val startMs: Long,
    val endMs: Long,
)

/**
 * Computes repeating window boundaries for usage limit rules using local Calendar
 * so DST and timezone transitions are handled cleanly without double-counting.
 */
fun computePeriodBounds(
    nowMs: Long,
    periodType: String,
    periodCount: Long,
    weekStartsOn: String = "sat",
): PeriodBounds {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    return when (periodType) {
        "hours" -> {
            val blockHours = periodCount.toInt().coerceIn(1, 24)
            val currentHour = cal.get(Calendar.HOUR_OF_DAY)
            val blockStartHour = (currentHour / blockHours) * blockHours
            cal.set(Calendar.HOUR_OF_DAY, blockStartHour)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.HOUR_OF_DAY, blockHours)
            val end = cal.timeInMillis
            PeriodBounds(start, end)
        }
        "week" -> {
            val targetDow = when (weekStartsOn) {
                "sun" -> Calendar.SUNDAY
                "mon" -> Calendar.MONDAY
                "tue" -> Calendar.TUESDAY
                "wed" -> Calendar.WEDNESDAY
                "thu" -> Calendar.THURSDAY
                "fri" -> Calendar.FRIDAY
                else -> Calendar.SATURDAY
            }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val diff = (cal.get(Calendar.DAY_OF_WEEK) - targetDow + 7) % 7
            cal.add(Calendar.DAY_OF_YEAR, -diff)
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 7)
            val end = cal.timeInMillis
            PeriodBounds(start, end)
        }
        "month" -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val end = cal.timeInMillis
            PeriodBounds(start, end)
        }
        else -> {
            // "day": midnight to midnight
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis
            PeriodBounds(start, end)
        }
    }
}
