package com.example.nativeandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.LocalDeviceStore

/**
 * Foreground Timer Service (PDF Section 7.11 & Section 8):
 * - Ongoing notification with live system-rendered chronometer (ticks without waking the app)
 * - setUsesChronometer(true), setWhen(...), setChronometerCountDown(true) for Pomodoro
 * - CATEGORY_STOPWATCH, VISIBILITY_PUBLIC (lock screen), silent channel, subject color
 * - Pause/Resume & Stop actions writing back to shared state + notifying active UI
 * - Android 16 (API 36+) Live Update / Promoted Ongoing support for Samsung One UI Now Bar
 */
class TimerForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        when (intent?.action) {
            ACTION_START_OR_UPDATE -> {
                val localStore = LocalDeviceStore(this)
                val snap = localStore.getTimerSnapshot()
                if (!snap.active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    StudyTimerWidgetProvider.refreshAllWidgets(this)
                    return START_NOT_STICKY
                }
                val notification = buildTimerNotification(snap)
                startForeground(NOTIFICATION_ID, notification)
                StudyTimerWidgetProvider.refreshAllWidgets(this)
            }
            ACTION_PAUSE_OR_RESUME -> {
                val localStore = LocalDeviceStore(this)
                val snap = localStore.getTimerSnapshot()
                if (snap.active) {
                    val nowMs = System.currentTimeMillis() + localStore.getServerTimeOffsetMs()
                    val updated = if (snap.status == "running") {
                        val elapsed = snap.accumulatedMs + (nowMs - snap.phaseStartedAtMs).coerceAtLeast(0L)
                        snap.copy(status = "paused", accumulatedMs = elapsed, phaseStartedAtMs = 0L)
                    } else {
                        snap.copy(status = "running", phaseStartedAtMs = nowMs)
                    }
                    localStore.saveTimerSnapshot(updated)
                    localStore.setPendingTimerCommand(
                        if (updated.status == "paused") CMD_PAUSE else CMD_RESUME
                    )
                    sendBroadcast(Intent(BROADCAST_TIMER_COMMAND).setPackage(packageName))
                    startForeground(NOTIFICATION_ID, buildTimerNotification(updated))
                    StudyTimerWidgetProvider.refreshAllWidgets(this)
                }
            }
            ACTION_STOP -> {
                val localStore = LocalDeviceStore(this)
                localStore.setPendingTimerCommand(CMD_STOP)
                val snap = localStore.getTimerSnapshot()
                localStore.saveTimerSnapshot(snap.copy(active = false, status = "idle"))
                sendBroadcast(Intent(BROADCAST_TIMER_COMMAND).setPackage(packageName))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                StudyTimerWidgetProvider.refreshAllWidgets(this)
            }
            ACTION_DISMISS -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                StudyTimerWidgetProvider.refreshAllWidgets(this)
            }
        }
        return START_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                // Must NOT be IMPORTANCE_MIN for Android 16 Live Updates / Now Bar eligibility
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Active Study Timer",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Live chronometer notification on the lock screen and Now Bar."
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildTimerNotification(snap: LocalDeviceStore.LocalTimerSnapshot): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseResumeIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, TimerForegroundService::class.java).setAction(ACTION_PAUSE_OR_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, TimerForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val isRunning = snap.status == "running"
        val isPomodoro = snap.mode == "pomodoro" && snap.targetMs > 0L
        val phaseSubtitle = when {
            !isRunning -> "Paused"
            snap.mode == "pomodoro" && snap.phase == "focus" -> "Focus ${snap.cycleCount}/${snap.longEvery}"
            snap.mode == "pomodoro" && snap.phase == "long_break" -> "Long Break"
            snap.mode == "pomodoro" && snap.phase == "break" -> "Break"
            else -> "Stopwatch"
        }

        val accentColorInt = try {
            Color.parseColor(snap.subjectColorHex)
        } catch (_: Exception) {
            Color.parseColor("#6C7BFF")
        }

        val nowLocal = System.currentTimeMillis()
        val serverOffset = LocalDeviceStore(this).getServerTimeOffsetMs()
        val nowServer = nowLocal + serverOffset
        val elapsedMs = if (isRunning && snap.phaseStartedAtMs > 0L) {
            snap.accumulatedMs + (nowServer - snap.phaseStartedAtMs).coerceAtLeast(0L)
        } else {
            snap.accumulatedMs
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(snap.subjectName.ifBlank { "Study Session" })
            .setContentText(phaseSubtitle)
            .setSubText(phaseSubtitle)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setColor(accentColorInt)
            .setColorized(true)
            .setContentIntent(openAppIntent)
            .addAction(
                0,
                if (isRunning) "Pause" else "Resume",
                pauseResumeIntent,
            )
            .addAction(
                0,
                "Stop",
                stopIntent,
            )

        if (isRunning) {
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
            if (isPomodoro) {
                val remainingMs = (snap.targetMs - elapsedMs).coerceAtLeast(0L)
                builder.setWhen(nowLocal + remainingMs)
                builder.setChronometerCountDown(true)
            } else {
                builder.setWhen(nowLocal - elapsedMs)
                builder.setChronometerCountDown(false)
            }
        } else {
            builder.setShowWhen(false)
            builder.setUsesChronometer(false)
            val displayMs = if (isPomodoro) (snap.targetMs - elapsedMs).coerceAtLeast(0L) else elapsedMs
            val mins = (displayMs / 60000L)
            val secs = (displayMs % 60000L) / 1000L
            builder.setContentText("Paused · %02d:%02d".format(mins, secs))
        }

        // Android 16 (API 36+) Live Updates / Samsung One UI Now Bar promoted ongoing request
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
            val chipLabel = if (isRunning) {
                if (snap.phase == "focus") "Focus" else "Break"
            } else {
                "Paused"
            }
            builder.setShortCriticalText(chipLabel)
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "study_timer_live_channel"
        const val NOTIFICATION_ID = 4101
        const val ACTION_START_OR_UPDATE = "com.example.timer.ACTION_START_OR_UPDATE"
        const val ACTION_PAUSE_OR_RESUME = "com.example.timer.ACTION_PAUSE_OR_RESUME"
        const val ACTION_STOP = "com.example.timer.ACTION_STOP"
        const val ACTION_DISMISS = "com.example.timer.ACTION_DISMISS"
        const val BROADCAST_TIMER_COMMAND = "com.example.timer.BROADCAST_COMMAND"
        const val CMD_PAUSE = "PAUSE"
        const val CMD_RESUME = "RESUME"
        const val CMD_STOP = "STOP"

        fun syncWithSnapshot(context: Context, active: Boolean) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = if (active) ACTION_START_OR_UPDATE else ACTION_DISMISS
            }
            try {
                if (active) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Ignore background FGS launch restriction if app is completely backgrounded
            }
        }
    }
}
