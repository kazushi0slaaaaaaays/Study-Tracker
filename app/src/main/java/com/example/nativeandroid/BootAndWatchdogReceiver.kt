package com.example.nativeandroid

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.data.LocalDeviceStore

class BootAndWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val localStore = LocalDeviceStore(context)
        // Re-anchor monotonic clock guard after reboot
        localStore.getGuardedNowMs()
        // Restart live timer notification if a session was running
        val snap = localStore.getTimerSnapshot()
        if (snap.active) {
            TimerForegroundService.syncWithSnapshot(context, true)
        }
        StudyWatchdogJobService.schedulePeriodic(context)
        StudyTimerWidgetProvider.refreshAllWidgets(context)
    }
}

/**
 * Periodic watchdog (via Android JobScheduler) that checks whether required blocker permissions
 * were revoked and ensures an active study timer notification stays alive.
 */
class StudyWatchdogJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val localStore = LocalDeviceStore(this)
        val snap = localStore.getTimerSnapshot()
        if (snap.active) {
            TimerForegroundService.syncWithSnapshot(this, true)
        }
        if (localStore.isSetupCompleted() && PermissionHelper.hasMissingBlockerPermissions(this, localStore)) {
            ReminderScheduler.postSimpleNotification(
                context = this,
                title = "App blocker permission revoked",
                body = "One or more Study Tracker blocker permissions were turned off. Open Setup Checklist to restore protection.",
                notifId = 8802,
            )
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 7301

        fun schedulePeriodic(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val comp = ComponentName(context, StudyWatchdogJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, comp)
                .setPeriodic(15 * 60 * 1000L) // Minimum 15 min interval on Android
                .setPersisted(true)
                .build()
            try {
                js.schedule(info)
            } catch (_: Exception) {
            }
        }
    }
}
