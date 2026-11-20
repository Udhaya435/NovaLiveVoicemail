package com.novalive.voicemail.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.novalive.voicemail.MainActivity
import java.util.Locale

/**
 * NovaAutoAnswerService
 *
 * Dedicated Android ForegroundService with WakeLock to guarantee execution during live phone ringing:
 * 1. Acquires PARTIAL_WAKE_LOCK so CPU does not sleep when screen is off.
 * 2. Starts in foreground with high-priority call notification (prevents OEM killing by Realme UI/MIUI).
 * 3. Counts down 12-15 seconds (last ring).
 * 4. Automatically answers the phone call via TelecomManager / InCallService / HeadsetHook.
 * 5. Routes audio to caller & speaker uplink.
 * 6. Speaks: "Hello! Udhaya is busy right now. Please call back later, or leave your message after the tone."
 * 7. Automatically hangs up / terminates the call and releases WakeLock.
 */
class NovaAutoAnswerService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "NovaAutoAnswerService"
        const val CHANNEL_ID = "nova_auto_answer_channel"
        const val NOTIFICATION_ID = 4001
        const val WAKELOCK_TAG = "NovaLiveVoicemail:AutoAnswerWakeLock"

        const val ACTION_CALL_RINGING = "com.novalive.voicemail.ACTION_CALL_RINGING"
        const val ACTION_CALL_ANSWERED_MANUALLY = "com.novalive.voicemail.ACTION_CALL_ANSWERED_MANUALLY"
        const val ACTION_CALL_ENDED = "com.novalive.voicemail.ACTION_CALL_ENDED"

        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"

        private const val PREFS_NAME = "nova_auto_answer_prefs"
        private const val KEY_ENABLED = "auto_answer_enabled"
        private const val KEY_TIMEOUT = "ring_timeout_seconds"
        private const val KEY_OWNER = "owner_name"
        private const val KEY_MESSAGE = "busy_message"
        private const val UTTERANCE_ID = "nova_auto_answer_utterance"

        val DEFAULT_MESSAGE = "Hello! Udhaya is busy right now. Please call back later, or leave your message after the tone."

        fun startForRingingCall(context: Context, phoneNumber: String, displayName: String? = null) {
            val intent = Intent(context, NovaAutoAnswerService::class.java).apply {
                action = ACTION_CALL_RINGING
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_DISPLAY_NAME, displayName ?: phoneNumber)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NovaAutoAnswerService", e)
            }
        }

        fun notifyCallAnsweredManually(context: Context) {
            val intent = Intent(context, NovaAutoAnswerService::class.java).apply {
                action = ACTION_CALL_ANSWERED_MANUALLY
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send manual answer intent", e)
            }
        }

        fun notifyCallEnded(context: Context) {
            val intent = Intent(context, NovaAutoAnswerService::class.java).apply {
                action = ACTION_CALL_ENDED
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send call ended intent", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var currentPhoneNumber: String = "Unknown"
    private var isCallCurrentlyAnsweredByAi = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NovaAutoAnswerService created")
        acquireWakeLock()
        createNotificationChannel()
        initTts()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            )?.apply {
                setReferenceCounted(false)
                acquire(45000L) // Safety cap: 45 seconds
            }
            Log.i(TAG, "Acquired partial WakeLock for reliable last-ring timer")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Released WakeLock")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TTS", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.US)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(0.92f)
            tts?.setPitch(1.0f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
            }
            isTtsReady = true
            Log.i(TAG, "TTS initialized successfully in foreground service")
        } else {
            Log.e(TAG, "TTS initialization failed with code $status")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CALL_RINGING -> {
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Incoming Caller"
                val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: phoneNumber
                currentPhoneNumber = phoneNumber

                val notification = buildServiceNotification(
                    "📞 Incoming Call from $displayName",
                    "Nova Voicemail active. Answering on last ring if not picked up."
                )
                startForeground(NOTIFICATION_ID, notification)

                startAutoAnswerCountdown()
            }
            ACTION_CALL_ANSWERED_MANUALLY -> {
                Log.i(TAG, "Call answered manually by user; cancelling auto-answer")
                cleanUpAndStop()
            }
            ACTION_CALL_ENDED -> {
                Log.i(TAG, "Call ended / caller disconnected; cancelling auto-answer")
                cleanUpAndStop()
            }
        }

        return START_NOT_STICKY
    }

    private fun startAutoAnswerCountdown() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_ENABLED, true)
        val timeoutSec = prefs.getInt(KEY_TIMEOUT, 15) // Default 15s (recommended last ring)
        val busyMsg = prefs.getString(KEY_MESSAGE, DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE

        if (!isEnabled) {
            Log.d(TAG, "Auto-Answer is disabled in settings; stopping service")
            cleanUpAndStop()
            return
        }

        cancelCountdown()

        Log.i(TAG, "Starting auto-answer countdown for $timeoutSec seconds...")

        countdownRunnable = Runnable {
            Log.i(TAG, "Last ring reached ($timeoutSec s)! Automatically answering call now...")
            executeAutoAnswerAndSpeak(busyMsg)
        }

        mainHandler.postDelayed(countdownRunnable!!, timeoutSec * 1000L)
    }

    private fun executeAutoAnswerAndSpeak(message: String) {
        isCallCurrentlyAnsweredByAi = true

        // 1. Answer the live cellular call
        val answered = answerCall()
        Log.i(TAG, "Call answer executed (result: $answered)")

        // Update notification
        val notification = buildServiceNotification(
            "🤖 Nova Live Voicemail Speaking",
            "Answering call: \"$message\""
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        // 2. Wait 800ms for connection to stabilize, route audio, and speak
        mainHandler.postDelayed({
            enableSpeakerAndAudioRouting()

            speakBusyMessage(message) {
                Log.i(TAG, "AI voice announcement completed! Ending call now...")
                // Wait 2.0s to let caller hear final tone, then hang up call
                mainHandler.postDelayed({
                    endCall()
                    Log.i(TAG, "Call successfully ended! Stopping foreground service.")
                    cleanUpAndStop()
                }, 2000L)
            }
        }, 800L)
    }

    private fun answerCall(): Boolean {
        // Strategy 1: If NovaInCallService has active call
        try {
            if (NovaInCallService.activeCall != null) {
                NovaInCallService.activeCall?.answer(0)
                Log.i(TAG, "Answered via NovaInCallService")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "NovaInCallService.answer failed", e)
        }

        // Strategy 2: TelecomManager acceptRingingCall (Android 8.0+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                tm?.acceptRingingCall()
                Log.i(TAG, "Answered via TelecomManager.acceptRingingCall()")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager.acceptRingingCall failed", e)
        }

        // Strategy 3: Media button hook fallback (universal Bluetooth/wired headset emulation)
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK)
            audioManager?.dispatchMediaKeyEvent(down)
            audioManager?.dispatchMediaKeyEvent(up)
            Log.i(TAG, "Answered via KEYCODE_HEADSETHOOK fallback")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Headset hook fallback failed", e)
        }

        return false
    }

    private fun endCall(): Boolean {
        // Strategy 1: NovaInCallService
        try {
            if (NovaInCallService.activeCall != null) {
                NovaInCallService.activeCall?.disconnect()
                Log.i(TAG, "Disconnected via NovaInCallService")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "NovaInCallService.disconnect failed", e)
        }

        // Strategy 2: TelecomManager.endCall (Android 9+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val ended = tm?.endCall() ?: false
                Log.i(TAG, "Disconnected via TelecomManager.endCall(): $ended")
                return ended
            }
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager.endCall failed", e)
        }

        // Strategy 3: Headset hook fallback
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK)
            audioManager?.dispatchMediaKeyEvent(down)
            audioManager?.dispatchMediaKeyEvent(up)
            Log.i(TAG, "Disconnected via KEYCODE_HEADSETHOOK fallback")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Headset hook end failed", e)
        }

        return false
    }

    private fun enableSpeakerAndAudioRouting() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true

            // Set voice call volume high so caller and receiver hear clearly
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.9).toInt(), 0)
            Log.i(TAG, "Audio routed to speakerphone & voice call stream")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AudioManager for in-call speaker", e)
        }
    }

    private fun speakBusyMessage(text: String, onDone: () -> Unit) {
        mainHandler.post {
            try {
                if (isTtsReady && tts != null) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.i(TAG, "TTS onStart: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.i(TAG, "TTS onDone: $utteranceId")
                            mainHandler.post { onDone() }
                        }

                        override fun onError(utteranceId: String?) {
                            Log.w(TAG, "TTS onError: $utteranceId")
                            mainHandler.post { onDone() }
                        }
                    })

                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }

                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
                } else {
                    Log.w(TAG, "TTS not ready when trying to speak busy message")
                    // Fallback delay to avoid hanging call instantly
                    mainHandler.postDelayed({ onDone() }, 3000L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during TTS speak", e)
                onDone()
            }
        }
    }

    private fun cancelCountdown() {
        countdownRunnable?.let {
            mainHandler.removeCallbacks(it)
            countdownRunnable = null
        }
    }

    private fun cleanUpAndStop() {
        cancelCountdown()
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NovaAutoAnswerService destroyed")
        cleanUpAndStop()
        try {
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nova Auto-Answer Voicemail Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Monitors live incoming ringing calls to answer on last ring and announce busy message"
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildServiceNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
