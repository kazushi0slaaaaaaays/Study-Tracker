package com.example.nativeandroid

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isInstalled: Boolean = true,
)

object InstalledAppsHelper {

    suspend fun loadInstalledLaunchableApps(context: Context): List<InstalledAppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val selfPkg = context.packageName
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val activities = try {
                pm.queryIntentActivities(intent, 0)
            } catch (_: Exception) {
                emptyList()
            }
            val seen = mutableSetOf<String>()
            val result = mutableListOf<InstalledAppInfo>()
            for (ri in activities) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == selfPkg || !seen.add(pkg)) continue
                val label = try {
                    ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                } catch (_: Exception) {
                    pkg
                }
                val icon = try {
                    drawableToImageBitmap(ri.loadIcon(pm))
                } catch (_: Exception) {
                    null
                }
                result.add(InstalledAppInfo(packageName = pkg, label = label, icon = icon))
            }
            result.sortedBy { it.label.lowercase() }
        }

    fun resolveAppInfo(context: Context, packageName: String, fallbackLabel: String = ""): InstalledAppInfo {
        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty()
                .ifBlank { fallbackLabel.ifBlank { packageName } }
            val icon = try {
                drawableToImageBitmap(pm.getApplicationIcon(appInfo))
            } catch (_: Exception) {
                null
            }
            InstalledAppInfo(packageName = packageName, label = label, icon = icon, isInstalled = true)
        } catch (_: PackageManager.NameNotFoundException) {
            InstalledAppInfo(
                packageName = packageName,
                label = fallbackLabel.ifBlank { packageName },
                icon = null,
                isInstalled = false,
            )
        } catch (_: Exception) {
            InstalledAppInfo(
                packageName = packageName,
                label = fallbackLabel.ifBlank { packageName },
                icon = null,
                isInstalled = false,
            )
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable?): ImageBitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap.asImageBitmap()
        }
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
