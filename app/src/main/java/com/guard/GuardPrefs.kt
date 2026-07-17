package com.guard

import android.content.Context
import android.content.SharedPreferences

/** One line in the in-app event log. */
data class LogEntry(val timestamp: Long, val message: String)

/**
 * Thin synchronous wrapper over [SharedPreferences]. SharedPreferences (rather
 * than DataStore) is deliberate: the service must read the persisted state
 * synchronously in onCreate before it decides whether to re-arm, and it must
 * write state changes without a coroutine scope. This is the single source of
 * truth that the tile, notification and in-app screen all read from.
 */
class GuardPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var state: GuardState
        get() = GuardState.fromName(prefs.getString(KEY_STATE, GuardState.UNARMED.name))
        set(value) = prefs.edit().putString(KEY_STATE, value.name).apply()

    /** Sensitivity slider value, 0..100. Higher = triggers/confirms more easily. */
    var sensitivity: Int
        get() = prefs.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_SENSITIVITY, value.coerceIn(0, 100)).apply()

    /** Grace period in seconds, clamped to the supported range. */
    var graceSeconds: Int
        get() = prefs.getInt(KEY_GRACE, DEFAULT_GRACE_SECONDS).coerceIn(MIN_GRACE_SECONDS, MAX_GRACE_SECONDS)
        set(value) = prefs.edit().putInt(KEY_GRACE, value.coerceIn(MIN_GRACE_SECONDS, MAX_GRACE_SECONDS)).apply()

    /**
     * If true (default), while armed and plugged into a charger the motion alarm
     * is paused; unplugging the charger immediately activates watching. Targets
     * the "charging in a café, thief unplugs and walks off" scenario.
     */
    var pauseWhileCharging: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_CHARGING, true)
        set(value) = prefs.edit().putBoolean(KEY_PAUSE_CHARGING, value).apply()

    /**
     * If true (default), unlocking stops any alarm but keeps the guard armed — it
     * resumes watching when the screen locks again. Only an explicit disarm turns
     * it off. If false, unlocking fully disarms (the original behavior).
     */
    var stayArmedAfterUnlock: Boolean
        get() = prefs.getBoolean(KEY_STAY_ARMED, true)
        set(value) = prefs.edit().putBoolean(KEY_STAY_ARMED, value).apply()

    /** If true, the guard re-arms itself after a reboot (only if it was armed). Default off. */
    var bootSurvival: Boolean
        get() = prefs.getBoolean(KEY_BOOT_SURVIVAL, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_SURVIVAL, value).apply()

    /**
     * If true, use the low-power standby (one-shot TYPE_SIGNIFICANT_MOTION, no
     * wake lock). This saves battery but on many phones is slow/insensitive to a
     * quick pickup and unreliable with the screen off. Default off, i.e. the
     * reliable accelerometer path (wake lock + wake-up accelerometer) is used.
     */
    var lowPowerMode: Boolean
        get() = prefs.getBoolean(KEY_LOW_POWER, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_POWER, value).apply()

    /**
     * True while ARMED and actually watching for motion (screen off). False during
     * the post-arm "waiting for the phone to be locked" phase. Reflected in the UI
     * so the app, notification and tile stay consistent.
     */
    var watching: Boolean
        get() = prefs.getBoolean(KEY_WATCHING, false)
        set(value) = prefs.edit().putBoolean(KEY_WATCHING, value).apply()

    /** Strobe the camera flashlight during the alarm. Default on. */
    var flashStrobe: Boolean
        get() = prefs.getBoolean(KEY_FLASH_STROBE, true)
        set(value) = prefs.edit().putBoolean(KEY_FLASH_STROBE, value).apply()

    /** Flash the whole screen white/black over the lock screen during the alarm. Default on. */
    var screenFlash: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_FLASH, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_FLASH, value).apply()

    /**
     * Alarm-stream volume saved while the siren forces max; -1 = nothing saved.
     * Persisted so a process death mid-alarm can't leave the volume pinned at max.
     */
    var savedAlarmVolume: Int
        get() = prefs.getInt(KEY_SAVED_ALARM_VOLUME, -1)
        set(value) = prefs.edit().putInt(KEY_SAVED_ALARM_VOLUME, value).apply()

    /** True while the manual siren test is playing, so the UI can show a Stop label. */
    var testSirenActive: Boolean
        get() = prefs.getBoolean(KEY_TEST_SIREN, false)
        set(value) = prefs.edit().putBoolean(KEY_TEST_SIREN, value).apply()

    /**
     * Append a timestamped line to the rolling event log (kept in the same prefs
     * file, so writing it also notifies the in-app screen's change listener). The
     * log is capped so it can never grow without bound. Safe to call from any
     * thread — SharedPreferences is synchronized.
     */
    fun appendLog(message: String) {
        val line = "${System.currentTimeMillis()}\t$message"
        val existing = prefs.getString(KEY_LOG, "").orEmpty()
        val combined = if (existing.isEmpty()) line else "$existing\n$line"
        val lines = combined.split("\n")
        val trimmed = if (lines.size > MAX_LOG_LINES) {
            lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        } else {
            combined
        }
        prefs.edit().putString(KEY_LOG, trimmed).apply()
    }

    /** Parsed log, oldest first. */
    fun logEntries(): List<LogEntry> {
        val raw = prefs.getString(KEY_LOG, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n").mapNotNull { l ->
            val tab = l.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val ts = l.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            LogEntry(ts, l.substring(tab + 1))
        }
    }

    fun clearLog() = prefs.edit().remove(KEY_LOG).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val NAME = "guard_prefs"
        const val KEY_STATE = "state"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_GRACE = "grace_seconds"
        const val KEY_STAY_ARMED = "stay_armed"
        const val KEY_PAUSE_CHARGING = "pause_charging"
        const val KEY_BOOT_SURVIVAL = "boot_survival"
        const val KEY_WATCHING = "watching"
        const val KEY_LOW_POWER = "low_power"
        const val KEY_FLASH_STROBE = "flash_strobe"
        const val KEY_SCREEN_FLASH = "screen_flash"
        const val KEY_LOG = "event_log"
        const val KEY_SAVED_ALARM_VOLUME = "saved_alarm_volume"
        const val KEY_TEST_SIREN = "test_siren_active"
        private const val MAX_LOG_LINES = 200

        const val DEFAULT_SENSITIVITY = 70
        const val DEFAULT_GRACE_SECONDS = 5
        const val MIN_GRACE_SECONDS = 2
        const val MAX_GRACE_SECONDS = 15

        /**
         * How far (degrees) the phone must tilt away from its resting orientation
         * before it counts as "lifted off the surface". Higher sensitivity = a
         * smaller tilt triggers.
         *
         *   sensitivity 0   -> 30 deg (must be clearly turned/lifted)
         *   sensitivity 100 -> 6 deg  (a slight nudge triggers)
         *   default 70      -> ~13 deg
         */
        fun sensitivityToTiltDeg(sensitivity: Int): Float {
            val s = sensitivity.coerceIn(0, 100) / 100f
            return 30f - s * (30f - 6f)
        }

        /**
         * Instantaneous jolt (m/s^2 above gravity) that counts as a grab/bump,
         * so a fast snatch triggers even before the tilt develops. Higher
         * sensitivity = a gentler jolt triggers.
         *
         *   sensitivity 0   -> 3.5 m/s^2
         *   sensitivity 100 -> 0.6 m/s^2
         *   default 70      -> ~1.5 m/s^2
         */
        fun sensitivityToJoltMs2(sensitivity: Int): Float {
            val s = sensitivity.coerceIn(0, 100) / 100f
            return 3.5f - s * (3.5f - 0.6f)
        }
    }
}
