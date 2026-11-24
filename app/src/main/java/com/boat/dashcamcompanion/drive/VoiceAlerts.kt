package com.boat.dashcamcompanion.drive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Spoken alerts: "Pothole ahead" when a NEW pothole is detected (repeat sightings of the same
 * pothole are already filtered by the pothole log, plus a short cooldown so a cluster of
 * potholes gives one alert, not a burst).
 *
 * Uses Android's built-in Text-to-Speech, which works offline when the phone's voice data is
 * installed (Settings → Accessibility/Language → Text-to-speech). Other audio (music,
 * navigation) is lowered briefly while the alert plays. Works with the screen off.
 */
object VoiceAlerts : TextToSpeech.OnInitListener {
    private const val TAG = "VoiceAlerts"
    private const val PREFS = "voice_alerts"
    private const val COOLDOWN_MS = 4_000L
    /** After "Pothole in N metres", the camera's own alert for that pothole is skipped. */
    private const val AFTER_ADVANCE_MS = 20_000L

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: String? = null
    private var lastSpokenMs = 0L
    private var focusRequest: AudioFocusRequest? = null

    // ---------------------------------------------------------------- settings

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (on) say(context, "Voice alerts on")
    }

    /** 0.0 … 1.0, relative to the phone's media volume. */
    fun volume(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("volume", 1.0f)

    fun setVolume(context: Context, v: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("volume", v.coerceIn(0f, 1f)).apply()
    }

    // ---------------------------------------------------------------- alerts

    /**
     * Called when the camera sees a pothole for the first time on this drive (new, or known from
     * earlier drives). Skipped right after an advance warning ("Pothole in 100 metres") - the
     * driver has already been told about it.
     */
    @Synchronized
    fun onPotholeSeen(context: Context, large: Boolean) {
        if (!isEnabled(context) || !AlertSettings.alertsOn(context)) return
        val now = System.currentTimeMillis()
        if (now - lastAdvanceMs < AFTER_ADVANCE_MS) {
            lastAdvanceMs = 0L   // skip once
            return
        }
        if (now - lastSpokenMs < COOLDOWN_MS) return
        lastSpokenMs = now
        say(context, if (large) "Large pothole ahead" else "Pothole ahead")
    }

    private var lastAdvanceMs = 0L

    /** Advance warning for an already known pothole ahead, e.g. "Pothole in 100 metres". */
    @Synchronized
    fun sayAdvance(context: Context, metres: Int) {
        if (!isEnabled(context) || !AlertSettings.advanceOn(context)) return
        val now = System.currentTimeMillis()
        if (now - lastAdvanceMs < 3_000L) return
        lastAdvanceMs = now
        say(context, "Pothole in $metres metres")
    }

    /** Speaks [text] now (initialises Text-to-Speech on first use). */
    @Synchronized
    fun say(context: Context, text: String) {
        if (tts == null) {
            appContext = context.applicationContext
            tts = TextToSpeech(context.applicationContext, this)
        }
        if (!ready) {
            pending = text
            return
        }
        speakNow(text)
    }

    @Synchronized
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-Speech not available (status $status)")
            return
        }
        val lang = t.setLanguage(Locale.ENGLISH)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "English voice not installed - install it in the phone's Text-to-speech settings")
        }
        t.setAudioAttributes(attributes())
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = releaseFocus()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = releaseFocus()
        })
        ready = true
        pending?.let { speakNow(it) }
        pending = null
    }

    private fun speakNow(text: String) {
        val ctx = appContext ?: return
        requestFocus(ctx)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume(ctx)) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "pothole_${System.currentTimeMillis()}")
        Log.i(TAG, "spoke: $text")
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Lowers other audio (music, navigation) while the alert plays. */
    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes())
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        val ctx = appContext ?: return
        val req = focusRequest ?: return
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
        focusRequest = null
    }
}
