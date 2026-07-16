package com.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The single foreground service that owns the whole guard: the state machine,
 * sensor registration, the wake lock, the siren, and the USER_PRESENT receiver
 * (spec §6). It is the only component that mutates [GuardState]; everything else
 * (tile, notification, in-app screen) reads the persisted state.
 *
 * Detection strategy: by default the ARMED stage holds a partial wake lock and
 * runs a wake-up accelerometer, detecting either a *tilt* away from the phone's
 * resting orientation (lifted off a surface) or a *jolt* (grabbed). This fires
 * instantly and, crucially, keeps working with the screen off. That costs some
 * battery while armed, which is the right trade for the "step away for minutes"
 * scenario. A low-power mode (one-shot TYPE_SIGNIFICANT_MOTION, no wake lock) is
 * available as a setting for users who want battery savings and whose device
 * cooperates.
 *
 * Lifecycle: the service only runs while the guard is active
 * (ARMED/TRIGGERED/ALARM); on reaching UNARMED it stops itself, so there is no
 * permanent notification and zero cost while off. The tile reads state straight
 * from prefs, so it still works while the service is stopped.
 */
class GuardService : Service() {

    private lateinit var prefs: GuardPrefs
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var siren: SirenPlayer

    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    private var cameraManager: CameraManager? = null
    private var flashCameraId: String? = null
    private var torchOn = false

    /** The three sub-modes of ARMED — see [updateWatchState]. */
    private enum class WatchMode {
        /** Waiting for the phone to be locked; sensors off. */
        IDLE,

        /** Paused on an owner-connected charger. In the reliable (accelerometer)
         *  mode the sensor keeps running so the resting baseline stays warm, but
         *  threshold hits only re-baseline — they never alarm. */
        PAUSED_CHARGING,

        /** Actively watching; motion leads to TRIGGERED/ALARM. */
        WATCHING,
    }

    private var watchMode = WatchMode.IDLE

    /**
     * True while motion should lead to an alarm (mode WATCHING). During
     * PAUSED_CHARGING the accelerometer may still be running ([sensorsRunning])
     * but this stays false, so the listener re-baselines instead of alarming.
     */
    private var detectionActive = false

    /** True while the standby accelerometer listener is registered. */
    private var sensorsRunning = false

    /** True once the phone has been locked (screen off) since arming. */
    private var hasBeenLocked = false

    /**
     * SECURITY GATE for "pause while charging": only a charger that was connected
     * while the owner was demonstrably present (at arm time, or before the phone
     * was locked) is allowed to pause the alarm. A charger connected AFTER the
     * phone is locked — e.g. a thief's power bank trying to silence the guard —
     * must never pause it.
     */
    private var chargerPauseAllowed = false

    private var sigMotionSensor: Sensor? = null
    private var wakeUpAccel: Sensor? = null

    private val current: GuardState
        get() = prefs.state

    // ---- Accelerometer standby detection state ----------------------------

    /** Low-pass estimate of the gravity vector (device orientation). */
    private var gX = 0f
    private var gY = 0f
    private var gZ = 0f
    private var gravityInit = false

    /** Baseline gravity vector captured once the phone is resting/still. */
    private var baseX = 0f
    private var baseY = 0f
    private var baseZ = 0f
    private var baselineFrozen = false

    private var armStartMs = 0L
    private var stillSinceMs = 0L
    private var detectHits = 0

