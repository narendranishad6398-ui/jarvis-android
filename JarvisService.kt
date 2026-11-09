package com.stark.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class JarvisService : Service(), TextToSpeech.OnInitListener {

    private val TAG = "JarvisService"
    private val CHANNEL_ID = "JarvisForegroundChannel"
    private val NOTIFICATION_ID = 1001

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isListening = false
    private var isTtsReady = false
    private var isSpeaking = false

    private var audioManager: AudioManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis Service initialized.")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::BackgroundAudioLock")
        wakeLock?.acquire()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)

        setupPhoneStateListener()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Listening for 'Hey Jarvis'..."))

        setupSpeechRecognizer()
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forceListen = intent?.getBooleanExtra("FORCE_LISTEN", false) ?: false
        if (forceListen) {
            setupSpeechRecognizer()
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
                        stopListening()
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                        restartListeningWithDelay()
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
                            restartListeningWithDelay(1000)
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val spokenText = matches[0].lowercase(Locale.ROOT)
                                Log.d(TAG, "Background Recognized: $spokenText")
                                processRecognizedText(spokenText)
                            } else {
                                restartListeningWithDelay(500)
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
                if (!isSpeaking && !isListening && speechRecognizer != null && recognizerIntent != null) {
                    speechRecognizer?.startListening(recognizerIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speech start error: ${e.message}")
                restartListeningWithDelay()
            }
        }
    }

    private fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
            } catch (e: Exception) {}
        }
    }

    private fun restartListeningWithDelay(delayMs: Long = 800) {
        mainHandler.postDelayed({
            if (!isSpeaking) {
                setupSpeechRecognizer()
                startListeningLoop()
            }
        }, delayMs)
    }

    private fun processRecognizedText(text: String) {
        val wakeWords = listOf("jarvis", "hey jarvis", "jarwish", "hey jarwish", "ok jarvis", "जार्विस", "हे जार्विस", "suno jarvis", "सुनो जार्विस")
        val isWakeWordTriggered = wakeWords.any { text.contains(it) }

        if (isWakeWordTriggered) {
            triggerWakeAlert()

            var command = text
            for (w in wakeWords) {
                if (command.contains(w)) {
                    command = command.substringAfter(w).trim()
                    break
                }
            }

            wakeUpAssistantOverlay(command)
        } else {
            restartListeningWithDelay(400)
        }
    }

    private fun triggerWakeAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(180)
            }
        } catch (e: Exception) {}
    }

    private fun wakeUpAssistantOverlay(initialCommand: String) {
        try {
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

            val pendingIntent = PendingIntent.getActivity(
                this, 101, overlayIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val wakeNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JARVIS Protocol")
                .setContentText("At your service, Sir.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(2002, wakeNotification)
            mainHandler.postDelayed({ manager.cancel(2002) }, 4000)

        } catch (e: Exception) {
            Log.e(TAG, "Screen wake error: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            val langResult = tts?.setLanguage(Locale("hi", "IN"))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("en", "IN"))
            }
            tts?.setPitch(0.95f)
            tts?.setSpeechRate(1.0f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        isSpeaking = false
        try { telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE) } catch (e: Exception) {}
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        Log.d(TAG, "Jarvis Service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
