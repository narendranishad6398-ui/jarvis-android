package com.stark.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis Service initialized.")

        // 1. Acquire Partial WakeLock to keep CPU running with screen off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::BackgroundAudioLock")
        wakeLock?.acquire()

        // 2. Initialize Text-to-Speech
        tts = TextToSpeech(this, this)

        // 3. Start Foreground Notification
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Monitoring audio for 'Jarvis'..."))

        // 4. Setup Speech Recognizer
        setupSpeechRecognizer()
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep service alive (restart automatically if system kills it)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Background Listener",
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
        val openIntent = Intent(this, MainActivity::class.java)
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

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
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
                    // Auto-restart listening loop seamlessly on silence, timeout, or network pause
                    restartListeningWithDelay()
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0].lowercase(Locale.ROOT)
                        Log.d(TAG, "Recognized: $spokenText")
                        processRecognizedText(spokenText)
                    } else {
                        restartListeningWithDelay()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        }
    }

    private fun startListeningLoop() {
        try {
            if (!isListening && speechRecognizer != null && recognizerIntent != null) {
                speechRecognizer?.startListening(recognizerIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speech start error: ${e.message}")
            restartListeningWithDelay()
        }
    }

    private fun restartListeningWithDelay() {
        android.os.Handler(mainLooper).postDelayed({
            startListeningLoop()
        }, 800)
    }

    private fun processRecognizedText(text: String) {
        val wakeWords = listOf("jarvis", "hey jarvis", "jarwish", "hey jarwish", "ok jarvis")
        val isWakeWordTriggered = wakeWords.any { text.contains(it) }

        if (isWakeWordTriggered) {
            triggerWakeAlert()
            wakeUpScreenAndBringToFront()

            // Remove wake word prefix to get command
            var command = text
            for (w in wakeWords) {
                if (command.contains(w)) {
                    command = command.substringAfter(w).trim()
                    break
                }
            }

            if (command.isEmpty()) {
                speak("At your service, Sir. What are your orders?")
            } else {
                executeDirective(command)
            }
        } else {
            // Keep listening for wake word
            restartListeningWithDelay()
        }
    }

    private fun triggerWakeAlert() {
        // Haptic feedback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(150)
            }
        } catch (e: Exception) {}
    }

    private fun wakeUpScreenAndBringToFront() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "JARVIS::WakeScreen"
            )
            screenLock.acquire(3000)

            val activityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(activityIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Screen wake error: ${e.message}")
        }
    }

    private fun executeDirective(cmd: String) {
        val command = cmd.lowercase(Locale.ROOT)
        updateNotification("Processing: $command")

        when {
            // 1. App Launching
            command.contains("open whatsapp") -> {
                launchPackage("com.whatsapp", "WhatsApp")
            }
            command.contains("open youtube") -> {
                launchPackage("com.google.android.youtube", "YouTube")
            }
            command.contains("open instagram") -> {
                launchPackage("com.instagram.android", "Instagram")
            }
            command.contains("open camera") -> {
                val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(cameraIntent)
                speak("Opening camera, Sir.")
            }
            command.contains("open spotify") -> {
                launchPackage("com.spotify.music", "Spotify")
            }
            command.contains("open settings") -> {
                val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(settingsIntent)
                speak("Opening settings, Sir.")
            }

            // 2. Flashlight Control
            command.contains("flashlight on") || command.contains("torch on") -> {
                toggleTorch(true)
                speak("Flashlight activated, Sir.")
            }
            command.contains("flashlight off") || command.contains("torch off") -> {
                toggleTorch(false)
                speak("Flashlight disabled, Sir.")
            }

            // 3. Time & Date
            command.contains("time") -> {
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                speak("It is currently $timeStr, Sir.")
            }
            command.contains("date") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(Date())
                speak("Today is $dateStr, Sir.")
            }

            // 4. Volume Control
            command.contains("volume up") -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                speak("Volume increased, Sir.")
            }
            command.contains("volume down") -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                speak("Volume lowered, Sir.")
            }

            // 5. Default
            else -> {
                speak("Command received for $command, Sir.")
            }
        }

        // Resume listening loop after speech finishes
        restartListeningWithDelay()
    }

    private fun launchPackage(packageName: String, appName: String) {
        val pm: PackageManager = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            speak("Launching $appName now, Sir.")
        } else {
            speak("I could not find $appName installed on this device, Sir.")
        }
    }

    private fun toggleTorch(enable: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Torch error: ${e.message}")
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_VOICE")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            // Prefer UK English for Jarvis voice
            tts?.language = Locale.UK
            tts?.setPitch(0.95f)
            tts?.setSpeechRate(1.0f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
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
