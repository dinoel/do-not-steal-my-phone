package com.guard

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen white/black strobe shown over the lock screen while the guard is in
 * ALARM, to make a stolen phone as visible as possible. It is launched by the
 * ALARM notification's full-screen intent (the standard alarm mechanism) and
 * finishes as soon as the state leaves ALARM (i.e. when the owner unlocks/disarms).
 *
 * Security: there is deliberately NO button that silences the alarm. The only
 * control is "Unlock to silence", which invokes the real system keyguard — so the
 * alarm can only be stopped by someone who can actually unlock the phone.
 */
class FlashActivity : ComponentActivity() {

    private val prefs by lazy { GuardPrefs(this) }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (prefs.state != GuardState.ALARM) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Appear over the keyguard and wake the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Force the screen to full brightness for maximum visibility.
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        if (prefs.state != GuardState.ALARM) {
            finish()
            return
        }

        setContent { FlashScreen(onUnlock = ::promptUnlock) }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(listener)
        if (prefs.state != GuardState.ALARM) finish()
    }

    override fun onStop() {
        prefs.unregisterListener(listener)
        super.onStop()
    }

    /** Ask the system keyguard to authenticate; a real unlock fires
     *  ACTION_USER_PRESENT, which disarms the guard and finishes this screen. */
    private fun promptUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, null)
        }
    }
}

@Composable
private fun FlashScreen(onUnlock: () -> Unit) {
    // Strobe the background white <-> black quickly.
    val transition = rememberInfiniteTransition(label = "strobe")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 110, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )
    // Snap between the colors directly — animateColorAsState on top of the
    // transition would smooth the strobe into a gray crossfade and re-animate
    // (extra recomposition work) every half-period.
    val bg = if (phase < 0.5f) Color.White else Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            // Tapping ANYWHERE brings up the real unlock prompt — no hunting for
            // the button under a strobing screen.
            .clickable(onClick = onUnlock),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Red reads clearly on both white and black frames.
            Text(
                text = "⚠ PHONE ALARM ⚠",
                color = Color.Red,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Button(onClick = onUnlock) {
                Text("Unlock to silence", fontSize = 20.sp)
            }
            Text(
                text = "Tap anywhere to unlock  •  Back to hide this screen",
                color = Color.Red,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}
