package com.stark.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
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
import android.speech.tts.Voice
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * 24/7 Silent Background Ear & Wake-Word Engine for JARVIS.
 * - Zero "Too-Too" mic beeping (mutes system & notification stream during recognizer setup).
 * - Media & App audio immunity (completely ignores speech from playing YouTube, Reels, or songs).
 * - Custom Wake-Word & Custom Screen Lock/Unlock voice commands.
 * - Instant in-app camera shutter & settings toggle without popping intrusive overlay.
 * - Deep masculine Iron Man voice tone.
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    private val TAG = "JarvisService"
    private val CHANNEL_ID = "JarvisWakeWordChannel"
    private val NOTIFICATION_ID = 1001

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var isListening = false
    private var isDestroyed = false
    private var isSpeaking = false
    private var isOverlayActive = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Initializing JARVIS background ear...")

        startForeground(NOTIFICATION_ID, buildForegroundNotification("Active | Hands-Free Ear Online"))

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.stark.jarvis:JarvisWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JarvisWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock error: ${e.message}")
        }

        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val speakMsg = intent.getStringExtra("SPEAK_TEXT")
            if (!speakMsg.isNullOrEmpty()) {
                speakTextOutLoud(speakMsg)
            }

            if (intent.getBooleanExtra("STOP_EAR", false)) {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }

            if (intent.getBooleanExtra("NOTIFY_OVERLAY_CLOSED", false)) {
                isOverlayActive = false
                restartListeningLoop(1200)
            }
        }

        if (!isListening && !isSpeaking && !isOverlayActive) {
            startListening()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.ENGLISH
            }

            tts?.setPitch(0.84f)
            tts?.setSpeechRate(1.00f)

            try {
                val voices = tts?.voices
                if (voices != null) {
                    var selectedVoice: Voice? = null
                    for (v in voices) {
                        val name = v.name.lowercase(Locale.ROOT)
                        if (name.contains("en-in") || name.contains("hi-in") || name.contains("male") || name.contains("cmn")) {
                            if (!name.contains("female") && !name.contains("girl")) {
                                selectedVoice = v
                                break
                            }
                        }
                    }
                    if (selectedVoice != null) {
                        tts?.voice = selectedVoice
                    }
                }
            } catch (ignored: Exception) {}

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    stopListening()
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    restartListeningLoop(1000)
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    restartListeningLoop(1000)
                }
            })

            isTtsReady = true
        }
    }

    private fun speakTextOutLoud(text: String) {
        if (isDestroyed) return
        stopListening()
        mainHandler.post {
            if (isTtsReady && tts != null) {
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "JARVIS_VOICE_" + System.currentTimeMillis())
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_VOICE_" + System.currentTimeMillis())
            }
        }
    }

    /**
     * Mutes recognizer startup chime sound to eliminate continuous "too-too" mic beeps.
     */
    private fun muteRecognizerBeep(mute: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mute) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (e: Exception) {}
    }

    private fun setupSpeechRecognizer() {
        mainHandler.post {
            try {
                if (speechRecognizer != null) {
                    try { speechRecognizer?.destroy() } catch (ignored: Exception) {}
                    speechRecognizer = null
                }

                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            mainHandler.postDelayed({ muteRecognizerBeep(false) }, 250)
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                        }
                        override fun onError(error: Int) {
                            isListening = false
                            // Exponential delay between idle timeouts to prevent rapid beeping loops
                            val delayMs = if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) 1500L else 1200L
                            restartListeningLoop(delayMs)
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                handleHeardSpeech(matches)
                            } else {
                                restartListeningLoop(1200)
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "SpeechRecognizer setup error: ${e.message}")
            }
        }
    }

    private fun startListening() {
        if (isDestroyed || isSpeaking || isOverlayActive) return

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    setupSpeechRecognizer()
                }

                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val prefs = getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
                val silentEar = prefs.getBoolean("SILENT_EAR_MODE", true)

                if (silentEar) {
                    muteRecognizerBeep(true)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, arrayOf("hi-IN", "en-US"))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Start listening failed: ${e.message}")
                restartListeningLoop(1500)
            }
        }
    }

    private fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
                muteRecognizerBeep(false)
            } catch (ignored: Exception) {}
        }
    }

    private fun restartListeningLoop(delayMillis: Long) {
        if (isDestroyed || isSpeaking || isOverlayActive) return

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isDestroyed && !isSpeaking && !isOverlayActive && !isListening) {
                startListening()
            }
        }, delayMillis)
    }

    /**
     * Speech processing with:
     * - Media & YouTube Audio Immunity: Rejects speech playing from running videos.
     * - Custom Voice Unlock & Lock Engine.
     * - Custom Wake-Word Engine.
     * - Direct execution for in-app camera shutter, settings, and lock/unlock.
     */
    private fun handleHeardSpeech(matches: ArrayList<String>) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isMusicActive = audioManager.isMusicActive
        val prefs = getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)

        val customWake = prefs.getString("CUSTOM_WAKE_WORD", "")?.trim()?.lowercase(Locale.ROOT) ?: ""
        val customUnlockPhrase = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim")?.trim()?.lowercase(Locale.ROOT) ?: ""
        val customLockPhrase = prefs.getString("CUSTOM_LOCK_PHRASE", "phone lock karo")?.trim()?.lowercase(Locale.ROOT) ?: ""

        val allowedPrefixes = mutableListOf(
            "hey jarvis", "hello jarvis", "hi jarvis", "suno jarvis", "ok jarvis",
            "hey jarvish", "hello jarvish", "hi jarvish", "suno jarvish",
            "hey stark", "hello stark", "hi stark", "suno stark",
            "hey computer", "hello computer", "suno computer",
            "hey assistant", "hello assistant", "suno assistant",
            "\u0939\u0947 \u091c\u093e\u0930\u094d\u0935\u093f\u0938", "\u0938\u0941\u0928\u094b \u091c\u093e\u0930\u094d\u0935\u093f\u0938",
            "\u091c\u093e\u0930\u094d\u0935\u093f\u0938", "\u091c\u093e\u0930\u094d\u0935\u093f\u0936"
        )
        if (customWake.isNotEmpty()) {
            allowedPrefixes.add(0, customWake)
        }

        val allowedSingleWords = listOf(
            "jarvis", "jarvish", "stark", "computer", "assistant",
            "\u091c\u093e\u0930\u094d\u0935\u093f\u0938", "\u091c\u093e\u0930\u094d\u0935\u093f\u0936"
        )

        for (candidate in matches) {
            val lower = candidate.lowercase(Locale.ROOT).trim()
            val words = lower.split("\\s+".toRegex())

            // 1. Custom Voice Unlock
            if ((customUnlockPhrase.isNotEmpty() && lower.contains(customUnlockPhrase)) ||
                lower.contains("khul ja sim sim") || lower.contains("open sesame") ||
                lower.contains("screen unlock karo") || lower.contains("phone unlock karo")) {
                Log.d(TAG, "Unlock directive matched: '$lower'")
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "com.stark.jarvis:UnlockWakeLock"
                )
                wl.acquire(6000)

                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.unlockWithSwipeGesture { success ->
                            speakTextOutLoud(if (success) "Voice password verified, Boss! Screen unlock kar di hai." else "Voice password verified Boss.")
                        }
                    } else {
                        speakTextOutLoud("Voice password verified Boss. Accessibility enable karein swipe unlock ke liye.")
                    }
                }, 450)
                return
            }

            // 2. Custom Voice Lock
            if ((customLockPhrase.isNotEmpty() && lower.contains(customLockPhrase)) ||
                lower.contains("phone lock") || lower.contains("screen lock") ||
                lower.contains("lock karo") || lower.contains("screen band karo") ||
                lower.contains("phone band karo")) {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.lockPhoneScreen()) {
                    speakTextOutLoud("Ji Boss, phone lock kar diya hai.")
                } else {
                    speakTextOutLoud("Phone lock karne ke liye Accessibility enable karein Boss.")
                }
                return
            }

            // 3. Media Immunity: If YouTube/song/video audio is active, IGNORE unless starts with wake word
            if (isMusicActive) {
                var startsWithWake = false
                var matchedPrefix = ""
                for (p in allowedPrefixes) {
                    if (lower.startsWith(p)) {
                        startsWithWake = true
                        matchedPrefix = p
                        break
                    }
                }
                if (startsWithWake) {
                    val cmd = lower.substringAfter(matchedPrefix).trim()
                    triggerWakeAlert()
                    dispatchDirectiveFast(cmd)
                    return
                }
                continue
            }

            // 4. Normal Mode: Check Wake Word
            var detectedWake: String? = null
            for (p in allowedPrefixes) {
                if (lower.contains(p)) {
                    detectedWake = p
                    break
                }
            }

            if (detectedWake == null) {
                for (sw in allowedSingleWords) {
                    if (words.contains(sw)) {
                        detectedWake = sw
                        break
                    }
                }
            }

            if (detectedWake != null) {
                val command = lower.substringAfter(detectedWake).trim()
                triggerWakeAlert()
                dispatchDirectiveFast(command)
                return
            }
        }

        restartListeningLoop(1200)
    }

    /**
     * Executes fast commands (Camera Shutter, Video, In-app actions, Settings, Lock) directly
     * in the background so the underlying active app is NEVER covered by an overlay!
     */
    private fun dispatchDirectiveFast(command: String) {
        val lower = command.lowercase(Locale.ROOT)
        val isFastDirectAction = lower.contains("photo") || lower.contains("video") ||
                lower.contains("type ") || lower.contains("likho ") ||
                lower.contains("click ") || lower.contains("dabao ") ||
                lower.contains("scroll") || lower.contains("torch") ||
                lower.contains("batti") || lower.contains("wifi") ||
                lower.contains("hotspot") || lower.contains("bluetooth") ||
                lower.contains("net ") || lower.contains("data ")

        if (isFastDirectAction && command.isNotEmpty()) {
            stopListening()
            CommandExecutor.execute(this, command) { reply, _ ->
                speakTextOutLoud(reply)
            }
        } else {
            wakeUpAssistantOverlay(command)
        }
    }

    private fun triggerWakeAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 80, 40, 90), intArrayOf(0, 180, 0, 240), -1)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 40, 90), intArrayOf(0, 180, 0, 240), -1))
            }
        } catch (e: Exception) {}
    }

    private fun wakeUpAssistantOverlay(initialCommand: String = "") {
        isOverlayActive = true
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                muteRecognizerBeep(false)
            } catch (ignored: Exception) {}

            try {
                val intent = Intent(this, AssistantOverlayActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    if (initialCommand.isNotEmpty()) {
                        putExtra("INITIAL_DIRECTIVE", initialCommand)
                    }
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch overlay: ${e.message}")
                isOverlayActive = false
                restartListeningLoop(1200)
            }
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Ear Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent hands-free wake word listener"
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS AI - Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed = true
        stopListening()
        muteRecognizerBeep(false)

        try { speechRecognizer?.destroy() } catch (ignored: Exception) {}
        speechRecognizer = null

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (ignored: Exception) {}
        tts = null

        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }
}
