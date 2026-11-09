package com.demirajvazi.ultratalkingpulse

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityManager
import java.util.Locale

/**
 * Speaks short messages to the user.
 *
 * - When a screen reader (TalkBack) is running, messages go through the screen reader,
 *   so they use the user's own voice and speed and never talk over it.
 * - Otherwise the app's own TextToSpeech voice is used, so sighted users without a
 *   screen reader hear the same guidance.
 *
 * It also plays short tones and vibrations ("cues") that confirm a good position and a
 * finished measurement.
 */
class Announcer(private val context: Context) : TextToSpeech.OnInitListener {

    var speechEnabled = true
    var cuesEnabled = true

    /** Rough time until the last message has finished being spoken. */
    var busyUntil = 0L
        private set

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false
    private val tone: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        chooseVoiceLanguage()
    }

    private fun chooseVoiceLanguage() {
        val engine = tts ?: return
        val appLocale = context.resources.configuration.locales[0]
        val candidates = if (appLocale.language == "sr") {
            // Serbian voices are not installed everywhere; Croatian and Bosnian voices
            // read Serbian Latin text well, so they are used as fallbacks.
            listOf("sr-Latn-RS", "sr-RS", "sr", "hr-HR", "bs-BA").map(Locale::forLanguageTag)
        } else {
            listOf(appLocale, Locale.US)
        }
        for (locale in candidates) {
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = locale
                return
            }
        }
    }

    fun isScreenReaderOn(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    /** Speaks [text], replacing anything the app itself is still saying. */
    @Suppress("DEPRECATION") // announceForAccessibility still works and is the simplest way to reach TalkBack.
    fun say(text: String, anchor: View) {
        busyUntil = SystemClock.elapsedRealtime() + 500L + text.length * 65L
        if (!speechEnabled) return
        if (isScreenReaderOn()) {
            anchor.announceForAccessibility(text)
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ultra-talking-pulse")
        }
    }

    /** Short rising tone and a light vibration: "position is good". */
    fun cueGood(anchor: View) {
        if (!cuesEnabled) return
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        vibrate(anchor)
    }

    /** Double beep and a vibration: "measurement finished". */
    fun cueDone(anchor: View) {
        if (!cuesEnabled) return
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        vibrate(anchor)
    }

    private fun vibrate(anchor: View) {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        anchor.performHapticFeedback(effect)
    }

    fun stopSpeaking() {
        tts?.stop()
        busyUntil = 0L
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        tone?.release()
    }
}
