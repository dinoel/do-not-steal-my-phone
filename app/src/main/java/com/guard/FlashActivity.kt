package com.guard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen white/black strobe shown over the lock screen while the guard is in
 * ALARM, to make a stolen phone as visible as possible. Launched by the ALARM
 * notification's full-screen intent (the standard alarm mechanism) and finishes as
 * soon as the state leaves ALARM (i.e. when the owner unlocks/disarms).
 *
 * Plain framework Views + a Handler color toggle — no Compose. Security: there is
 * deliberately NO button that silences the alarm; the only control invokes the real
 * system keyguard, so the alarm stops only for someone who can actually unlock.
 */
class FlashActivity : Activity() {

    private val prefs by lazy { GuardPrefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var white = true

    private val strobe = object : Runnable {
        override fun run() {
            white = !white
            root.setBackgroundColor(if (white) Color.WHITE else Color.BLACK)
            handler.postDelayed(this, STROBE_MS)
        }
    }

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

        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(listener)
        if (prefs.state != GuardState.ALARM) finish()
    }

    override fun onResume() {
        super.onResume()
        handler.post(strobe)
    }

    override fun onPause() {
        handler.removeCallbacks(strobe)
        super.onPause()
    }

    override fun onStop() {
        prefs.unregisterListener(listener)
        super.onStop()
    }

    private fun buildUi(): View {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            // Tapping ANYWHERE brings up the real unlock prompt — no hunting for
            // the button under a strobing screen.
            setOnClickListener { promptUnlock() }
        }

        root.addView(TextView(this).apply {
            text = "⚠ PHONE ALARM ⚠"
            setTextColor(Color.RED) // reads on both white and black frames
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, lp(dp(24)))

        root.addView(Button(this).apply {
            text = "Unlock to silence"
            textSize = 20f
            setOnClickListener { promptUnlock() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Tap anywhere to unlock  •  Back to hide this screen"
            setTextColor(Color.RED)
            textSize = 14f
            gravity = Gravity.CENTER
        }, lp(dp(24)))

        return root
    }

    private fun lp(topMargin: Int) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { this.topMargin = topMargin }

    /** Ask the system keyguard to authenticate; a real unlock fires
     *  ACTION_USER_PRESENT, which stops the alarm and finishes this screen. */
    private fun promptUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, null)
        }
    }

    companion object {
        private const val STROBE_MS = 110L
    }
}
