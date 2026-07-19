package com.guard

import android.media.AudioManager

/** The three sub-modes of ARMED — see [WatchGate.watchMode]. */
enum class WatchMode {
    /** Waiting for the phone to be locked; sensors off. */
    IDLE,

    /** Paused on an owner-connected charger. In the reliable (accelerometer) mode
     *  the sensor keeps running so the resting baseline stays warm, but threshold
     *  hits only re-baseline — they never alarm. */
    PAUSED_CHARGING,

    /** Actively watching; motion leads to TRIGGERED/ALARM. */
    WATCHING,
}

/**
 * Every "should we watch / may we alarm / may we disarm" rule, as pure functions
 * of the inputs the service has already read from the system. Extracted from
 * `GuardService` because these are the decisions where a mistake is a security
 * hole rather than a bug, and here they are directly unit-testable.
 */
object WatchGate {

    /**
     * Central gate: watch for motion only once the phone has been locked AND it is
     * not paused on an owner-connected charger. Unplugging activates watching
     * immediately.
     */
    fun watchMode(
        hasBeenLocked: Boolean,
        pauseWhileChargingSetting: Boolean,
        chargerPauseAllowed: Boolean,
        pluggedIn: Boolean,
    ): WatchMode = when {
        !hasBeenLocked -> WatchMode.IDLE
        isChargePaused(pauseWhileChargingSetting, chargerPauseAllowed, pluggedIn) ->
            WatchMode.PAUSED_CHARGING
        else -> WatchMode.WATCHING
    }

    /** True if the "pause while charging" pause currently applies. */
    fun isChargePaused(
        pauseWhileChargingSetting: Boolean,
        chargerPauseAllowed: Boolean,
        pluggedIn: Boolean,
    ): Boolean = pauseWhileChargingSetting && chargerPauseAllowed && pluggedIn

    /**
     * SECURITY GATE for "pause while charging": only a charger connected while the
     * owner was demonstrably present (at arm time, or before the phone was locked)
     * may pause the alarm. A charger connected AFTER the phone was locked — e.g. a
     * thief's power bank plugged in to silence the guard — must never pause it.
     */
    fun chargerMayPause(hasBeenLocked: Boolean): Boolean = !hasBeenLocked

    /**
     * In [GuardMode.CARRIED], movement is the owner walking around, so tilt/jolt
     * must not alarm while the phone is covered. The instant it is uncovered the
     * mode behaves like [GuardMode.RESTING] again — a phone taken out and set down
     * is guarded without changing a setting.
     *
     * [covered] is null when the proximity state is unknown (no sensor, or nothing
     * reported yet). That must NOT suppress motion: a phone with no usable
     * proximity sensor has to keep the protection it had.
     */
    fun motionSuppressed(mode: GuardMode, covered: Boolean?): Boolean =
        mode == GuardMode.CARRIED && covered == true

    /**
     * ANTI-THEFT GUARANTEE: an external disarm (notification action, tile, stray
     * intent) is refused while the phone is *securely* locked, so silencing the
     * alarm always requires a real unlock. A device with no lock set at all has
     * nothing to unlock, so it is allowed through.
     */
    fun disarmAllowed(keyguardLocked: Boolean, deviceSecure: Boolean): Boolean =
        !(keyguardLocked && deviceSecure)

    /**
     * True when the phone is currently unlocked by its owner (a secure keyguard
     * that is dismissed). A thief cannot unlock a secure device, so this means the
     * owner is present — a backstop for a missed `ACTION_USER_PRESENT`, which could
     * otherwise leave the guard watching during normal use.
     */
    fun unlockedByOwner(keyguardLocked: Boolean, deviceSecure: Boolean): Boolean =
        deviceSecure && !keyguardLocked

    /**
     * True while a call is ringing or connected (cellular or VoIP), read from the
     * global audio mode — which needs NO permission. Grabbing the phone to answer
     * must never sound the alarm.
     */
    fun phoneBusy(audioMode: Int): Boolean =
        audioMode == MODE_RINGTONE || audioMode == MODE_IN_CALL || audioMode == MODE_IN_COMMUNICATION

    // Compile-time constants, so this object never loads an Android class and the
    // rules above stay testable on a plain JVM.
    private const val MODE_RINGTONE = AudioManager.MODE_RINGTONE
    private const val MODE_IN_CALL = AudioManager.MODE_IN_CALL
    private const val MODE_IN_COMMUNICATION = AudioManager.MODE_IN_COMMUNICATION
}
