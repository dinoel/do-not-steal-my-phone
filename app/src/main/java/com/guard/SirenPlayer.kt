package com.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Owns the alarm siren. The waveform is SYNTHESIZED at runtime with [AudioTrack]
 * (no audio asset shipped), looped seamlessly on the ALARM stream so it is heard
 * even on silent/vibrate (spec §8). Generating it in code keeps the APK tiny and
 * lets the siren be arbitrarily harsh.
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

    private var track: AudioTrack? = null
    private var sirenBuffer: ShortArray? = null // generated once, reused
    private var savedAlarmVolume: Int? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    val isPlaying: Boolean
        get() = track != null

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
        if (track != null) return "Siren already playing"

        // Save and max out the alarm-stream volume. Persisted too, so a process
        // death mid-alarm can't leave the user's volume pinned at max forever.
        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        guardPrefs.savedAlarmVolume = savedAlarmVolume ?: -1
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }.onFailure { Log.w(TAG, "Could not raise alarm volume", it) }
        val actual = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        requestAudioFocus()

        val buffer = sirenBuffer ?: buildSirenBuffer().also { sirenBuffer = it }
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .apply {
                    write(buffer, 0, buffer.size)
                    // Loop the whole buffer forever (frame indices; mono => 1 frame/sample).
                    setLoopPoints(0, buffer.size, -1)
                    setVolume(AudioTrack.getMaxVolume()) // full track gain
                    play()
                }
        }.onFailure { Log.w(TAG, "Could not start AudioTrack siren", it) }.getOrNull()

        val summary = "Alarm volume $savedAlarmVolume/$max → $actual/$max"
        Log.i(TAG, "Siren started ($summary)")
        return summary
    }

    /** Stop immediately and restore the previous alarm volume. Idempotent. */
    fun stop() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null

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
        if (track != null) return
        val leftover = guardPrefs.savedAlarmVolume
        if (leftover >= 0) {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, leftover, 0)
            }.onFailure { Log.w(TAG, "Could not restore leftover alarm volume", it) }
            guardPrefs.savedAlarmVolume = -1
            Log.i(TAG, "Restored alarm volume left over from a previous run ($leftover)")
        }
    }

    // ---- Synthesis --------------------------------------------------------

    /**
     * Build one seamless loop cycling four harsh siren characters (wail, hi-lo
     * two-tone, whoop, screech). A single continuous phase accumulator across all
     * segments avoids clicks at the boundaries; a soft-clip (tanh) adds harmonics
     * so it is loud and piercing on a small phone speaker.
     */
    private fun buildSirenBuffer(): ShortArray {
        val sr = SAMPLE_RATE
        val segSamples = (SEGMENT_SECONDS * sr).toInt()
        val total = segSamples * SEGMENT_COUNT
        val out = ShortArray(total)
        val twoPi = 2.0 * PI
        var phase = 0.0

        for (i in 0 until total) {
            val seg = i / segSamples
            val t = (i % segSamples).toDouble() / sr // seconds within the segment
            val freq = when (seg) {
                0 -> 2500.0 + 500.0 * sin(twoPi * 0.5 * t)       // slow wail 2000..3000
                1 -> if ((t / 0.35).toInt() % 2 == 0) 2600.0 else 1950.0 // hi-lo two-tone
                2 -> 1400.0 + 1800.0 * ((t * 3.0) % 1.0)         // rising whoop, 3/sec
                3 -> 2800.0 + 60.0 * sin(twoPi * 7.0 * t)        // screech w/ vibrato
                else -> 1700.0 + 2000.0 * ((t * 7.0) % 1.0)      // police "yelp": fast up-sweep, 7/sec
            }
            phase += twoPi * freq / sr
            if (phase > twoPi) phase -= twoPi

            // Soft clipping: harder on the screech, hardest on the yelp (nastiest bite).
            val drive = when (seg) {
                3 -> 4.0
                4 -> 5.5
                else -> 2.3
            }
            val shaped = tanh(drive * sin(phase)) / tanh(drive)
            out[i] = (shaped * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }

        // Tiny fades at the very ends so the loop seam doesn't click.
        val fade = (0.003 * sr).toInt()
        for (i in 0 until fade) {
            val g = i.toDouble() / fade
            out[i] = (out[i] * g).toInt().toShort()
            out[total - 1 - i] = (out[total - 1 - i] * g).toInt().toShort()
        }
        return out
    }

    // ---- Audio focus ------------------------------------------------------

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
        private const val SAMPLE_RATE = 22050
        private const val SEGMENT_COUNT = 5     // wail, two-tone, whoop, screech, yelp
        private const val SEGMENT_SECONDS = 2.2 // 5 segments => ~11s loop
    }
}
