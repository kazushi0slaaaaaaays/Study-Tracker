package com.example.nativeandroid

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.LocalDeviceStore

/**
 * 2x2 Home-Screen Widget (PDF Section 7.11):
 * Displays subject name, big live chronometer/time, phase subtitle, and a single Start/Pause button
 * backed by the exact same LocalDeviceStore snapshot as the lock-screen notification.
 */
class StudyTimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE_TIMER) {
            val localStore = LocalDeviceStore(context)
            val snap = localStore.getTimerSnapshot()
            if (snap.active) {
                val svcIntent = Intent(context, TimerForegroundService::class.java).apply {
                    action = TimerForegroundService.ACTION_PAUSE_OR_RESUME
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                } catch (_: Exception) {
                }
            } else {
                val openApp = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(openApp)
            }
            refreshAllWidgets(context)
        }
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE_TIMER = "com.example.ACTION_WIDGET_TOGGLE_TIMER"

        fun refreshAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, StudyTimerWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, mgr, id)
            }
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val localStore = LocalDeviceStore(context)
            val snap = localStore.getTimerSnapshot()
            val views = RemoteViews(context.packageName, R.layout.widget_study_timer)

            val openIntent = PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)

            val toggleIntent = PendingIntent.getBroadcast(
                context,
                widgetId + 2000,
                Intent(context, StudyTimerWidgetProvider::class.java).setAction(ACTION_WIDGET_TOGGLE_TIMER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_action_btn, toggleIntent)

            views.setTextViewText(R.id.widget_subject, snap.subjectName.ifBlank { "Study Tracker" })

            val isRunning = snap.active && snap.status == "running"
            val isPomodoro = snap.mode == "pomodoro" && snap.targetMs > 0L

            if (isRunning) {
                val nowServer = System.currentTimeMillis() + localStore.getServerTimeOffsetMs()
                val elapsedMs = snap.accumulatedMs +
                    if (snap.phaseStartedAtMs > 0L) (nowServer - snap.phaseStartedAtMs).coerceAtLeast(0L) else 0L
                val nowRealtime = SystemClock.elapsedRealtime()
                val baseRealtime = if (isPomodoro) {
                    val remaining = (snap.targetMs - elapsedMs).coerceAtLeast(0L)
                    nowRealtime + remaining
                } else {
                    nowRealtime - elapsedMs
                }
                views.setViewVisibility(R.id.widget_chronometer, View.VISIBLE)
                views.setViewVisibility(R.id.widget_static_time, View.GONE)
                views.setChronometer(R.id.widget_chronometer, baseRealtime, null, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    views.setChronometerCountDown(R.id.widget_chronometer, isPomodoro)
                }
                val phaseText = if (isPomodoro) {
                    when (snap.phase) {
                        "focus" -> "Focus ${snap.cycleCount}/${snap.longEvery}"
                        "long_break" -> "Long Break"
                        else -> "Break"
                    }
                } else "Stopwatch"
                views.setTextViewText(R.id.widget_phase, phaseText)
                views.setTextViewText(R.id.widget_action_btn, "Pause")
            } else if (snap.active) {
                val displayMs = if (isPomodoro) (snap.targetMs - snap.accumulatedMs).coerceAtLeast(0L) else snap.accumulatedMs
                val totalSec = displayMs / 1000L
                val hrs = totalSec / 3600L
                val mins = (totalSec % 3600L) / 60L
                val secs = totalSec % 60L
                val formatted = if (hrs > 0) "%d:%02d:%02d".format(hrs, mins, secs) else "%02d:%02d".format(mins, secs)
                views.setViewVisibility(R.id.widget_chronometer, View.GONE)
                views.setViewVisibility(R.id.widget_static_time, View.VISIBLE)
                views.setTextViewText(R.id.widget_static_time, formatted)
                views.setTextViewText(R.id.widget_phase, "Paused")
                views.setTextViewText(R.id.widget_action_btn, "Resume")
            } else {
                views.setViewVisibility(R.id.widget_chronometer, View.GONE)
                views.setViewVisibility(R.id.widget_static_time, View.VISIBLE)
                views.setTextViewText(R.id.widget_static_time, "00:00")
                views.setTextViewText(R.id.widget_phase, "Ready to focus")
                views.setTextViewText(R.id.widget_action_btn, "Start")
            }

            mgr.updateAppWidget(widgetId, views)
        }
    }
}
