package com.example.nativeandroid

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.LocalDeviceStore
import com.example.data.computePeriodBounds

/**
 * Local offline App Blocker & Live Usage Accounting Service (PDF Sections 5G & 8):
 * - Tracks foreground package from TYPE_WINDOW_STATE_CHANGED
 * - Counts foreground time per package live in-service (persisted every 3s)
 * - Uses LocalDeviceStore.getGuardedNowMs() monotonic clock guard
 * - Enforces Mode 1 (Study-session blocking) and Mode 2 (Usage limits + lockedUntil)
 * - Sends user HOME first via GLOBAL_ACTION_HOME, then launches BlockedActivity
 * - Anti-bypass hardening: while a session is running or any rule is locked, blocks
 *   this app's App Info, Accessibility settings, Device Admin settings, and Uninstall dialogs
 *   (including Samsung One UI package/class variants)
 */
class StudyBlockerAccessibilityService : AccessibilityService() {

    private lateinit var localStore: LocalDeviceStore
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentForegroundPkg: String = ""

    @Volatile
    private var currentForegroundClass: String = ""

    private var lastTickMonoMs: Long = 0L
    private var pendingUsageMsByRule = mutableMapOf<String, Long>()
    private var lastPersistMonoMs: Long = 0L
    private var lastBlockTriggerMonoMs: Long = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                onSecondTick()
            } finally {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        localStore = LocalDeviceStore(this)
        lastTickMonoMs = SystemClock.elapsedRealtime()
        lastPersistMonoMs = lastTickMonoMs
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        val cls = event.className?.toString().orEmpty()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Flush accumulated usage for previous foreground app before switching
            flushPendingUsage()
            currentForegroundPkg = pkg
            currentForegroundClass = cls
            lastTickMonoMs = SystemClock.elapsedRealtime()
            evaluateAndEnforce(pkg, cls)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (isProtectionEngaged() && isPotentialTamperPackage(pkg)) {
                if (isTamperScreenVisible(pkg, cls)) {
                    triggerBlock(
                        appName = "System Settings",
                        reason = "Protection is engaged while a session or usage lock is active.",
                        unlockAtMs = 0L,
                    )
                }
            }
        }
    }

    override fun onInterrupt() {
        flushPendingUsage()
    }

    override fun onDestroy() {
        flushPendingUsage()
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    private fun onSecondTick() {
        if (!::localStore.isInitialized) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = pm?.isInteractive ?: true
        val nowMono = SystemClock.elapsedRealtime()
        val delta = (nowMono - lastTickMonoMs).coerceIn(0L, 5000L)
        lastTickMonoMs = nowMono

        if (!screenOn || currentForegroundPkg.isBlank() || currentForegroundPkg == packageName) {
            return
        }

        val nowGuarded = localStore.getGuardedNowMs()
        val weekStart = localStore.getWeekStartsOn()
        val rules = localStore.getCachedBlockRules()

        for (rule in rules) {
            if (rule.targetId == currentForegroundPkg) {
                val bounds = computePeriodBounds(nowGuarded, rule.periodType, rule.periodCount, weekStart)
                val prevPending = pendingUsageMsByRule[rule.id] ?: 0L
                pendingUsageMsByRule[rule.id] = prevPending + delta
                val stored = localStore.getRuleUsageMs(rule.id, bounds.startMs)
                val totalUsed = stored + prevPending + delta
                val allowanceMs = rule.allowanceMin * 60_000L
                if (totalUsed >= allowanceMs) {
                    flushPendingUsage()
                    localStore.setLocalRuleLockedUntil(rule.id, bounds.endMs)
                    sendBroadcast(Intent(BROADCAST_RULE_LOCKED).setPackage(packageName))
                    triggerBlock(
                        appName = rule.targetLabel.ifBlank { currentForegroundPkg },
                        reason = "${periodPrefix(rule.periodType)} limit reached, ${rule.allowanceMin} of ${rule.allowanceMin} min used",
                        unlockAtMs = bounds.endMs,
                    )
                    return
                }
            }
        }

        if (nowMono - lastPersistMonoMs >= 3000L) {
            flushPendingUsage()
            lastPersistMonoMs = nowMono
        }

        evaluateAndEnforce(currentForegroundPkg, currentForegroundClass)
    }

    private fun flushPendingUsage() {
        if (!::localStore.isInitialized || pendingUsageMsByRule.isEmpty()) return
        val nowGuarded = localStore.getGuardedNowMs()
        val weekStart = localStore.getWeekStartsOn()
        val rulesById = localStore.getCachedBlockRules().associateBy { it.id }
        val snapshot = pendingUsageMsByRule.toMap()
        pendingUsageMsByRule.clear()
        for ((ruleId, deltaMs) in snapshot) {
            val rule = rulesById[ruleId] ?: continue
            val bounds = computePeriodBounds(nowGuarded, rule.periodType, rule.periodCount, weekStart)
            localStore.addRuleUsageMs(ruleId, bounds.startMs, deltaMs)
        }
    }

    private fun evaluateAndEnforce(pkg: String, cls: String) {
        if (!::localStore.isInitialized || pkg.isBlank() || pkg == packageName) return
        val nowGuarded = localStore.getGuardedNowMs()

        // 1. Anti-bypass hardening for system tamper screens while protection is engaged
        if (isProtectionEngaged() && isPotentialTamperPackage(pkg) && isTamperScreenVisible(pkg, cls)) {
            triggerBlock(
                appName = "Settings protection",
                reason = "Cannot disable Study Tracker while a session or usage lock is active.",
                unlockAtMs = 0L,
            )
            return
        }

        // 2. Mode 1: Study-session blocking
        val timer = localStore.getTimerSnapshot()
        val sessionBlockingNow = timer.active && timer.status == "running" &&
            (timer.phase == "focus" || timer.blockDuringBreaks)
        if (sessionBlockingNow) {
            val blockedPackages = localStore.getSessionBlocklistPackages()
            if (pkg in blockedPackages) {
                val nowServer = System.currentTimeMillis() + localStore.getServerTimeOffsetMs()
                val elapsedMs = timer.accumulatedMs +
                    if (timer.phaseStartedAtMs > 0L) (nowServer - timer.phaseStartedAtMs).coerceAtLeast(0L) else 0L
                val appLabel = InstalledAppsHelper.resolveAppInfo(this, pkg).label
                triggerBlock(
                    appName = appLabel,
                    reason = "Study session running, ${formatMmSs(elapsedMs)} elapsed",
                    unlockAtMs = 0L,
                )
                return
            }
        }

        // 3. Mode 2: Usage limits
        val weekStart = localStore.getWeekStartsOn()
        val rules = localStore.getCachedBlockRules()
        for (rule in rules) {
            if (rule.targetId == pkg) {
                val bounds = computePeriodBounds(nowGuarded, rule.periodType, rule.periodCount, weekStart)
                val usedMs = localStore.getRuleUsageMs(rule.id, bounds.startMs) +
                    (pendingUsageMsByRule[rule.id] ?: 0L)
                val allowanceMs = rule.allowanceMin * 60_000L
                val isLockedNow = rule.lockedUntilMs > nowGuarded
                if (usedMs >= allowanceMs || isLockedNow) {
                    val unlockAt = maxOf(rule.lockedUntilMs, bounds.endMs)
                    if (unlockAt > rule.lockedUntilMs) {
                        localStore.setLocalRuleLockedUntil(rule.id, unlockAt)
                        sendBroadcast(Intent(BROADCAST_RULE_LOCKED).setPackage(packageName))
                    }
                    triggerBlock(
                        appName = rule.targetLabel.ifBlank { InstalledAppsHelper.resolveAppInfo(this, pkg).label },
                        reason = "${periodPrefix(rule.periodType)} limit reached, ${rule.allowanceMin} of ${rule.allowanceMin} min used",
                        unlockAtMs = unlockAt,
                    )
                    return
                }
            }
        }
    }

    private fun isProtectionEngaged(): Boolean {
        val nowGuarded = localStore.getGuardedNowMs()
        val timer = localStore.getTimerSnapshot()
        if (timer.active && timer.status == "running") return true
        val rules = localStore.getCachedBlockRules()
        return rules.any { it.lockedUntilMs > nowGuarded }
    }

    private fun isPotentialTamperPackage(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
            pkg == "com.samsung.accessibility" ||
            pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.packageinstaller" ||
            pkg == "com.samsung.android.packageinstaller"
    }

    private fun isTamperScreenVisible(pkg: String, cls: String): Boolean {
        val clsLower = cls.lowercase()
        val root: AccessibilityNodeInfo? = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        val mentionsStudyTracker = root != null && containsText(root, "Study Tracker", 0)
        if (!mentionsStudyTracker) return false

        // Block App Info, Accessibility service toggle, Device Admin page, and Uninstall confirmation
        if (pkg.contains("packageinstaller")) return true
        if (pkg == "com.samsung.accessibility") return true
        if (clsLower.contains("installedappdetails") ||
            clsLower.contains("accessibility") ||
            clsLower.contains("deviceadmin") ||
            clsLower.contains("uninstall") ||
            clsLower.contains("subsettings") ||
            clsLower.contains("spaactivity")
        ) {
            return true
        }
        return containsAnyKeyword(
            root,
            listOf("uninstall", "force stop", "deactivate", "accessibility", "device admin"),
            0,
        )
    }

    private fun containsText(node: AccessibilityNodeInfo?, query: String, depth: Int): Boolean {
        if (node == null || depth > 8) return false
        val txt = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (txt.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsText(child, query, depth + 1)
            if (found) return true
        }
        return false
    }

    private fun containsAnyKeyword(node: AccessibilityNodeInfo?, keywords: List<String>, depth: Int): Boolean {
        if (node == null || depth > 8) return false
        val txt = node.text?.toString().orEmpty().lowercase()
        if (keywords.any { txt.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsAnyKeyword(child, keywords, depth + 1)) return true
        }
        return false
    }

    private fun triggerBlock(appName: String, reason: String, unlockAtMs: Long) {
        val nowMono = SystemClock.elapsedRealtime()
        if (nowMono - lastBlockTriggerMonoMs < 900L) return
        lastBlockTriggerMonoMs = nowMono

        // Send blocked app back to Home first so it never gets a visible frame
        performGlobalAction(GLOBAL_ACTION_HOME)

        val overlayIntent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockedActivity.EXTRA_APP_NAME, appName)
            putExtra(BlockedActivity.EXTRA_REASON, reason)
            putExtra(BlockedActivity.EXTRA_UNLOCK_AT_MS, unlockAtMs)
        }
        handler.postDelayed({
            try {
                startActivity(overlayIntent)
            } catch (_: Exception) {
                // Ignore if system blocked launch
            }
        }, 120L)
    }

    private fun periodPrefix(periodType: String): String = when (periodType) {
        "hours" -> "Hourly"
        "week" -> "Weekly"
        "month" -> "Monthly"
        else -> "Daily"
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs) else "%02d:%02d".format(mins, secs)
    }

    companion object {
        const val BROADCAST_RULE_LOCKED = "com.example.blocker.BROADCAST_RULE_LOCKED"
    }
}
