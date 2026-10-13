package com.kenny.tellofollow.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListenMode { PUSH_TO_TALK, CONTINUOUS /* future: auto-restart after each result */ }

data class VoiceUiState(
    val available: Boolean = true,
    val listening: Boolean = false,
    val heard: String = "",
    val partial: Boolean = false,
    val action: String = "",      // "-> FOLLOW (0.92)" or "-> (no match)"
    val gateLine: String = "",    // "would do ... / blocked: ..."
    val error: String = "",
)

/**
 * Android SpeechRecognizer wrapper. Must be used on the main thread.
 * EXTRA_PREFER_OFFLINE: the phone sits on TELLO Wi-Fi with no internet.
 * Push-to-talk now: [startListening] on press, [stopListening] on release.
 * [mode] = CONTINUOUS is reserved for a later phase (restart in onResults/onError).
 */
class VoiceCommandEngine(
    private val context: Context,
    var mode: ListenMode = ListenMode.PUSH_TO_TALK,
    private val onAlternatives: (List<String>, FloatArray?) -> Unit,
) {
    private val _state = MutableStateFlow(VoiceUiState(available = SpeechRecognizer.isRecognitionAvailable(context)))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        }
        return recognizer
    }

    fun startListening() {
        val r = ensureRecognizer() ?: run {
            _state.value = _state.value.copy(available = false, error = "No speech recognizer on this phone")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        _state.value = _state.value.copy(listening = true, heard = "", partial = true, action = "", gateLine = "", error = "")
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.cancel()
        _state.value = _state.value.copy(listening = false)
    }

    fun showResult(heard: String, action: String, gateLine: String) {
        _state.value = _state.value.copy(heard = heard, partial = false, action = action, gateLine = gateLine)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            _state.value = _state.value.copy(heard = text, partial = true)
        }

        override fun onResults(results: Bundle?) {
            val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            _state.value = _state.value.copy(listening = false, partial = false, heard = alts.firstOrNull() ?: "")
            onAlternatives(alts, conf)
        }

        override fun onError(error: Int) {
            _state.value = _state.value.copy(listening = false, partial = false, error = errorText(error))
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> "Needs offline speech pack (Settings > Google > Voice > Offline)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission denied"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, try again"
        SpeechRecognizer.ERROR_AUDIO -> "Mic audio error"
        else -> "Speech error $code"
    }
}