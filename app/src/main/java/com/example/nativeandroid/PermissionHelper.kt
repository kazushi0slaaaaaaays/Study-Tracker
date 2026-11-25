package com.example.nativeandroid

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.LocalDeviceStore

enum class PermissionStatusLevel {
    GREEN,
    AMBER,
    RED,
}

data class SetupPermissionItem(
    val id: String,
    val title: String,
    val reason: String,
    val status: PermissionStatusLevel,
    val requiredForBlocking: Boolean,
)

object PermissionHelper {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedComponent = ComponentName(context, StudyBlockerAccessibilityService::class.java)
        if (enabledServices.any { info ->
                val svc = info.resolveInfo?.serviceInfo ?: return@any false
                svc.packageName == expectedComponent.packageName && svc.name == expectedComponent.className
            }
        ) {
            return true
        }
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return flat.contains(expectedComponent.flattenToString(), ignoreCase = true) ||
            flat.contains(expectedComponent.flattenToShortString(), ignoreCase = true)
    }

    fun isOverlayEnabled(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isUsageAccessEnabled(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val comp = ComponentName(context, StudyDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(comp)
    }

    fun areNotificationsAllowed(context: Context): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun evaluateAll(context: Context, localStore: LocalDeviceStore): List<SetupPermissionItem> {
        val acc = isAccessibilityEnabled(context)
        val overlay = isOverlayEnabled(context)
        val usage = isUsageAccessEnabled(context)
        val admin = isDeviceAdminActive(context)
        val notif = areNotificationsAllowed(context)
        val battery = isBatteryUnrestricted(context)
        val restrictedOk = acc || localStore.isRestrictedSettingsConfirmed()

        return listOf(
            SetupPermissionItem(
                id = "restricted_settings",
                title = "Allow restricted settings",
                reason = "Required on Android 13+ for sideloaded APKs before Accessibility can be turned on (App info -> ⋮ menu).",
                status = if (restrictedOk) PermissionStatusLevel.GREEN else PermissionStatusLevel.AMBER,
                requiredForBlocking = false,
            ),
            SetupPermissionItem(
                id = "accessibility",
                title = "Accessibility service",
                reason = "Detects the active foreground app locally to enforce session blocking and usage limits.",
                status = if (acc) PermissionStatusLevel.GREEN else PermissionStatusLevel.RED,
                requiredForBlocking = true,
            ),
            SetupPermissionItem(
                id = "overlay",
                title = "Display over other apps",
                reason = "Shows the full-screen blocked overlay cleanly without letting the blocked app flash.",
                status = if (overlay) PermissionStatusLevel.GREEN else PermissionStatusLevel.RED,
                requiredForBlocking = true,
            ),
            SetupPermissionItem(
                id = "usage_access",
                title = "Usage access",
                reason = "Cross-checks foreground usage accounting across screen locks and reboots.",
                status = if (usage) PermissionStatusLevel.GREEN else PermissionStatusLevel.RED,
                requiredForBlocking = true,
            ),
            SetupPermissionItem(
                id = "device_admin",
                title = "Device admin",
                reason = "Prevents normal uninstallation while a study session or locked usage rule is active.",
                status = if (admin) PermissionStatusLevel.GREEN else PermissionStatusLevel.AMBER,
                requiredForBlocking = false,
            ),
            SetupPermissionItem(
                id = "notifications",
                title = "Notifications",
                reason = "Powers the live lock-screen chronometer, Now Bar status, and exam countdown reminders.",
                status = if (notif) PermissionStatusLevel.GREEN else PermissionStatusLevel.RED,
                requiredForBlocking = false,
            ),
            SetupPermissionItem(
                id = "battery",
                title = "Battery set to Unrestricted",
                reason = "Keeps the timer and app blocker alive in the background without One UI killing them.",
                status = if (battery) PermissionStatusLevel.GREEN else PermissionStatusLevel.AMBER,
                requiredForBlocking = false,
            ),
        )
    }

    fun hasMissingBlockerPermissions(context: Context, localStore: LocalDeviceStore): Boolean {
        return evaluateAll(context, localStore).any {
            it.requiredForBlocking && it.status != PermissionStatusLevel.GREEN
        }
    }

    fun hasAnyRedPermission(context: Context, localStore: LocalDeviceStore): Boolean {
        return evaluateAll(context, localStore).any { it.status == PermissionStatusLevel.RED }
    }

    fun openPermissionSettings(context: Context, permissionId: String, localStore: LocalDeviceStore) {
        val intent = when (permissionId) {
            "restricted_settings" -> {
                localStore.setRestrictedSettingsConfirmed(true)
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "device_admin" -> {
                val comp = ComponentName(context, StudyDeviceAdminReceiver::class.java)
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Prevents uninstalling Study Tracker while focus protection is engaged."
                    )
                }
            }
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            "battery" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
            } catch (_: Exception) {
                // Ignore if device has no settings handler
            }
        }
    }
}