    // ---- Standby: one-shot significant motion (low-power mode) ------------

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            Log.i(TAG, "Significant motion fired (low-power mode)")
            onMotionDetected("significant motion")
        }
    }

    // ---- Standby: reliable wake-up accelerometer (default) ----------------

    private val standbyAccelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val now = SystemClock.elapsedRealtime()

            // Low-pass filter to estimate the gravity direction.
            if (!gravityInit) {
                gX = x; gY = y; gZ = z
                gravityInit = true
            } else {
                gX = GRAVITY_LP * gX + (1 - GRAVITY_LP) * x
                gY = GRAVITY_LP * gY + (1 - GRAVITY_LP) * y
                gZ = GRAVITY_LP * gZ + (1 - GRAVITY_LP) * z
            }

            val jolt = abs(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)

            // Phase 1: wait until the phone is set down and still, then lock in the
            // resting orientation as the baseline. This makes "arm, then place the
            // phone" work without instantly false-triggering on the placing motion.
            if (!baselineFrozen) {
                baseX = gX; baseY = gY; baseZ = gZ
                if (jolt < STILL_JOLT_MS2) {
                    if (stillSinceMs == 0L) stillSinceMs = now
                    else if (now - stillSinceMs >= STILL_MS) {
                        baselineFrozen = true
                        Log.i(TAG, "Baseline orientation locked; now watching for pickup")
                        logEvent(
                            if (detectionActive) "Phone placed & still — watching for pickup"
                            else "Resting position locked (charging — alarm paused)"
                        )
                    }
                } else {
                    stillSinceMs = 0L
                }
                // Safety: never wait forever to arm (e.g. a vibrating surface).
                if (now - armStartMs >= BASELINE_MAX_MS) baselineFrozen = true
                return
            }

            // Phase 2: watch for a tilt off the resting orientation or a jolt.
            val tiltDeg = angleBetweenDeg(gX, gY, gZ, baseX, baseY, baseZ)
            val sens = prefs.sensitivity
            val tiltThresh = GuardPrefs.sensitivityToTiltDeg(sens)
            val joltThresh = GuardPrefs.sensitivityToJoltMs2(sens)

            if (tiltDeg > tiltThresh || jolt > joltThresh) {
                detectHits++
                if (detectHits >= DETECT_HITS) {
                    if (detectionActive) {
                        val reason = "tilt ${"%.0f".format(tiltDeg)}° / jolt ${"%.1f".format(jolt)}"
                        Log.i(TAG, "Pickup detected ($reason)")
                        onMotionDetected(reason)
                    } else {
                        // Charging-paused: the phone was legitimately moved (e.g. the
                        // owner repositioned it on the charger). Adopt the new resting
                        // position so unplug-detection stays instant AND accurate —
                        // never alarm from here.
                        Log.i(TAG, "Moved while charging-paused — re-baselining")
                        baselineFrozen = false
                        stillSinceMs = 0L
                        detectHits = 0
                        armStartMs = now
                    }
                }
            } else {
                detectHits = 0
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ---- USER_PRESENT (owner unlocked) ------------------------------------

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "ACTION_USER_PRESENT — owner is here, disarming")
                    onUserPresent()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // The phone was just locked — from now on it's eligible to watch
                    // (so placing/locking the phone never self-triggers).
                    if (current == GuardState.ARMED) {
                        hasBeenLocked = true
                        updateWatchState()
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    if (current == GuardState.ARMED) {
                        if (!hasBeenLocked) {
                            // Owner is still using the phone — this charger may pause.
                            chargerPauseAllowed = true
                            logEvent("Charger connected")
                        } else {
                            // Connected AFTER the phone was locked: could be a thief's
                            // power bank trying to silence the guard. Never pause.
                            logEvent("Charger connected while locked — NOT pausing")
                        }
                        updateWatchState()
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    chargerPauseAllowed = false
                    if (current == GuardState.ARMED) {
                        logEvent("Charger UNPLUGGED")
                        updateWatchState()
                    }
                }
            }
        }
    }

    // ---- Grace timer ------------------------------------------------------

    private val graceExpiredRunnable = Runnable { onGraceExpired() }

    // =======================================================================

    override fun onCreate() {
        super.onCreate()
        prefs = GuardPrefs(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        siren = SirenPlayer(this)
        // If the process died mid-alarm, the user's alarm volume was left pinned
        // at max; the saved value is persisted, so put it back now.
        siren.restoreLeftoverVolume()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        flashCameraId = findFlashCameraId()

        sigMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        // A wake-up accelerometer keeps delivering with the screen off; fall back
        // to the normal one (still fine because we hold a wake lock while armed).
        wakeUpAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createChannels()

        // ACTION_USER_PRESENT cannot be received via a manifest receiver; register
        // at runtime. It is sent by the SYSTEM, so the receiver must be EXPORTED —
        // RECEIVER_NOT_EXPORTED would restrict delivery to our own app's broadcasts
        // and the unlock event would never arrive. Exporting is safe because
        // ACTION_USER_PRESENT is a protected broadcast only the OS can send.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )

        // Establish foreground status immediately (5-second startForegroundService deadline).
        startForegroundWithState()

        // Restore after a process (re)creation. If we were mid-alarm when killed we
        // deliberately restore to ARMED rather than resuming a siren out of nowhere.
        when (current) {
            GuardState.ARMED, GuardState.TRIGGERED, GuardState.ALARM -> {
                Log.i(TAG, "Restoring after (re)create; was $current, resuming as ARMED")
                logEvent("Service restarted — resuming armed")
                prefs.state = GuardState.ARMED
                enterArmed()
            }
            GuardState.UNARMED -> { /* nothing to restore */ }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm()
            ACTION_DISARM -> requestDisarm()
            ACTION_TOGGLE -> if (current.isActive) requestDisarm() else arm()
            ACTION_SELF_CHECK -> selfCheck()
            ACTION_TEST_SIREN -> testSiren()
            null -> selfCheck() // START_STICKY redelivery has a null intent
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        cancelGraceTimer()
        unregisterStandby()
        siren.stop()
        stopAlarmVibration()
        stopTorchStrobe()
        releaseWakeLock()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    // ---- Transitions ------------------------------------------------------

    private fun arm() {
        if (current == GuardState.ARMED) return
        Log.i(TAG, "ARM (from $current)")
        cancelGraceTimer()
        siren.stop()
        stopAlarmVibration()
        stopTorchStrobe()
        setState(GuardState.ARMED)
        logEvent(if (prefs.lowPowerMode) "Armed (battery-saver mode)" else "Armed")
        enterArmed()
    }

    /** Actions performed on entering ARMED (also used when restoring). */
    private fun enterArmed() {
        detectionActive = false
        prefs.watching = false
        watchMode = WatchMode.IDLE
        unregisterStandby()   // nothing should be watching yet
        releaseWakeLock()     // no wake lock while merely waiting
        scheduleWatchdog()
        // If the screen is already off (e.g. boot re-arm), treat it as locked.
        hasBeenLocked = !powerManager.isInteractive
        // A charger present at arm/unlock time was connected by the owner, so it
        // is allowed to pause the alarm (see chargerPauseAllowed).
        chargerPauseAllowed = isPluggedIn()
        updateWatchState()
    }

    /** True if a charger is currently connected (any type). */
    private fun isPluggedIn(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    /** True if the "pause while charging" pause currently applies (setting on,
     *  an owner-connected charger — see [chargerPauseAllowed] — still plugged). */
    private fun isChargePaused(): Boolean =
        prefs.pauseWhileCharging && chargerPauseAllowed && isPluggedIn()

    /**
     * Central gate: we watch for motion only once the phone has been locked AND
     * it is not paused on an owner-connected charger. Unplugging the charger
     * activates watching immediately (with a warm baseline, so detection is live
     * from the very first sample). A charger connected after the phone was locked
     * (a thief's power bank) never pauses — see [chargerPauseAllowed].
     */
    private fun updateWatchState() {
        if (current != GuardState.ARMED) return
        val target = when {
            !hasBeenLocked -> WatchMode.IDLE
            isChargePaused() -> WatchMode.PAUSED_CHARGING
            else -> WatchMode.WATCHING
        }
        if (target == watchMode) {
            if (target == WatchMode.WATCHING) registerStandby() // re-assert (watchdog/restart)
            updateForegroundNotification() // text may have changed
            return
        }
        val from = watchMode
        watchMode = target
        when (target) {
            WatchMode.WATCHING -> startWatching(from)
            WatchMode.PAUSED_CHARGING -> pauseForCharging()
            WatchMode.IDLE -> stopWatching()
        }
        updateForegroundNotification()
    }

    /** Begin actively watching for motion. */
    private fun startWatching(from: WatchMode) {
        detectionActive = true
        prefs.watching = true
        if (from == WatchMode.PAUSED_CHARGING && sensorsRunning) {
            // The accelerometer kept running through the charging pause, so the
            // resting baseline is already locked in — detection is live from this
            // very moment, with no re-baseline blind window right when a
            // "unplug and walk away" thief would strike.
            acquireWakeLock() // back on battery: guarantee delivery ourselves
            logEvent("Now watching for pickup (instant — baseline kept)")
        } else {
            registerStandby()
            logEvent("Now watching for pickup")
        }
    }

    /** Pause the alarm on an owner-connected charger. */
    private fun pauseForCharging() {
        detectionActive = false
        prefs.watching = false
        if (prefs.lowPowerMode) {
            // Low-power mode has no baseline to keep warm — just stop the sensor.
            unregisterStandby()
            releaseWakeLock()
            logEvent("Charging — motion alarm paused")
        } else {
            // Keep the accelerometer running while paused: power is free on the
            // charger, and it keeps the resting baseline warm so that unplugging
            // starts real detection instantly. Threshold hits while paused only
            // re-baseline (see the listener) — they never alarm.
            if (!sensorsRunning) registerStandby()
            logEvent("Charging — alarm paused (still tracking resting position)")
        }
    }

    /** Fully stop watching (back to waiting for the phone to be locked). */
    private fun stopWatching() {
        detectionActive = false
        prefs.watching = false
        unregisterStandby()
        releaseWakeLock()
        logEvent("Paused — waiting to watch")
    }

    /**
     * External disarm request (notification action, tile, or a stray intent).
     * Backstop for the anti-theft guarantee: refuse to disarm while the phone is
     * *securely* locked, so silencing the alarm always requires an unlock. The
     * notification action and the tile also gate on unlock themselves; this is
     * defense-in-depth for any path that slips past them. Internal disarms
     * (owner-present, in-app button) don't go through here.
     */
    private fun requestDisarm() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (km.isKeyguardLocked && km.isDeviceSecure) {
            Log.w(TAG, "Disarm refused — phone is locked")
            logEvent("Disarm attempt while locked — ignored (unlock first)")
            return
        }
        disarm()
    }

    private fun disarm(reason: String = "Disarmed") {
        Log.i(TAG, "DISARM (from $current): $reason")
        cancelGraceTimer()
        unregisterStandby()
        siren.stop()
        stopAlarmVibration()
        stopTorchStrobe()
        releaseWakeLock()
        cancelWatchdog()
        detectionActive = false
        sensorsRunning = false
        watchMode = WatchMode.IDLE
        prefs.watching = false
        logEvent(reason)
        setState(GuardState.UNARMED)
        // Nothing left to guard: drop the notification and stop the service.
        stopSelfAndForeground()
    }

    /** ARMED -> TRIGGERED. Called from either detection path. */
    private fun onMotionDetected(reason: String = "motion") {
        if (current != GuardState.ARMED) return
        detectionActive = false
        watchMode = WatchMode.IDLE
        prefs.watching = false
        unregisterStandby()          // we've decided; stop watching
        acquireWakeLock()            // ensure held for grace + any alarm
        setState(GuardState.TRIGGERED)
        warningVibrate()
        val grace = prefs.graceSeconds
        mainHandler.postDelayed(graceExpiredRunnable, grace * 1000L)
        updateForegroundNotification()
        logEvent("Motion detected ($reason) — ${grace}s to unlock")
        Log.i(TAG, "TRIGGERED; grace = ${grace}s")
    }

    /** Grace timer elapsed with no owner unlock -> ALARM. */
    private fun onGraceExpired() {
        if (current != GuardState.TRIGGERED) return
        triggerAlarm()
    }

    /** TRIGGERED -> ALARM. Wake lock is already held from TRIGGERED. */
    private fun triggerAlarm() {
        Log.i(TAG, "ALARM")
        acquireWakeLock() // ensure held for the whole siren
        setState(GuardState.ALARM)
        updateForegroundNotification()
        startAlarmVibration()
        if (prefs.flashStrobe) startTorchStrobe()
        val volInfo = siren.start()
        logEvent("ALARM — siren + vibration${if (prefs.flashStrobe) " + flashlight" else ""} on ($volInfo)")
    }

    /**
     * Owner unlocked. Always stops any alarm immediately. Then either keeps the
     * guard armed (default — resumes watching when the screen locks again) or fully
     * disarms, depending on the "stay armed after unlock" setting.
     */
    private fun onUserPresent() {
        if (!current.isActive) return
        if (prefs.stayArmedAfterUnlock) {
            reArmAfterUnlock()
        } else {
            disarm("Phone unlocked by owner — disarmed")
        }
    }

    /** Stop all alarm output but stay armed (return to the waiting phase). */
    private fun reArmAfterUnlock() {
        cancelGraceTimer()
        siren.stop()
        stopAlarmVibration()
        stopTorchStrobe()
        logEvent("Unlocked — alarm stopped, still armed")
        setState(GuardState.ARMED)
        // Screen is on now, so enterArmed() puts us back in the waiting phase; it
        // will start watching again when the phone is next locked.
        enterArmed()
    }

    /** Watchdog / null-intent restart: make the live state consistent. */
    private fun selfCheck() {
        when (current) {
            GuardState.ARMED -> {
                updateWatchState()  // re-assert or start/pause per screen + charger
                scheduleWatchdog()
                logEvent("Watchdog check — still armed")
            }
            GuardState.TRIGGERED, GuardState.ALARM -> {
                // We were recreated mid-event; onCreate already downgraded to ARMED.
            }
            GuardState.UNARMED -> stopSelfAndForeground()
        }
    }

    // ---- Sensor registration ---------------------------------------------

    private fun registerStandby() {
        unregisterStandby() // keep idempotent

        if (prefs.lowPowerMode && sigMotionSensor != null) {
            // Low-power: one-shot significant motion, no wake lock (sensor hub
            // wakes the device). Slower/less reliable but negligible battery.
            Log.i(TAG, "Standby: low-power (TYPE_SIGNIFICANT_MOTION)")
            releaseWakeLock()
            val ok = sensorManager.requestTriggerSensor(triggerListener, sigMotionSensor)
            if (!ok) Log.w(TAG, "requestTriggerSensor returned false")
        } else {
            // Reliable: wake lock + wake-up accelerometer, tilt/jolt detection.
            Log.i(TAG, "Standby: reliable (wake-up accelerometer)")
            resetDetection()
            acquireWakeLock()
            wakeUpAccel?.let {
                sensorManager.registerListener(standbyAccelListener, it, SensorManager.SENSOR_DELAY_UI)
                sensorsRunning = true
            } ?: Log.w(TAG, "No accelerometer available")
        }
    }

    private fun unregisterStandby() {
        sigMotionSensor?.let { sensorManager.cancelTriggerSensor(triggerListener, it) }
        sensorManager.unregisterListener(standbyAccelListener)
        sensorsRunning = false
    }

    private fun resetDetection() {
        gravityInit = false
        baselineFrozen = false
        stillSinceMs = 0L
        detectHits = 0
        armStartMs = SystemClock.elapsedRealtime()
    }

    // ---- Wake lock --------------------------------------------------------

    private fun acquireWakeLock() {
        val wl = wakeLock ?: powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WL_TAG).also { wakeLock = it }
        if (!wl.isHeld) {
            // Always released explicitly on state exit / destroy; the timeout is a
            // safety net so a stuck lock can never drain the battery forever.
            wl.acquire(WAKELOCK_TIMEOUT_MS)
            Log.i(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
    }

    // ---- Grace timer helper ----------------------------------------------

    private fun cancelGraceTimer() = mainHandler.removeCallbacks(graceExpiredRunnable)

    // ---- Warning feedback -------------------------------------------------

    private val alarmAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Two short buzzes when motion is first detected (TRIGGERED). */
    private fun warningVibrate() {
        val pattern = longArrayOf(0, 150, 120, 150)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** Strong continuous vibration for the whole ALARM (repeats until stopped). */
    @Suppress("DEPRECATION")
    private fun startAlarmVibration() {
        val v = vibrator ?: return
        val timings = longArrayOf(0, 700, 300) // buzz 700ms, pause 300ms, repeat
        val effect = if (v.hasAmplitudeControl()) {
            val amplitudes = intArrayOf(0, 255, 0) // max intensity
            VibrationEffect.createWaveform(timings, amplitudes, 0) // repeat from index 0
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
        // Use alarm audio attributes so it vibrates even in silent/vibrate profiles.
        v.vibrate(effect, alarmAudioAttributes)
    }

    private fun stopAlarmVibration() {
        vibrator?.cancel()
    }

    // ---- Flashlight strobe ------------------------------------------------
    //
    // Runs on its own HandlerThread: setTorchMode is a binder call into the
    // camera service that can stall, and the main thread is busy rendering the
    // full-screen FlashActivity strobe during the alarm.

    private var torchThread: HandlerThread? = null
    private var torchHandler: Handler? = null

    private val torchStrobe = object : Runnable {
        override fun run() {
            torchOn = !torchOn
            setTorch(torchOn)
            torchHandler?.postDelayed(this, TORCH_INTERVAL_MS)
        }
    }

    private fun findFlashCameraId(): String? = runCatching {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private fun setTorch(on: Boolean) {
        val id = flashCameraId ?: return
        // setTorchMode needs no CAMERA permission; can throw if the camera is busy.
        runCatching { cameraManager?.setTorchMode(id, on) }
    }

    private fun startTorchStrobe() {
        if (flashCameraId == null || torchThread != null) return
        torchOn = false
        val thread = HandlerThread("guard-torch").apply { start() }
        torchThread = thread
        torchHandler = Handler(thread.looper).apply { post(torchStrobe) }
    }

    private fun stopTorchStrobe() {
        val handler = torchHandler
        torchHandler = null // strobe runnable sees null and stops rescheduling
        if (handler != null) {
            handler.removeCallbacks(torchStrobe)
            // Post the final "off" on the same thread so it is ordered after any
            // in-flight toggle — the torch can never be left stuck on.
            handler.post { setTorch(false) }
        } else {
            setTorch(false)
        }
        torchThread?.quitSafely()
        torchThread = null
        torchOn = false
    }

    // ---- Test siren -------------------------------------------------------

    private fun testSiren() {
        if (current == GuardState.ALARM) return // don't fight a real alarm
        Log.i(TAG, "Test siren (3s)")
        val volInfo = siren.start()
        if (prefs.flashStrobe) startTorchStrobe()
        logEvent("Test siren (3s) — $volInfo")
        mainHandler.postDelayed({
            siren.stop()
            stopTorchStrobe()
            if (!current.isActive) stopSelfAndForeground()
        }, TEST_SIREN_MS)
    }

    // ---- Watchdog (AlarmManager backstop) ---------------------------------
    //
    // Lightweight backstop only: an inexact allow-while-idle alarm that pokes the
    // service. Inexact (setAndAllowWhileIdle) avoids needing an exact-alarm
    // permission; a few minutes of drift is fine for a safety net. START_STICKY is
    // the primary restart mechanism.

    private fun scheduleWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
        am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, watchdogPendingIntent())
    }

    private fun cancelWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.cancel(watchdogPendingIntent())
    }

    private fun watchdogPendingIntent(): PendingIntent {
        val intent = Intent(this, WatchdogReceiver::class.java)
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- State + notification --------------------------------------------

    /** Append to the in-app event log (and logcat). */
    private fun logEvent(message: String) {
        Log.i(TAG, "EVENT: $message")
        prefs.appendLog(message)
    }

    /** Persist state, refresh the notification, and ask the tile to redraw. */
    private fun setState(newState: GuardState) {
        prefs.state = newState // also notifies the in-app screen's prefs listener
        GuardTileService.requestUpdate(this)
    }

    private fun stopSelfAndForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithState() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateForegroundNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = current
        val (channel, title) = when (state) {
            GuardState.ALARM -> CHANNEL_ALARM to getString(R.string.state_alarm)
            GuardState.TRIGGERED -> CHANNEL_STATUS to getString(R.string.state_triggered)
            GuardState.ARMED -> CHANNEL_STATUS to when (watchMode) {
                WatchMode.WATCHING -> getString(R.string.state_armed)
                WatchMode.PAUSED_CHARGING -> getString(R.string.state_armed_charging)
                WatchMode.IDLE -> getString(R.string.state_armed_waiting)
            }
            GuardState.UNARMED -> CHANNEL_STATUS to getString(R.string.state_unarmed)
        }

        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePi = PendingIntent.getService(
            this, 1, serviceIntent(this, ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel =
            if (state.isActive) getString(R.string.action_disarm) else getString(R.string.action_arm)

        // While active this action is always a Disarm. Require the device be
        // unlocked before it fires so a thief can't silence the alarm straight
        // from the lock-screen notification (enforced by the system, API 31+).
        val toggleAction = NotificationCompat.Action.Builder(R.drawable.ic_shield, toggleLabel, togglePi)
            .setAuthenticationRequired(state.isActive)
            .build()

        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(title)
            .setOngoing(true) // non-dismissable while the service is up
            .setContentIntent(contentPi)
            .setCategory(if (state == GuardState.ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (state == GuardState.ALARM) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .addAction(toggleAction)

        // In ALARM, use a full-screen intent to launch the white/black flash over
        // the lock screen (the standard alarm mechanism).
        if (state == GuardState.ALARM && prefs.screenFlash) {
            val flashPi = PendingIntent.getActivity(
                this, 2,
                Intent(this, FlashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(flashPi, true)
        }

        return builder.build()
    }

    private fun createChannels() {
        val status = NotificationChannel(
            CHANNEL_STATUS, getString(R.string.channel_status_name), NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_status_desc) }

        val alarm = NotificationChannel(
            CHANNEL_ALARM, getString(R.string.channel_alarm_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alarm_desc)
            setSound(null, null) // the siren is the sound; the notification adds none
            enableVibration(false)
        }

        notificationManager.createNotificationChannel(status)
        notificationManager.createNotificationChannel(alarm)
    }

    /** Angle in degrees between two 3-vectors. */
    private fun angleBetweenDeg(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
    ): Float {
        val dot = ax * bx + ay * by + az * bz
        val magA = sqrt(ax * ax + ay * ay + az * az)
        val magB = sqrt(bx * bx + by * by + bz * bz)
        if (magA < 1e-3f || magB < 1e-3f) return 0f
        val cos = (dot / (magA * magB)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    companion object {
        private const val TAG = "GuardService"

        const val ACTION_ARM = "com.guard.action.ARM"
        const val ACTION_DISARM = "com.guard.action.DISARM"
        const val ACTION_TOGGLE = "com.guard.action.TOGGLE"
        const val ACTION_SELF_CHECK = "com.guard.action.SELF_CHECK"
        const val ACTION_TEST_SIREN = "com.guard.action.TEST_SIREN"

        private const val CHANNEL_STATUS = "guard_status"
        private const val CHANNEL_ALARM = "guard_alarm"
        private const val NOTIFICATION_ID = 1

        private const val WL_TAG = "Guard:AlarmWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 60 min safety net

        // Detection tuning (see GuardPrefs for the sensitivity-mapped thresholds).
        private const val GRAVITY_LP = 0.85f       // gravity low-pass smoothing
        private const val STILL_JOLT_MS2 = 0.7f    // "still enough" to lock baseline
        private const val STILL_MS = 700L          // must be still this long first
        private const val BASELINE_MAX_MS = 10_000L // give up waiting for stillness
        private const val DETECT_HITS = 2          // consecutive samples to debounce noise

        private const val TORCH_INTERVAL_MS = 150L // flashlight strobe half-period
        private const val TEST_SIREN_MS = 9000L // one full cycle of the 4 alarm sounds
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        fun serviceIntent(context: Context, action: String): Intent =
            Intent(context, GuardService::class.java).setAction(action)

        /** Start the service (foreground) with the given action. */
        fun send(context: Context, action: String) {
            // Can throw ForegroundServiceStartNotAllowedException if started from
            // the background without the battery-optimization exemption; log rather
            // than crash. START_STICKY is the primary restart path anyway.
            runCatching {
                ContextCompat.startForegroundService(context, serviceIntent(context, action))
            }.onFailure {
                Log.w(TAG, "startForegroundService($action) failed", it)
                // Make the failure visible in the in-app event log for diagnosis.
                runCatching {
                    GuardPrefs(context).appendLog("Could not start service: ${it.javaClass.simpleName}")
                }
            }
        }
    }
}
