package com.example.nativeandroid

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.Exam
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ReminderScheduler {

    const val REMINDER_CHANNEL_ID = "study_reminders_channel"

    fun ensureReminderChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(REMINDER_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    "Exam & Study Reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Exam countdown alerts, daily study reminders, and permission status alerts."
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun scheduleExamReminders(context: Context, exams: List<Exam>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val nowMs = System.currentTimeMillis()

        for (exam in exams) {
            val examDate = try {
                sdf.parse(exam.date)
            } catch (_: Exception) {
                null
            } ?: continue

            for (daysBefore in exam.reminderDays) {
                val cal = Calendar.getInstance().apply {
                    time = examDate
                    add(Calendar.DAY_OF_YEAR, -daysBefore.toInt())
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val triggerMs = cal.timeInMillis
                if (triggerMs <= nowMs) continue

                val requestCode = ("exam_${exam.id}_$daysBefore").hashCode()
                val intent = Intent(context, ReminderReceiver::class.java).apply {
                    putExtra(ReminderReceiver.EXTRA_TITLE, "${exam.title} in $daysBefore day${if (daysBefore == 1L) "" else "s"}")
                    putExtra(ReminderReceiver.EXTRA_BODY, "Exam date: ${exam.date}. Stay on track with your study plan.")
                    putExtra(ReminderReceiver.EXTRA_NOTIF_ID, requestCode)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                scheduleExactSafe(am, triggerMs, pi)
            }
        }
    }

    fun scheduleDailyStudyReminder(context: Context, hour: Int, minute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val requestCode = 9001
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, "Time for your daily study goal")
            putExtra(ReminderReceiver.EXTRA_BODY, "Start a focus session to protect your study streak.")
            putExtra(ReminderReceiver.EXTRA_NOTIF_ID, requestCode)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (hour < 0 || hour > 23) {
            am.cancel(pi)
            return
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        scheduleExactSafe(am, cal.timeInMillis, pi)
    }

    fun triggerPhaseOrGoalAlert(context: Context, title: String, body: String, sound: Boolean, vibrate: Boolean) {
        ensureReminderChannel(context)
        if (sound) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(context, uri)?.play()
            } catch (_: Exception) {
            }
        }
        if (vibrate) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(220L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(220L)
                }
            } catch (_: Exception) {
            }
        }
        postSimpleNotification(context, title, body, (System.currentTimeMillis() % 100000).toInt() + 5000)
    }

    fun postSimpleNotification(context: Context, title: String, body: String, notifId: Int) {
        ensureReminderChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId, notif)
        } catch (_: Exception) {
        }
    }

    private fun scheduleExactSafe(am: AlarmManager, triggerAtMs: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }
}
