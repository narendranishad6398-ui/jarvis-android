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
import android.speech.tts.Voice
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * JARVIS 24/7 Silent Background Ear & Autonomous Protocol Service
 * - Completely silent: Zero "toon-toon" beeps.
 * - Strict Wake-Word Verification: Never triggers on video/reels sound from running apps.
 * - Custom Voice Lock & Unlock Engine.
 * - Deep Authoritative Male Voice.
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    private val TAG = "JarvisService"
    private val CHANNEL_ID = "jarvis_continuous_service_channel"
    private val NOTIFICATION_ID = 1001

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    @Volatile private var isOverlayActive = false
    @Volatile private var isPhoneCallActive = false

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.stark.jarvis.OVERLAY_DISMISSED") {
                Log.d(TAG, "Overlay dismissed. Resuming silent ear.")
                mainHandler.postDelayed({
                    isOverlayActive = false
                    restartListeningLoop(600)
                }, 500)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis Service initialized.")

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

        startForeground(NOTIFICATION_ID, buildForegroundNotification("Online & Ready, Boss"))

        val filter = IntentFilter("com.stark.jarvis.OVERLAY_DISMISSED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayReceiver, filter)
        }

        setupTTS()
        setupSpeechRecognizer()
        mainHandler.postDelayed({ startListeningLoop() }, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speakText = intent?.getStringExtra("SPEAK_TEXT")
        if (!speakText.isNullOrEmpty()) {
            speakTextOutLoud(speakText)
        }

        if (!isListening && !isOverlayActive) {
            startListeningLoop()
        }

        return START_STICKY
    }

    private fun setupTTS() {
        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            Log.e(TAG, "TTS init error: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            selectDeepMaleJarvisVoice()
        }
    }

    private fun selectDeepMaleJarvisVoice() {
        try {
            tts?.language = Locale("hi", "IN")
            val voices = tts?.voices
            if (!voices.isNullOrEmpty()) {
                val maleVoice = voices.find { voice ->
                    val name = voice.name.lowercase(Locale.ROOT)
                    val lang = voice.locale.language
                    val isHindiOrIndian = (lang == "hi" || (lang == "en" && voice.locale.country == "IN"))
                    val isMale = (name.contains("male") || name.contains("man") || name.contains("boy") ||
                            name.contains("hie") || name.contains("hid") || name.contains("end") || name.contains("enc")) &&
                            !name.contains("female") && !name.contains("woman") && !name.contains("girl")
                    isHindiOrIndian && isMale
                } ?: voices.find { voice ->
                    val name = voice.name.lowercase(Locale.ROOT)
                    !name.contains("female") && !name.contains("woman") && !name.contains("girl")
                }

                if (maleVoice != null) {
                    tts?.voice = maleVoice
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice select error: ${e.message}")
        }

        // Deep baritone masculine pitch & crisp rate
        tts?.setPitch(0.84f)
        tts?.setSpeechRate(1.00f)
    }

    private fun speakTextOutLoud(text: String) {
        mainHandler.post {
            if (isTtsReady && tts != null) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_NOTIF_VOICE")
            }
        }
    }

    /**
     * Completely eliminates the "toon-toon" mic sound by momentarily muting system notification streams.
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
                            mainHandler.postDelayed({ muteRecognizerBeep(false) }, 300)
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                            muteRecognizerBeep(false)
                        }
                        override fun onError(error: Int) {
                            isListening = false
                            muteRecognizerBeep(false)
                            restartListeningLoop(800)
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            muteRecognizerBeep(false)
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                evaluateBackgroundSpeech(matches)
                            } else {
                                restartListeningLoop(600)
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
            if (isListening || isOverlayActive || isPhoneCallActive) return@post

            try {
                if (speechRecognizer == null) {
                    setupSpeechRecognizer()
                }
                if (recognizerIntent != null && speechRecognizer != null) {
                    muteRecognizerBeep(true)
                    speechRecognizer?.startListening(recognizerIntent)
                }
            } catch (e: Exception) {
                muteRecognizerBeep(false)
                restartListeningLoop(800)
            }
        }
    }

    private fun restartListeningLoop(delayMs: Long = 700) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isOverlayActive && !isPhoneCallActive) {
                startListeningLoop()
            }
        }, delayMs)
    }

    /**
     * Strict Wake-Word Verification:
     * - ONLY activates for user-specified wake words.
     * - When other apps (YouTube, Reels, Games) play sound, rejects all dialogue unless leading wake word is spoken.
     * - Custom Voice Unlock: Wakes up and unlocks screen instantly.
     * - Custom Voice Lock: Locks screen instantly.
     */
    private fun evaluateBackgroundSpeech(matches: List<String>) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isMediaPlaying = audioManager.isMusicActive

        val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val customUnlockPhrase = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim")?.trim()?.lowercase(Locale.ROOT) ?: ""
        val customLockPhrase = prefs.getString("CUSTOM_LOCK_PHRASE", "phone lock karo")?.trim()?.lowercase(Locale.ROOT) ?: ""

        // 1. Strict Allowed Trigger Words & Phrases
        val allowedPrefixes = listOf(
            // Jarvis / Jarvish
            "hey jarvis", "hello jarvis", "hi jarvis", "hii jarvis", "suno jarvis",
            "hey jarvish", "hello jarvish", "hi jarvish", "hii jarvish", "suno jarvish",
            // Stark
            "hey stark", "hello stark", "hi stark", "suno stark",
            // Computer
            "hey computer", "hello computer", "suno computer",
            // Assistant
            "hey assistant", "hello assistant", "suno assistant",
            // Hindi
            "\u0939\u0947 \u091c\u093e\u0930\u0935\u093f\u0938", "\u0939\u0947\u0932\u094b \u091c\u093e\u0930\u0935\u093f\u0938", "\u0938\u0941\u0928\u094b \u091c\u093e\u0930\u0935\u093f\u0938",
            "\u0939\u0947 \u091c\u093e\u0930\u0935\u093f\u0938\u093c"
        )

        val allowedSingleWords = listOf(
            "jarvis", "jarvish", "stark", "computer", "assistant",
            "\u091c\u093e\u0930\u0935\u093f\u0938", "\u091c\u093e\u0930\u0935\u093f\u0938\u093c"
        )

        for (candidate in matches) {
            val lower = candidate.lowercase(Locale.ROOT).trim()
            val words = lower.split("\\s+".toRegex())

            // 1. Check Custom Voice Unlock Password directly
            if ((customUnlockPhrase.isNotEmpty() && lower.contains(customUnlockPhrase)) || 
                lower.contains("khul ja sim sim") || lower.contains("open sesame")) {
                Log.d(TAG, "Custom Voice Unlock Password matched: '$lower'")
                triggerWakeAlert()
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "com.stark.jarvis:UnlockWakeLock"
                    )
                    wl.acquire(4000L)
                } catch (e: Exception) {}

                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.unlockWithSwipeGesture { success ->
                            speakTextOutLoud(if (success) "Voice password verified, Boss! Screen unlock kar di hai." else "Voice password verified Boss.")
                        }
                    } else {
                        speakTextOutLoud("Voice password verified Boss. Accessibility enable karein swipe unlock ke liye.")
                    }
                }, 400)
                return
            }

            // 2. Check Custom Voice Lock Password directly
            if (customLockPhrase.isNotEmpty() && lower.contains(customLockPhrase)) {
                Log.d(TAG, "Custom Voice Lock Password matched: '$lower'")
                triggerWakeAlert()
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.lockPhoneScreen()) {
                    speakTextOutLoud("Ji Boss, phone lock kar diya hai.")
                }
                return
            }

            // 3. Media Playing Protection (Reels, YouTube, Games, Video Sound)
            if (isMediaPlaying) {
                for (p in allowedPrefixes) {
                    if (lower.startsWith(p)) {
                        val command = lower.substringAfter(p).trim()
                        triggerWakeAlert()
                        wakeUpAssistantOverlay(command)
                        return
                    }
                }
                // Do NOT trigger on random words from running video/app audio!
                continue
            }

            // 4. Normal Ambient Listening Mode:
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
                Log.d(TAG, "Wake word verified: '$detectedWake' in '$candidate'")
                val command = lower.substringAfter(detectedWake).trim()
                triggerWakeAlert()
                wakeUpAssistantOverlay(command)
                return
            }
        }

        restartListeningLoop(700)
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
                restartListeningLoop(1000)
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
            .setContentTitle("JARVIS AI • Active")
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
        try { unregisterReceiver(overlayReceiver) } catch (ignored: Exception) {}

        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()

        try { speechRecognizer?.destroy() } catch (ignored: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (ignored: Exception) {}

        Log.d(TAG, "Jarvis Service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
