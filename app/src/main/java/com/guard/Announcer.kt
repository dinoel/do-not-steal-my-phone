package com.guard

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks a configurable spoken warning during the alarm, using the on-device
 * TextToSpeech engine — no asset in the APK, no network, no permission. Purely
 * best-effort: if no TTS engine or voice data is available it stays silent and the
 * siren plays alone.
 *
 * While an utterance is playing, [onSpeaking] is invoked (true on start, false on
 * end) so the caller can duck the siren for intelligibility. The phrase repeats
 * with a short gap until [stop].
 */
class Announcer(context: Context, private val onSpeaking: (Boolean) -> Unit) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var active = false
    private var phrase = DEFAULT_TEXT
    private var locale: Locale = Locale.getDefault()

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Begin repeating the announcement. [langTag] is a BCP-47 tag, "" = device default. */
    fun start(text: String, langTag: String) {
        phrase = text.ifBlank { DEFAULT_TEXT }
        locale = if (langTag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(langTag)
        active = true
        val existing = tts
        if (existing == null) {
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    Log.w(TAG, "TTS unavailable ($status) — announcement skipped")
                    return@TextToSpeech
                }
                tts?.setAudioAttributes(attrs)
                tts?.setSpeechRate(SPEECH_RATE) // a bit faster = more urgent
                tts?.setOnUtteranceProgressListener(progress)
                applyLanguage()
                if (active) handler.post { speakOnce() }
            }
        } else if (ready) {
            applyLanguage()
            speakOnce()
        }
    }

    /** Stop announcing (idempotent). */
    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        runCatching { tts?.stop() }
        onSpeaking(false)
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun applyLanguage() {
        val res = tts?.setLanguage(locale) ?: return
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language '$locale' unavailable; falling back to English")
            tts?.setLanguage(Locale.ENGLISH)
        }
    }

    private fun speakOnce() {
        if (!active || !ready) return
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, UTTER_ID)
    }

    private fun reschedule() {
        if (active) handler.postDelayed({ speakOnce() }, GAP_MS)
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            handler.post { if (active) onSpeaking(true) }
        }

        override fun onDone(utteranceId: String?) {
            handler.post { onSpeaking(false); reschedule() }
        }

        @Deprecated("Deprecated in API level 21")
        override fun onError(utteranceId: String?) {
            handler.post { onSpeaking(false); reschedule() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handler.post { onSpeaking(false); reschedule() }
        }
    }

    companion object {
        private const val TAG = "Announcer"
        const val DEFAULT_TEXT = "This phone has been stolen."
        private const val UTTER_ID = "guard_alarm"
        private const val GAP_MS = 2500L // silence between repeats (siren fills it)
        private const val SPEECH_RATE = 1.25f // 1.0 = normal; faster reads as urgent
    }
}
