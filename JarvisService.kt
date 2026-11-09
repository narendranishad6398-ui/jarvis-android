package com.stark.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class JarvisService : Service() {

    private val TAG = "JarvisService"
    private val CHANNEL_ID = "JarvisForegroundChannel"
    private val NOTIFICATION_ID = 1001

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isListening = false
    @Volatile private var isOverlayActive = false
    @Volatile private var isPhoneCallActive = false

    // Strict wake word list: Only triggers when user explicitly calls JARVIS
    private val allowedWakeWords = listOf(
        "hey jarvis", "hello jarvis", "hi jarvis", "hii jarvis", "suno jarvis",
        "hey jarvish", "hello jarvish", "hi jarvish", "hii jarvish", "suno jarvish",
        "jarvis", "jarvish", "जार्विस", "हे जार्विस", "हेलो जार्विस", "हाय जार्विस"
    )

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.stark.jarvis.OVERLAY_DISMISSED") {
                Log.d(TAG, "Overlay dismissed. Resuming wake-word ear.")
                mainHandler.postDelayed({
                    isOverlayActive = false
                    restartListeningLoop(400)
                }, 500)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis Service initialized.")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::BackgroundAudioLock")
        try {
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire error: ${e.message}")
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Register overlay dismiss listener
        val filter = IntentFilter("com.stark.jarvis.OVERLAY_DISMISSED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayReceiver, filter)
        }

        setupPhoneStateListener()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Active: Listening ONLY for 'Hey Jarvis' / 'Jarvis'"))

        setupSpeechRecognizer()
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isListening && !isOverlayActive && !isPhoneCallActive) {
            startListeningLoop()
        }
        return START_STICKY
    }

    private fun setupPhoneStateListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    super.onCallStateChanged(state, phoneNumber)
                    if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                        isPhoneCallActive = true
                        stopListening()
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                        isPhoneCallActive = false
                        restartListeningLoop(1000)
                    }
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "Telephony listener error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS AI Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS active while screen is locked or off"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        val openIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
            putExtra("EXTRA_START_LISTENING", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Protocol Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupSpeechRecognizer() {
        mainHandler.post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                        }
                        override fun onError(error: Int) {
                            isListening = false
                            // On timeout or no speech, restart quietly without freezing
                            restartListeningLoop(350)
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                evaluateBackgroundSpeech(matches)
                            } else {
                                restartListeningLoop(300)
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US", "hi"))
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Setup recognizer error: ${e.message}")
            }
        }
    }

    private fun startListeningLoop() {
        mainHandler.post {
            try {
                if (isOverlayActive || isPhoneCallActive) return@post

                val hasAudioPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!hasAudioPerm) return@post

                if (speechRecognizer == null) {
                    setupSpeechRecognizer()
                }

                if (!isListening && speechRecognizer != null && recognizerIntent != null) {
                    speechRecognizer?.startListening(recognizerIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start listening error: ${e.message}")
                restartListeningLoop(600)
            }
        }
    }

    private fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.cancel()
            } catch (e: Exception) {}
        }
    }

    private fun restartListeningLoop(delayMs: Long = 300) {
        mainHandler.postDelayed({
            if (!isOverlayActive && !isPhoneCallActive) {
                startListeningLoop()
            }
        }, delayMs)
    }

    /**
     * Strict Wake-Word Verification
     * Prevents random voices, YouTube videos, other apps, or room chatter from triggering JARVIS.
     * ONLY awakens if user specifically said "Jarvis", "Hey Jarvis", "Hello Jarvis", "Hi Jarvis", "Jarvish", etc.
     */
    private fun evaluateBackgroundSpeech(matches: List<String>) {
        for (candidate in matches) {
            val lower = candidate.lowercase(Locale.ROOT).trim()

            for (wakeWord in allowedWakeWords) {
                if (lower.contains(wakeWord)) {
                    Log.d(TAG, "Valid Wake Word detected: '$wakeWord' in '$candidate'. Triggering JARVIS!")

                    // Extract attached directive (e.g. "hey jarvis papa ko call lagao" -> "papa ko call lagao")
                    val command = lower.substringAfter(wakeWord).trim()
                    triggerWakeAlert()
                    wakeUpAssistantOverlay(command)
                    return
                }
            }
        }

        // No wake word matched -> Completely IGNORE other audio and continue listening quietly!
        restartListeningLoop(250)
    }

    private fun triggerWakeAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), intArrayOf(0, 200, 0, 255), -1)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), intArrayOf(0, 200, 0, 255), -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(180)
            }
        } catch (e: Exception) {}
    }

    private fun wakeUpAssistantOverlay(initialCommand: String) {
        try {
            isOverlayActive = true
            stopListening()

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "JARVIS::WakeScreen"
            )
            screenLock.acquire(4000)

            val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
                if (initialCommand.isNotEmpty()) {
                    putExtra("EXTRA_USER_QUERY", initialCommand)
                    putExtra("EXTRA_START_LISTENING", false)
                } else {
                    putExtra("EXTRA_START_LISTENING", true)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(overlayIntent)

            // High-priority full-screen intent for locked screen
            val pendingIntent = PendingIntent.getActivity(
                this, 101, overlayIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val wakeNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JARVIS Protocol")
                .setContentText(if (initialCommand.isNotEmpty()) initialCommand else "Listening, Sir...")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(2002, wakeNotification)
            mainHandler.postDelayed({ manager.cancel(2002) }, 4000)

            // Safeguard reset after 12s in case overlay closed without broadcast
            mainHandler.postDelayed({
                if (isOverlayActive) {
                    isOverlayActive = false
                    restartListeningLoop(500)
                }
            }, 12000)

        } catch (e: Exception) {
            Log.e(TAG, "Screen wake error: ${e.message}")
            isOverlayActive = false
            restartListeningLoop(600)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        isOverlayActive = false
        try { telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE) } catch (e: Exception) {}
        try { unregisterReceiver(overlayReceiver) } catch (e: Exception) {}
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {}
        Log.d(TAG, "Jarvis Service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
