package com.guard

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single settings + status screen, built with plain framework Views styled from
 * the design handoff tokens (no Compose, no Material library). Dark is the primary
 * palette; the light palette is used automatically when the system is in light
 * mode. The service remains the single writer of guard state; this screen reads
 * prefs and stays live via a prefs listener + a foreground charging receiver.
 */
class MainActivity : Activity() {

    private val prefs by lazy { GuardPrefs(this) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val density get() = resources.displayMetrics.density

    private lateinit var pal: Palette

    /** True while [render] writes view state, so listeners don't loop back. */
    private var updating = false
    private var charging = false

    // Views updated by render().
    private lateinit var statusPanel: LinearLayout
    private lateinit var statusDot: View
    private lateinit var pulseRing: View
    private lateinit var statusKicker: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var armButton: Button
    private lateinit var stayArmedSwitch: Switch
    private lateinit var pauseChargingSwitch: Switch
    private lateinit var flashStrobeSwitch: Switch
    private lateinit var screenFlashSwitch: Switch
    private lateinit var bootSurvivalSwitch: Switch
    private lateinit var lowPowerSwitch: Switch
    private lateinit var sensitivityValue: TextView
    private lateinit var sensitivitySeek: SeekBar
    private lateinit var graceValue: TextView
    private lateinit var graceSeek: SeekBar
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifButton: Button
    private lateinit var logContainer: LinearLayout

    private var pulseAnimator: ValueAnimator? = null
    private var alarmAnimator: ObjectAnimator? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
                else -> return
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = if (isNight()) Palette.DARK else Palette.LIGHT
        applySystemBars()
        setContentView(buildUi())
        prefs.registerListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        charging = isCharging()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, filter)
        }
        render()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(powerReceiver) }
        stopPulse()
        stopAlarmPulse()
        super.onPause()
    }

    override fun onDestroy() {
        prefs.unregisterListener(prefsListener)
        super.onDestroy()
    }

    // ---- Units / drawables ------------------------------------------------

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    private fun roundRect(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != 0 && strokeDp > 0f) {
                setStroke((dpf(strokeDp)).toInt().coerceAtLeast(1), stroke)
            }
        }

    private fun oval(color: Int, sizeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(sizeDp), dp(sizeDp))
    }

    private fun withAlpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    // ---- UI construction --------------------------------------------------

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(30))
        }

        column.addView(buildHeader())
        column.addView(buildStatusPanel(), gap(12))
        column.addView(buildArmButton(), gap(12))
        column.addView(buildSecondaryButton(), gap(8))

        // Behavior
        column.addView(cardWith("BEHAVIOR") { card ->
            stayArmedSwitch = toggleRow(card, "Stay armed after unlock",
                "Unlocking stops the siren but keeps guarding until you Disarm.", true
            ) { prefs.stayArmedAfterUnlock = it }
            divider(card)
            pauseChargingSwitch = toggleRow(card, "Pause while charging",
                "Unplugging in public instantly activates watching.", false
            ) { prefs.pauseWhileCharging = it }
        }, gap(12))

        // Alarm effects
        column.addView(cardWith("ALARM EFFECTS") { card ->
            flashStrobeSwitch = toggleRow(card, "Flashlight strobe",
                "Strobe the camera flash during the alarm.", false
            ) { prefs.flashStrobe = it }
            divider(card)
            screenFlashSwitch = toggleRow(card, "Screen flash",
                "Flash the screen white/black over the lock screen.", false
            ) { prefs.screenFlash = it }
        }, gap(12))

        // Sensitivity
        column.addView(cardWith("SENSITIVITY") { card ->
            sensitivityValue = valueLabel()
            card.addView(sensitivityValue)
            sensitivitySeek = slider(100) { p ->
                prefs.sensitivity = p
                sensitivityValue.text = sensitivityText(p)
            }
            card.addView(sensitivitySeek, wide().apply { topMargin = dp(6) })
        }, gap(12))

        // Grace period
        column.addView(cardWith("GRACE PERIOD") { card ->
            graceValue = valueLabel()
            card.addView(graceValue)
            card.addView(caption("Time to unlock after motion before the siren sounds."))
            graceSeek = slider(GuardPrefs.MAX_GRACE_SECONDS - GuardPrefs.MIN_GRACE_SECONDS) { p ->
                val secs = p + GuardPrefs.MIN_GRACE_SECONDS
                prefs.graceSeconds = secs
                graceValue.text = "${secs}s"
            }
            card.addView(graceSeek, wide().apply { topMargin = dp(6) })
        }, gap(12))

        // Required setup
        column.addView(cardWith("REQUIRED SETUP") { card ->
            batteryStatus = statusLine()
            batteryButton = actionButton("Grant", solid = true) { requestBatteryExemption() }
            card.addView(setupRow("Battery optimization exemption", batteryStatus, batteryButton))
            divider(card)
            notifStatus = statusLine()
            notifButton = actionButton("Enable", solid = false) { requestNotifications() }
            card.addView(setupRow("Notifications permission", notifStatus, notifButton))
            divider(card)
            val sleep = statusLine().apply {
                text = "⚑ Manual — please verify"; setTextColor(pal.warn)
            }
            card.addView(setupRow("Not in Samsung “Sleeping apps”", sleep,
                actionButton("Open settings", solid = false) { openAppSettings() }))
        }, gap(12))

        // Advanced
        column.addView(cardWith("ADVANCED") { card ->
            bootSurvivalSwitch = toggleRow(card, "Re-arm after reboot",
                "If the phone restarts while armed, arm again automatically.", false
            ) { prefs.bootSurvival = it }
            divider(card)
            lowPowerSwitch = toggleRow(card, "Battery-saver detection",
                "Low-power sensor: saves battery but slower & less reliable.", false
            ) { prefs.lowPowerMode = it }
        }, gap(12))

        // Test siren
        column.addView(testButton(), gap(12))

        // Event log
        column.addView(buildLogCard(), gap(12))

        return ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            isFillViewport = true
            addView(column)
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = "🛡"
            textSize = 19f
            gravity = Gravity.CENTER
            background = roundRect(pal.brand, 11f)
            val s = dp(38)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        row.addView(badge)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = "Guard"; textSize = 21f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.text); letterSpacing = -0.02f
        })
        texts.addView(TextView(this).apply {
            text = "ON-DEVICE · NO ACCOUNTS"; textSize = 10f
            setTypeface(typeface, Typeface.BOLD); setTextColor(pal.text3); letterSpacing = 0.13f
        })
        row.addView(texts)
        return row
    }

    private fun buildStatusPanel(): View {
        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val dotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Pulse ring behind the dot (animated only while watching), then the dot.
        val dotHolder = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val s = dp(12)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        pulseRing = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
        }
        statusDot = View(this)
        // Overlay dot on ring using a FrameLayout-like: put ring then dot in a frame.
        val frame = android.widget.FrameLayout(this).apply {
            val s = dp(12)
            layoutParams = LinearLayout.LayoutParams(s, s)
            addView(pulseRing, android.widget.FrameLayout.LayoutParams(s, s))
            addView(statusDot, android.widget.FrameLayout.LayoutParams(s, s))
        }
        dotRow.addView(frame)
        statusKicker = TextView(this).apply {
            textSize = 11f; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.16f
            setPadding(dp(10), 0, 0, 0)
        }
        dotRow.addView(statusKicker)
        statusPanel.addView(dotRow)

        statusTitle = TextView(this).apply {
            textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.text)
            letterSpacing = -0.01f
            setPadding(0, dp(10), 0, 0)
        }
        statusPanel.addView(statusTitle)
        statusSubtitle = TextView(this).apply {
            textSize = 13f; setTextColor(pal.text2); setPadding(0, dp(3), 0, 0)
        }
        statusPanel.addView(statusSubtitle)
        return statusPanel
    }

    private fun buildArmButton(): Button {
        armButton = Button(this).apply {
            textSize = 17f; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.03f
            isAllCaps = false
            stateListAnimator = null
            setOnClickListener { GuardService.send(context, GuardService.ACTION_TOGGLE) }
        }
        return armButton.also { it.layoutParams = wide().apply { height = dp(58) } }
    }

    private fun buildSecondaryButton(): View {
        val b = Button(this).apply {
            text = "＋ Add Guard tile to Quick Settings"
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
            setTextColor(pal.text)
            stateListAnimator = null
            background = roundRect(Color.TRANSPARENT, 13f, pal.border, 1.5f)
            setOnClickListener { addQuickTile() }
        }
        b.layoutParams = wide().apply { height = dp(46) }
        b.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        return b
    }

    private fun testButton(): View {
        val b = Button(this).apply {
            text = "Test siren"
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
            setTextColor(pal.danger)
            stateListAnimator = null
            background = roundRect(pal.elevated, 14f, pal.border, 1.5f)
            setOnClickListener { GuardService.send(context, GuardService.ACTION_TEST_SIREN) }
        }
        b.layoutParams = wide().apply { height = dp(52) }
        return b
    }

    private fun buildLogCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(pal.card, 20f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(cardHeading("EVENT LOG"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Clear"; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.brand); setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { prefs.clearLog(); render() }
        })
        card.addView(header, wide())
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0)
        }
        card.addView(logContainer, wide())
        return card
    }

    // ---- Small builders ---------------------------------------------------

    private fun gap(topDp: Int) = wide().apply { topMargin = dp(topDp) }
    private fun wide() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun cardHeading(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.1f; setTextColor(pal.text2)
    }

    private fun valueLabel() = TextView(this).apply {
        textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.brand)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text; textSize = 12.5f; setTextColor(pal.text2); setPadding(0, dp(3), 0, 0)
    }

    private fun statusLine() = TextView(this).apply {
        textSize = 12.5f; setPadding(0, dp(2), 0, 0)
    }

    /** A card container with a heading; [body] fills its content. */
    private fun cardWith(heading: String, body: (LinearLayout) -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(pal.card, 20f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(cardHeading(heading), wide().apply { bottomMargin = dp(4) })
        body(card)
        return card
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            setBackgroundColor(pal.border)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1).coerceAtLeast(1)))
    }

    private fun toggleRow(
        parent: LinearLayout, title: String, subtitle: String,
        defaultOn: Boolean, onChange: (Boolean) -> Unit,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.text)
        })
        texts.addView(TextView(this).apply {
            text = subtitle; textSize = 12.5f; setTextColor(pal.text2); setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            styleSwitch(this)
            setOnCheckedChangeListener { _, checked -> if (!updating) onChange(checked) }
        }
        row.addView(sw)
        parent.addView(row, wide())
        return sw
    }

    private fun setupRow(label: String, status: TextView, action: Button): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.text)
        })
        texts.addView(status)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(action)
        return row
    }

    private fun actionButton(label: String, solid: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label; textSize = 13f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
            stateListAnimator = null
            minWidth = 0; minimumWidth = 0
            setPadding(dp(15), 0, dp(15), 0)
            if (solid) {
                background = roundRect(pal.brand, 11f); setTextColor(Color.WHITE)
            } else {
                background = roundRect(Color.TRANSPARENT, 11f, pal.border, 1.5f)
                setTextColor(pal.text2)
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(34))
            setOnClickListener { onClick() }
        }
    }

    private fun slider(max: Int, onValue: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            this.max = max
            styleSeek(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!updating && fromUser) onValue(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
    }

    private fun styleSwitch(sw: Switch) {
        sw.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(pal.brand, pal.track),
        )
    }

    private fun styleSeek(sb: SeekBar) {
        sb.progressTintList = ColorStateList.valueOf(pal.brand)
        sb.progressBackgroundTintList = ColorStateList.valueOf(pal.track)
        sb.thumb = oval(Color.WHITE, 22)
        sb.splitTrack = false
        sb.setPadding(dp(8), sb.paddingTop, dp(8), sb.paddingBottom)
    }

    // ---- Rendering --------------------------------------------------------

    private fun render() {
        updating = true

        val state = prefs.state
        val watching = prefs.watching
        val chargePaused = prefs.pauseWhileCharging && charging && state == GuardState.ARMED && !watching
        val sc = stateColor(state, watching, chargePaused)

        // Status panel tint.
        val alarmBoost = if (state == GuardState.ALARM) 0.08f else 0f
        val tintA = (if (pal.isDark) 0.16f else 0.11f) + alarmBoost
        statusPanel.background = roundRect(withAlpha(sc, tintA), 18f)
        statusDot.background = oval(sc, 12)
        pulseRing.background = oval(withAlpha(sc, 0.5f), 12)
        statusKicker.setTextColor(sc)

        val (kicker, title, subtitle) = when {
            state == GuardState.UNARMED ->
                Triple("OFF", "Guard is off", "Arm to protect your phone")
            state == GuardState.TRIGGERED ->
                Triple("MOTION", "Motion detected", "Unlock now!")
            state == GuardState.ALARM ->
                Triple("ALARM", "Phone moved!", "Unlock to silence")
            watching ->
                Triple("ARMED", "Watching for motion", "Your phone is protected")
            chargePaused ->
                Triple("ARMED", "Paused while charging", "Unplug to activate watching")
            else ->
                Triple("ARMED", "Waiting", "Lock the phone to start watching")
        }
        statusKicker.text = kicker
        statusTitle.text = title
        statusSubtitle.text = subtitle

        // Pulse ring only while watching; alarm panel pulse only in ALARM.
        if (watching && state == GuardState.ARMED) startPulse() else stopPulse()
        if (state == GuardState.ALARM) startAlarmPulse() else stopAlarmPulse()

        // Primary button.
        armButton.isAllCaps = false
        if (state.isActive) {
            armButton.text = "DISARM"
            armButton.background = roundRect(Color.TRANSPARENT, 16f, sc, 1.5f)
            armButton.setTextColor(sc)
        } else {
            armButton.text = "ARM"
            armButton.background = roundRect(pal.brand, 16f)
            armButton.setTextColor(Color.WHITE)
        }

        stayArmedSwitch.isChecked = prefs.stayArmedAfterUnlock
        pauseChargingSwitch.isChecked = prefs.pauseWhileCharging
        flashStrobeSwitch.isChecked = prefs.flashStrobe
        screenFlashSwitch.isChecked = prefs.screenFlash
        bootSurvivalSwitch.isChecked = prefs.bootSurvival
        lowPowerSwitch.isChecked = prefs.lowPowerMode

        val sens = prefs.sensitivity
        sensitivityValue.text = sensitivityText(sens)
        sensitivitySeek.progress = sens
        val grace = prefs.graceSeconds
        graceValue.text = "${grace}s"
        graceSeek.progress = grace - GuardPrefs.MIN_GRACE_SECONDS

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        setExemption(batteryStatus, batteryButton, pm.isIgnoringBatteryOptimizations(packageName))
        setExemption(notifStatus, notifButton, areNotificationsAllowed())

        renderLog()
        updating = false
    }

    private fun sensitivityText(v: Int): String {
        val q = when { v < 34 -> "Low"; v < 67 -> "Balanced"; else -> "High" }
        return "$v · $q"
    }

    private fun setExemption(status: TextView, button: Button, granted: Boolean) {
        if (granted) {
            status.text = "✓ Granted"; status.setTextColor(pal.ok)
            button.visibility = View.GONE
        } else {
            status.text = "✕ Not granted"; status.setTextColor(pal.danger)
            button.visibility = View.VISIBLE
        }
    }

    private fun renderLog() {
        logContainer.removeAllViews()
        val entries = prefs.logEntries()
        if (entries.isEmpty()) {
            logContainer.addView(TextView(this).apply {
                text = "No events yet. Arm the guard to start logging."
                textSize = 12f; setTextColor(pal.text3)
            })
            return
        }
        entries.asReversed().take(60).forEach { entry ->
            val msg = entry.message
            val msgColor = when {
                msg.contains("ALARM") -> pal.danger
                msg.contains("Motion") -> pal.warn
                msg.contains("watching", ignoreCase = true) -> pal.ok
                else -> pal.text
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
            }
            line.addView(TextView(this).apply {
                text = timeFmt.format(Date(entry.timestamp))
                textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(pal.text3)
                setPadding(0, 0, dp(10), 0)
            })
            line.addView(TextView(this).apply {
                text = msg; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(msgColor)
            })
            logContainer.addView(line)
        }
    }

    // ---- Animations -------------------------------------------------------

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseRing.visibility = View.VISIBLE
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1700
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedValue as Float
                val scale = 1f + f * 2.2f
                pulseRing.scaleX = scale
                pulseRing.scaleY = scale
                pulseRing.alpha = 0.5f * (1f - f)
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRing.alpha = 0f
        pulseRing.scaleX = 1f
        pulseRing.scaleY = 1f
    }

    private fun startAlarmPulse() {
        if (alarmAnimator != null) return
        alarmAnimator = ObjectAnimator.ofFloat(statusPanel, "alpha", 1f, 0.5f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopAlarmPulse() {
        alarmAnimator?.cancel()
        alarmAnimator = null
        statusPanel.alpha = 1f
    }

    // ---- Colors -----------------------------------------------------------

    private fun stateColor(state: GuardState, watching: Boolean, chargePaused: Boolean): Int = when {
        state == GuardState.UNARMED -> pal.stOff
        state == GuardState.TRIGGERED -> pal.stTriggered
        state == GuardState.ALARM -> pal.stAlarm
        watching -> pal.stWatching
        else -> pal.stWaiting // waiting or charge-paused both use amber
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (!pal.isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    // ---- System status + actions -----------------------------------------

    private fun isCharging(): Boolean {
        val s = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (s?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun areNotificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    }

    private fun requestBatteryExemption() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            openAppSettings()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun addQuickTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(this, GuardTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_shield),
                mainExecutor,
            ) { _: Int -> }
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    // ---- Palette ----------------------------------------------------------

    private class Palette(
        val isDark: Boolean,
        val bg: Int, val card: Int, val elevated: Int, val border: Int,
        val text: Int, val text2: Int, val text3: Int, val brand: Int, val track: Int,
        val ok: Int, val warn: Int, val danger: Int,
        val stOff: Int, val stWaiting: Int, val stWatching: Int,
        val stTriggered: Int, val stAlarm: Int,
    ) {
        companion object {
            val DARK = Palette(
                isDark = true,
                bg = 0xFF0C0F13.toInt(), card = 0xFF161A20.toInt(), elevated = 0xFF1E232B.toInt(),
                border = 0xFF2A313A.toInt(), text = 0xFFEAEDF0.toInt(), text2 = 0xFF8B939D.toInt(),
                text3 = 0xFF5C646E.toInt(), brand = 0xFF5090F7.toInt(), track = 0xFF333B45.toInt(),
                ok = 0xFF35C77C.toInt(), warn = 0xFFF0A93B.toInt(), danger = 0xFFFF5657.toInt(),
                stOff = 0xFF7B838D.toInt(), stWaiting = 0xFFF0A93B.toInt(),
                stWatching = 0xFF35C77C.toInt(), stTriggered = 0xFFFF8A3D.toInt(),
                stAlarm = 0xFFFF5657.toInt(),
            )
            val LIGHT = Palette(
                isDark = false,
                bg = 0xFFEDF0F3.toInt(), card = 0xFFFFFFFF.toInt(), elevated = 0xFFF1F4F7.toInt(),
                border = 0xFFE3E8ED.toInt(), text = 0xFF161A1F.toInt(), text2 = 0xFF5B646D.toInt(),
                text3 = 0xFF98A0A9.toInt(), brand = 0xFF2E6BE6.toInt(), track = 0xFFD3DAE1.toInt(),
                ok = 0xFF1E9E5A.toInt(), warn = 0xFFC4820B.toInt(), danger = 0xFFDC3B3B.toInt(),
                stOff = 0xFF8A929B.toInt(), stWaiting = 0xFFCE8710.toInt(),
                stWatching = 0xFF1E9E5A.toInt(), stTriggered = 0xFFE06816.toInt(),
                stAlarm = 0xFFDC3B3B.toInt(),
            )
        }
    }

    companion object {
        private const val REQ_NOTIF = 1
    }
}
