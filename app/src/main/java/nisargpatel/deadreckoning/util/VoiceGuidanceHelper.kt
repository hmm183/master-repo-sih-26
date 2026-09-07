package nisargpatel.deadreckoning.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Text-to-Speech (TTS) Voice Guidance engine for live turn-by-turn navigation.
 * Announces upcoming maneuvers and street changes with smart deduplication.
 */
class VoiceGuidanceHelper(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var lastSpokenInstruction: String = ""
    private var lastSpokenTimeMs: Long = 0L

    var isMuted: Boolean = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(1.02f)
            tts?.setPitch(1.0f)
            isInitialized = true
            Log.i("VoiceGuidance", "TTS engine successfully initialized")
        } else {
            Log.w("VoiceGuidance", "Failed to initialize TTS: status=$status")
        }
    }

    fun speak(instruction: String, isPriority: Boolean = false) {
        if (isMuted || !isInitialized || instruction.isBlank()) return

        val clean = instruction.trim()
        val now = System.currentTimeMillis()

        // Deduplicate identical instruction spoken recently unless priority
        if (!isPriority && clean.equals(lastSpokenInstruction, ignoreCase = true) && (now - lastSpokenTimeMs < 12_000L)) {
            return
        }

        lastSpokenInstruction = clean
        lastSpokenTimeMs = now

        try {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "NavVoice_${System.currentTimeMillis()}")
            Log.d("VoiceGuidance", "Speaking: \"$clean\"")
        } catch (e: Throwable) {
            Log.w("VoiceGuidance", "TTS speak error: ${e.message}")
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Throwable) {
            // Ignored
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Throwable) {
            // Ignored
        }
    }
}
