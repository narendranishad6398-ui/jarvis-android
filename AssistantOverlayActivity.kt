package com.stark.jarvis

import android.app.KeyguardManager
import android.content.Context
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
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class AssistantOverlayActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val TAG = "AssistantOverlay"
    private val SPEECH_REQUEST_CODE = 501

    private lateinit var overlayRoot: View
    private lateinit var overlayUserText: TextView
    private lateinit var overlayResponseText: TextView
    private lateinit var lensBtn: Button
    private lateinit var overlayMicBtn: Button
    private lateinit var closeBtn: Button

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isListening = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable {
        finishAndDismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_assistant_overlay)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                km?.requestDismissKeyguard(this, null)
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

            val initialQuery = intent.getStringExtra("EXTRA_USER_QUERY") ?: ""
            val initialReply = intent.getStringExtra("EXTRA_ASSISTANT_REPLY") ?: ""
            val startListening = intent.getBooleanExtra("EXTRA_START_LISTENING", true)

            if (initialQuery.isNotEmpty()) {
                overlayUserText.text = initialQuery
            } else {
                overlayUserText.text = "Listening..."
            }

            if (initialReply.isNotEmpty()) {
                overlayResponseText.text = initialReply
            } else {
                overlayResponseText.text = "Haan Sir, boliye?"
            }

            // Tap outer background to dismiss
            overlayRoot.setOnClickListener {
                finishAndDismiss()
            }

            // Close button
            closeBtn.setOnClickListener {
                finishAndDismiss()
            }

            // Lens / Camera button
            lensBtn.setOnClickListener {
                CommandExecutor.launchGoogleLensOrCamera(this) { reply, _ ->
                    speakAndDismiss(reply, true)
                }
            }

            // Mic tap button: starts listening
            overlayMicBtn.setOnClickListener {
                startLiveVoiceRecognition()
            }

            if (startListening) {
                startLiveVoiceRecognition()
            } else if (initialReply.isNotEmpty()) {
                speakAndDismiss(initialReply, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal in onCreate: ${e.message}")
        }
    }

    private fun startLiveVoiceRecognition() {
        mainHandler.post {
            try {
                autoDismissHandler.removeCallbacks(autoDismissRunnable)
                overlayUserText.text = "Listening..."
                overlayResponseText.text = "Haan Sir, boliye?"
                overlayMicBtn.text = "🔴"

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
                            overlayMicBtn.text = "🎙"
                        }
                        override fun onError(error: Int) {
                            isListening = false
                            overlayMicBtn.text = "🎙"
                            Log.e(TAG, "SpeechRecognizer error: $error. Launching fallback dialog...")
                            startFallbackSpeechIntent()
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            overlayMicBtn.text = "🎙"
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                handleSpokenDirective(matches[0])
                            } else {
                                overlayResponseText.text = "Aawaz samajh nahi aayi, Sir."
                                resetAutoDismissTimer(3500)
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US", "hi"))
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }

                    speechRecognizer?.startListening(recognizerIntent)
                } else {
                    startFallbackSpeechIntent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting voice recognition: ${e.message}")
                startFallbackSpeechIntent()
            }
        }
    }

    private fun startFallbackSpeechIntent() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "JARVIS is listening... Speak now!")
            }
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            overlayResponseText.text = "Mic error: Please check speech settings."
            resetAutoDismissTimer(4000)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                handleSpokenDirective(results[0])
            }
        }
    }

    private fun handleSpokenDirective(spokenText: String) {
        overlayUserText.text = spokenText
        overlayResponseText.text = "Executing, Sir..."

        var clean = spokenText.lowercase(Locale.ROOT)
        val wakeWords = listOf("hey jarvis", "jarvis", "jarwish", "hey jarwish", "जार्विस", "हे जार्विस", "suno jarvis")
        for (w in wakeWords) {
            if (clean.contains(w)) {
                clean = clean.substringAfter(w).trim()
                break
            }
        }

        if (clean.isEmpty()) {
            speakAndDismiss("Haan Sir, boliye main kya karoon?", false)
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

            val delay = if (isQuickDismiss) 1800L else 3800L
            resetAutoDismissTimer(delay)
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

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    autoDismissHandler.removeCallbacks(autoDismissRunnable)
                }
                override fun onDone(utteranceId: String?) {
                    autoDismissHandler.postDelayed(autoDismissRunnable, 1500)
                }
                override fun onError(utteranceId: String?) {
                    autoDismissHandler.postDelayed(autoDismissRunnable, 2000)
                }
            })
        }
    }

    private fun resetAutoDismissTimer(delayMillis: Long) {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, delayMillis)
    }

    private fun finishAndDismiss() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        try { speechRecognizer?.stopListening() } catch (e: Exception) {}
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
    }
}
