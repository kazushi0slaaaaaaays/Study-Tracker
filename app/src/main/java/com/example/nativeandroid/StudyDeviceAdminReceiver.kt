package com.example.nativeandroid

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class StudyDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling Device Admin allows Study Tracker to be uninstalled while focus protection is active."
    }
}
