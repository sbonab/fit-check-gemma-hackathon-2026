package dev.local.outfitroast

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Uses only an installed, embedded English voice. Never falls back to a network voice. */
class OfflineSpeaker(context: Context, private val onStatus: (String) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var available = false
    private var closed = false
    private var completion: ((String?) -> Unit)? = null
    private var started: (() -> Unit)? = null
    private var utterance: String? = null
    private var timeout: Runnable? = null
    var description = "Checking offline voice…"
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            ui.post {
                if (!closed) {
                    runCatching {
                        check(result == TextToSpeech.SUCCESS) { "TTS engine unavailable" }
                        val engine = checkNotNull(tts)
                        val voice = engine.voices.orEmpty()
                            .filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language &&
                                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == Locale.US }.thenBy { it.name })
                            .firstOrNull() ?: error("Install an offline English voice in Android speech settings")
                        check(engine.setVoice(voice) == TextToSpeech.SUCCESS) { "Offline voice could not be selected" }
                        check(engine.voice?.name == voice.name && engine.voice?.isNetworkConnectionRequired == false) { "Engine rejected offline voice" }
                        engine.setSpeechRate(1.0f)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                ui.post { if (utteranceId == utterance) started?.invoke() }
                            }
                            override fun onDone(utteranceId: String?) { finish(utteranceId, null) }
                            @Deprecated("Platform callback")
                            override fun onError(utteranceId: String?) { finish(utteranceId, "Offline speech failed") }
                            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, "Offline speech failed ($errorCode)") }
                        })
                        available = true
                        description = "Offline voice: ${voice.name}"
                    }.onFailure { available = false; description = "Text only · ${it.message}" }
                    onStatus(description)
                }
            }
        }
    }

    fun speak(text: String, onStart: () -> Unit, onComplete: (String?) -> Unit): Boolean {
        val engine = tts ?: return false
        if (!available || closed || engine.voice?.isNetworkConnectionRequired != false) return false
        stop()
        completion = onComplete
        started = onStart
        val id = "fit-check-${System.nanoTime()}"
        utterance = id
        val spoken = text.take(TextToSpeech.getMaxSpeechInputLength())
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        if (engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
            utterance = null; completion = null; started = null
            description = "Text only · Offline speech could not start"
            onStatus(description)
            return false
        }
        timeout = Runnable { engine.stop(); finish(id, "Offline speech timed out") }.also { ui.postDelayed(it, 90000) }
        return true
    }

    private fun finish(id: String?, error: String?) {
        ui.post {
            if (id != null && id == utterance) {
                utterance = null
                timeout?.let { ui.removeCallbacks(it) }; timeout = null
                val callback = completion; completion = null; started = null
                callback?.invoke(error)
            }
        }
    }

    fun stop() {
        utterance = null; completion = null; started = null
        timeout?.let { ui.removeCallbacks(it) }; timeout = null
        tts?.stop()
    }
    fun close() { closed = true; stop(); tts?.shutdown(); tts = null }
}
