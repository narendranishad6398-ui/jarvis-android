package com.stark.jarvis

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * JARVIS Floating Translucent Assistant Overlay
 * - Deep masculine JARVIS voice.
 * - Says "Hello Boss" on trigger.
 * - Hands-free auto dismiss for In-App actions so underlying apps receive focus.
 */
class AssistantOverlayActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val TAG = "AssistantOverlay"

    private lateinit var overlayRoot: View
    private lateinit var overlayUserText: TextView
    private lateinit var overlayResponseText: TextView
    private lateinit var lensBtn: ImageView
    private lateinit var overlayMicBtn: ImageView
    private lateinit var closeBtn: ImageView

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isListening = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { dismissOverlay() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_assistant_overlay)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }

            overlayRoot = findViewById(R.id.overlayRoot)
            overlayUserText = findViewById(R.id.overlayUserText)
            overlayResponseText = findViewById(R.id.overlayResponseText)
            lensBtn = findViewById(R.id.lensBtn)
            overlayMicBtn = findViewById(R.id.overlayMicBtn)
            closeBtn = findViewById(R.id.closeBtn)

            tts = TextToSpeech(this, this)

            val initialDirective = intent?.getStringExtra("INITIAL_DIRECTIVE") ?: ""

            overlayRoot.setOnClickListener {
                resetAutoDismissTimer(5000)
            }

            closeBtn.setOnClickListener {
                dismissOverlay()
            }

            overlayMicBtn.setOnClickListener {
                if (!isListening) {
                    startLiveVoiceRecognition()
                }
            }

            lensBtn.setOnClickListener {
                CommandExecutor.launchGoogleLensOrCamera(this) { reply, _ ->
                    speakAndDismiss(reply, true)
                }
            }

            if (initialDirective.isNotEmpty()) {
                mainHandler.postDelayed({
                    handleSpokenDirective(initialDirective)
                }, 300)
            } else {
                overlayUserText.text = "JARVIS listening..."
                overlayResponseText.text = "Hello Boss! Boliye kya hukum hai?"
                resetAutoDismissTimer(8000)
                mainHandler.postDelayed({
                    startLiveVoiceRecognition()
                }, 400)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Overlay create error: ${e.message}")
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val initialDirective = intent?.getStringExtra("INITIAL_DIRECTIVE") ?: ""
        if (initialDirective.isNotEmpty()) {
            handleSpokenDirective(initialDirective)
        } else {
            startLiveVoiceRecognition()
        }
    }

    private fun startLiveVoiceRecognition() {
        mainHandler.post {
            try {
                autoDismissHandler.removeCallbacks(autoDismissRunnable)

                if (speechRecognizer != null) {
                    try { speechRecognizer?.destroy() } catch (ignored: Exception) {}
                    speechRecognizer = null
                }

                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    isListening = true
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            overlayUserText.text = "Listening, Boss..."
                            overlayResponseText.text = "Aapki aawaz sun raha hoon..."
                        }

                        override fun onBeginningOfSpeech() {
                            overlayUserText.text = "Sun raha hoon Boss..."
                        }

                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            isListening = false
                            overlayUserText.text = "Processing command..."
                        }

                        override fun onError(error: Int) {
                            isListening = false
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                overlayResponseText.text = "Sun nahi paya Boss, dobara bolein..."
                                resetAutoDismissTimer(3000)
                            } else {
                                overlayResponseText.text = "Aawaz clear nahi aayi Boss."
                                resetAutoDismissTimer(2500)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                handleSpokenDirective(matches[0])
                            } else {
                                overlayResponseText.text = "Aawaz samajh nahi aayi, Boss."
                                resetAutoDismissTimer(2500)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!partial.isNullOrEmpty()) {
                                overlayUserText.text = partial[0]
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US", "hi"))
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    }

                    speechRecognizer?.startListening(recognizerIntent)
                } else {
                    overlayResponseText.text = "Voice recognizer error."
                    resetAutoDismissTimer(2000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SpeechRecognizer error: ${e.message}")
                resetAutoDismissTimer(2000)
            }
        }
    }

    private fun handleSpokenDirective(spokenText: String) {
        overlayUserText.text = spokenText
        overlayResponseText.text = "Executing, Boss..."

        var clean = spokenText.lowercase(Locale.ROOT)
        val wakeWords = listOf(
            "hey jarvis", "hello jarvis", "hi jarvis", "hii jarvis", "suno jarvis",
            "hey jarvish", "hello jarvish", "hi jarvish", "hii jarvish",
            "jarvis", "jarvish", "\u091c\u093e\u0930\u0935\u093f\u0938", "\u0939\u0947 \u091c\u093e\u0930\u0935\u093f\u0938", "\u0939\u0947\u0932\u094b \u091c\u093e\u0930\u0935\u093f\u0938"
        )
        for (w in wakeWords) {
            if (clean.contains(w)) {
                clean = clean.substringAfter(w).trim()
                break
            }
        }

        if (clean.isEmpty() || clean == "kaise ho" || clean == "kya haal hai" || clean == "hello" || clean == "hi") {
            speakAndDismiss("Hello Boss! Main bilkul badhiya hoon, aap bataiye kya hukum hai?", false)
            return
        }

        // In-App actions require the overlay to minimize so underlying app has window focus:
        val isInAppAction = clean.startsWith("type") || clean.startsWith("likh") || clean.startsWith("likho") ||
                clean.contains("click") || clean.contains("dabao") || clean.contains("like karo") ||
                clean.contains("scroll") || clean.contains("reel") || clean.contains("photo kheencho") ||
                clean.contains("back") || clean.contains("home") || clean.contains("recents")

        if (isInAppAction) {
            overlayResponseText.text = "Ji Boss, abhi kar raha hoon..."
            if (isTtsReady) {
                tts?.speak("Ji Boss", TextToSpeech.QUEUE_FLUSH, null, "OVERLAY_VOICE")
            }
            // Dismiss overlay after brief moment so underlying app regains active window focus
            mainHandler.postDelayed({
                dismissOverlay()
                // Execute accessibility action on the underlying app:
                CommandExecutor.execute(applicationContext, clean) { reply, _ ->
                    val speakIntent = Intent(applicationContext, JarvisService::class.java).apply {
                        putExtra("SPEAK_TEXT", reply)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(speakIntent)
                    } else {
                        applicationContext.startService(speakIntent)
                    }
                }
            }, 250)
            return
        }

        CommandExecutor.execute(this, clean) { reply, isQuickDismiss ->
            speakAndDismiss(reply, isQuickDismiss)
        }
    }

    private fun speakAndDismiss(text: String, isQuickDismiss: Boolean) {
        mainHandler.post {
            overlayResponseText.text = text

            if (isTtsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "OVERLAY_VOICE")
            }

            val delay = if (isQuickDismiss) 1800L else 3500L
            resetAutoDismissTimer(delay)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            selectNaturalHumanVoice()

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    autoDismissHandler.removeCallbacks(autoDismissRunnable)
                }
                override fun onDone(utteranceId: String?) {
                    autoDismissHandler.postDelayed(autoDismissRunnable, 1400)
                }
                override fun onError(utteranceId: String?) {
                    autoDismissHandler.postDelayed(autoDismissRunnable, 1800)
                }
            })
        }
    }

    /**
     * Deep Masculine Voice Engine (Iron Man JARVIS Profile)
     * Filters for male Hindi / Indian English voices with lower baritone pitch.
     */
    private fun selectNaturalHumanVoice() {
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

        // Deep baritone masculine pitch & clear rate
        tts?.setPitch(0.84f)
        tts?.setSpeechRate(1.00f)
    }

    private fun resetAutoDismissTimer(delayMillis: Long) {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, delayMillis)
    }

    private fun dismissOverlay() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        try {
            sendBroadcast(Intent("com.stark.jarvis.OVERLAY_DISMISSED"))
        } catch (ignored: Exception) {}

        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        try { speechRecognizer?.destroy() } catch (ignored: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (ignored: Exception) {}
    }
}
