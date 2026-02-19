package com.agentrelay

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceCommandService(private val context: Context) {

    private enum class Phase { WAKE_WORD, TASK_CAPTURE }

    private var speechRecognizer: SpeechRecognizer? = null
    private var phase = Phase.WAKE_WORD
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null

    fun start() {
        if (isRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }
        isRunning = true
        phase = Phase.WAKE_WORD
        initToneGenerator()
        startListening()
        FloatingBubble.getInstance(context).setVoiceListening(true)
        Log.d(TAG, "Voice command service started")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping speech recognizer", e)
        }
        speechRecognizer = null
        releaseToneGenerator()
        FloatingBubble.getInstance(context).setVoiceListening(false)
        Log.d(TAG, "Voice command service stopped")
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create ToneGenerator", e)
        }
    }

    private fun releaseToneGenerator() {
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
    }

    private fun playConfirmTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (_: Exception) {}
    }

    private fun playErrorTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        } catch (_: Exception) {}
    }

    private fun startListening() {
        if (!isRunning) return

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createListener())
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (phase == Phase.WAKE_WORD) {
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            } else {
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Give user more time to speak the task
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Listening started in phase: $phase")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            scheduleRestart()
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech ($phase)")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech ($phase)")
        }

        override fun onError(error: Int) {
            val errorName = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_SERVER -> "SERVER"
                else -> "UNKNOWN($error)"
            }
            Log.d(TAG, "Recognition error: $errorName in phase $phase")

            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.e(TAG, "No audio permission — stopping voice service")
                stop()
                return
            }

            if (phase == Phase.TASK_CAPTURE) {
                // Failed to capture task — go back to wake word
                playErrorTone()
                phase = Phase.WAKE_WORD
            }

            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.d(TAG, "Results ($phase): $matches")

            when (phase) {
                Phase.WAKE_WORD -> {
                    if (matches != null && containsWakeWord(matches)) {
                        onWakeWordDetected()
                    } else {
                        scheduleRestart()
                    }
                }
                Phase.TASK_CAPTURE -> {
                    val task = matches?.firstOrNull()?.trim()
                    if (!task.isNullOrEmpty()) {
                        onTaskCaptured(task)
                    } else {
                        playErrorTone()
                        phase = Phase.WAKE_WORD
                        scheduleRestart()
                    }
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (phase != Phase.WAKE_WORD) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (partial != null && containsWakeWord(partial)) {
                onWakeWordDetected()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun containsWakeWord(results: List<String>): Boolean {
        return results.any { text ->
            val lower = text.lowercase()
            WAKE_PHRASES.any { phrase -> lower.contains(phrase) }
        }
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "Wake word detected!")
        playConfirmTone()
        phase = Phase.TASK_CAPTURE

        // Stop current recognition and restart in task capture mode
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}

        // Small delay to let the tone play
        handler.postDelayed({
            startListening()
        }, 300)
    }

    private fun onTaskCaptured(task: String) {
        Log.d(TAG, "Task captured: $task")

        // Filter out wake word remnants from the task
        var cleanTask = task
        WAKE_PHRASES.forEach { phrase ->
            cleanTask = cleanTask.lowercase().replace(phrase, "").trim()
        }
        if (cleanTask.isEmpty()) cleanTask = task

        // Go back to wake word listening
        phase = Phase.WAKE_WORD
        scheduleRestart()

        // Trigger the task via overlay
        handler.post {
            OverlayWindow.getInstance(context).startTaskFromVoice(cleanTask)
        }
    }

    private fun scheduleRestart() {
        if (!isRunning) return
        handler.postDelayed({
            if (isRunning) startListening()
        }, RESTART_DELAY_MS)
    }

    companion object {
        private const val TAG = "VoiceCommandService"
        private const val RESTART_DELAY_MS = 300L

        private val WAKE_PHRASES = listOf(
            "hey relay",
            "hey really",   // common misrecognition
            "a relay",      // common misrecognition
            "hey realy",
        )

        @Volatile
        private var instance: VoiceCommandService? = null

        fun getInstance(context: Context): VoiceCommandService {
            return instance ?: synchronized(this) {
                instance ?: VoiceCommandService(context.applicationContext).also { instance = it }
            }
        }
    }
}
