package com.example.nativeandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Study Tracker Reminder"
        val body = intent.getStringExtra(EXTRA_BODY) ?: "Keep your focus streak going."
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 7001)
        ReminderScheduler.postSimpleNotification(context, title, body, notifId)
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
    }
}
