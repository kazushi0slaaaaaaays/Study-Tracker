package com.example.nativeandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.data.LocalDeviceStore
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkText
import com.example.ui.theme.DarkTextDim
import com.example.ui.theme.DefaultAccent
import com.example.ui.theme.StudyTrackerTheme
import kotlinx.coroutines.delay

/**
 * Full-screen near-black blocked overlay (PDF Section 7.9):
 * Soft top gradient in danger color, large outlined lock icon, "<App> is blocked",
 * reason line, live unlock countdown for limits, and ONE button only: "Back to studying".
 * No skip, snooze, or unlock button anywhere.
 */
class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToStudying()
            }
        })

        val appName = intent.getStringExtra(EXTRA_APP_NAME)?.ifBlank { "This app" } ?: "This app"
        val reason = intent.getStringExtra(EXTRA_REASON)?.ifBlank { "Blocked by Study Tracker" }
            ?: "Blocked by Study Tracker"
        val unlockAtMs = intent.getLongExtra(EXTRA_UNLOCK_AT_MS, 0L)
        val localStore = LocalDeviceStore(this)

        setContent {
            StudyTrackerTheme(themeMode = "dark") {
                BlockedOverlayScreen(
                    appName = appName,
                    reason = reason,
                    unlockAtMs = unlockAtMs,
                    localStore = localStore,
                    onBackToStudying = { returnToStudying() },
                )
            }
        }
    }

    private fun returnToStudying() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(launchIntent)
        finish()
    }

    companion object {
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_UNLOCK_AT_MS = "extra_unlock_at_ms"
    }
}

@Composable
private fun BlockedOverlayScreen(
    appName: String,
    reason: String,
    unlockAtMs: Long,
    localStore: LocalDeviceStore,
    onBackToStudying: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(localStore.getGuardedNowMs()) }

    LaunchedEffect(unlockAtMs) {
        while (true) {
            nowMs = localStore.getGuardedNowMs()
            delay(1000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to DangerRed.copy(alpha = 0.24f),
                        0.32f to Color(0xFF0E090B),
                        1.0f to DarkBg,
                    )
                )
            )
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(DangerRed.copy(alpha = 0.14f), CircleShape)
                    .border(1.5.dp, DangerRed.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "Blocked app lock",
                    tint = DangerRed,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "$appName is blocked",
                style = MaterialTheme.typography.headlineMedium,
                color = DarkText,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = reason,
                style = MaterialTheme.typography.bodyLarge,
                color = DarkTextDim,
                textAlign = TextAlign.Center,
            )

            if (unlockAtMs > nowMs) {
                Spacer(modifier = Modifier.height(18.dp))
                val remainSec = ((unlockAtMs - nowMs) / 1000L).coerceAtLeast(0L)
                val hours = remainSec / 3600L
                val mins = (remainSec % 3600L) / 60L
                val secs = remainSec % 60L
                val formatted = when {
                    hours > 0 -> "${hours}h ${mins}m"
                    mins > 0 -> "${mins}m ${secs}s"
                    else -> "${secs}s"
                }
                Box(
                    modifier = Modifier
                        .background(DangerRed.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .border(1.dp, DangerRed.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Unlocks in $formatted",
                        style = MaterialTheme.typography.titleMedium,
                        color = DangerRed,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = onBackToStudying,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DefaultAccent,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("blocked_back_to_studying_button"),
            ) {
                Text(
                    text = "Back to studying",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
