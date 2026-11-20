package com.novalive.voicemail.telecom

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.util.Log
import java.util.Locale

/**
 * NovaAutoAnswerHelper
 * 
 * Automatically answers unanswered incoming calls on the "last ring" (configurable timeout)
 * and uses native TextToSpeech to speak:
 * "Hello! Udhaya is busy right now. Please call back later, or leave your message after the tone."
 */
class NovaAutoAnswerHelper(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "NovaAutoAnswer"
        private const val UTTERANCE_ID = "nova_busy_announcement"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    var isAutoAnswerEnabled = true
    var ringTimeoutSeconds = 15 // Default 15 seconds (last ring)
    var busyMessage = "Hello! Udhaya is busy right now. Please call back later, or leave your message after the tone."

    init {
        mainHandler.post {
            try {
                tts = TextToSpeech(context.applicationContext, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating TextToSpeech", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "US English voice data missing or not supported, using default locale")
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(0.95f)
            tts?.setPitch(1.0f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
            }

            isTtsReady = true
            Log.i(TAG, "Nova TTS auto-announcer initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with code: $status")
        }
    }

    /**
     * Called when an incoming call starts ringing. Schedules auto-answer on the last ring.
     */
    fun onCallRinging(phoneNumber: String, onAnswered: () -> Unit) {
        cancelPendingAutoAnswer()

        if (!isAutoAnswerEnabled) {
            Log.d(TAG, "Auto-answer disabled by user settings")
            return
        }

        Log.i(TAG, "Call from $phoneNumber is ringing. Scheduling auto-answer in $ringTimeoutSeconds seconds (last ring)...")

        pendingRunnable = Runnable {
            Log.i(TAG, "Ring timeout ($ringTimeoutSeconds s) reached! Auto-answering call...")
            val success = answerCallNow()
            onAnswered.invoke()

            // Speak the busy announcement
            mainHandler.postDelayed({
                speakAnnouncement(busyMessage) {
                    Log.i(TAG, "Announcement finished")
                }
            }, 600)
        }

        mainHandler.postDelayed(pendingRunnable!!, ringTimeoutSeconds * 1000L)
    }

    fun cancelPendingAutoAnswer() {
        pendingRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingRunnable = null
            Log.d(TAG, "Cancelled pending auto-answer timer")
        }
    }

    fun answerCallNow(): Boolean {
        try {
            // 1. If NovaInCallService has active call
            if (NovaInCallService.activeCall != null) {
                NovaInCallService.activeCall?.answer(0)
                Log.i(TAG, "Answered call via NovaInCallService")
                return true
            }

            // 2. TelecomManager acceptRingingCall
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                tm?.acceptRingingCall()
                Log.i(TAG, "Answered call via TelecomManager.acceptRingingCall()")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception answering call", e)
        }
        return false
    }

    fun speakAnnouncement(text: String, onComplete: (() -> Unit)? = null) {
        val speechText = if (text.isNotBlank()) text else busyMessage

        mainHandler.post {
            try {
                if (isTtsReady && tts != null) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "AI voice started speaking: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "AI voice finished speaking")
                            mainHandler.post { onComplete?.invoke() }
                        }

                        override fun onError(utteranceId: String?) {
                            Log.w(TAG, "AI voice TTS error")
                            mainHandler.post { onComplete?.invoke() }
                        }
                    })

                    tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                } else {
                    Log.w(TAG, "TTS not ready yet, re-initializing")
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error speaking announcement", e)
                onComplete?.invoke()
            }
        }
    }

    fun shutdown() {
        cancelPendingAutoAnswer()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
