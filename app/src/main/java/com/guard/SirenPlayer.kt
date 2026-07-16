package com.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log

/**
 * Owns the alarm siren. Plays [R.raw.siren] looped on the ALARM audio stream so
 * it is heard even when the ringer is on silent/vibrate (spec §8).
 *
 * On [start] it saves the current alarm-stream volume, forces it to max, then
 * plays; [stop] restores the saved volume. Always call [stop] to avoid leaving
 * the user's alarm volume pinned to max.
 */
class SirenPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val guardPrefs = GuardPrefs(appContext)

    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    val isPlaying: Boolean
        get() = player != null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Start looping the siren at max alarm volume. Idempotent.
     * Returns a short human-readable summary of the volume change (for the log).
     */
    fun start(): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (player != null) return "Siren already playing"

        // Save and max out the alarm-stream volume. Persisted too, so a process
        // death mid-alarm can't leave the user's volume pinned at max forever.
        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        guardPrefs.savedAlarmVolume = savedAlarmVolume ?: -1
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM, max,
                // No UI flash, but do allow overriding a DND/zen restriction.
                0
            )
        }.onFailure { Log.w(TAG, "Could not raise alarm volume", it) }
        val actual = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        requestAudioFocus()

        player = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            val afd = appContext.resources.openRawResourceFd(R.raw.siren)
            afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            isLooping = true
            setVolume(1f, 1f) // full player gain on top of the maxed stream
            prepare()
            start()
        }
        val summary = "Alarm volume $savedAlarmVolume/$max → $actual/$max"
        Log.i(TAG, "Siren started ($summary)")
        return summary
    }

    /** Stop immediately and restore the previous alarm volume. Idempotent. */
    fun stop() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null

        abandonAudioFocus()

        savedAlarmVolume?.let { prior ->
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prior, 0)
            }.onFailure { Log.w(TAG, "Could not restore alarm volume", it) }
        }
        savedAlarmVolume = null
        guardPrefs.savedAlarmVolume = -1
        Log.i(TAG, "Siren stopped and volume restored")
    }

    /**
     * If a previous process died mid-siren (leaving the alarm volume pinned at
     * max), restore the persisted pre-siren volume. Call once on service start.
     */
    fun restoreLeftoverVolume() {
        if (player != null) return
        val leftover = guardPrefs.savedAlarmVolume
        if (leftover >= 0) {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, leftover, 0)
            }.onFailure { Log.w(TAG, "Could not restore leftover alarm volume", it) }
            guardPrefs.savedAlarmVolume = -1
            Log.i(TAG, "Restored alarm volume left over from a previous run ($leftover)")
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).setAudioAttributes(audioAttributes).build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val TAG = "SirenPlayer"
    }
}
