package com.stark.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

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

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis Service initialized.")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::BackgroundAudioLock")
        wakeLock?.acquire()

        tts = TextToSpeech(this, this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Monitoring audio for 'Jarvis'..."))

        setupSpeechRecognizer()
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speakExtra = intent?.getStringExtra("SPEAK_TEXT")
        if (!speakExtra.isNullOrEmpty()) {
            speak(speakExtra)
        }
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
                if (!isListening && speechRecognizer != null && recognizerIntent != null) {
                    speechRecognizer?.startListening(recognizerIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speech start error: ${e.message}")
                restartListeningWithDelay()
            }
        }
    }

    private fun restartListeningWithDelay() {
        mainHandler.postDelayed({
            setupSpeechRecognizer()
            startListeningLoop()
        }, 1000)
    }

    private fun processRecognizedText(text: String) {
        val wakeWords = listOf("jarvis", "hey jarvis", "jarwish", "hey jarwish", "ok jarvis", "जार्विस", "हे जार्विस", "suno jarvis")
        val isWakeWordTriggered = wakeWords.any { text.contains(it) }

        if (isWakeWordTriggered) {
            triggerWakeAlert()
            wakeUpScreenAndBringToFront()

            var command = text
            for (w in wakeWords) {
                if (command.contains(w)) {
                    command = command.substringAfter(w).trim()
                    break
                }
            }

            if (command.isEmpty()) {
                speak("Haan Sir, boliye main kya kar sakta hoon? At your service.")
            } else {
                executeDirective(command)
            }
        } else {
            restartListeningWithDelay()
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

    private fun wakeUpScreenAndBringToFront(userQuery: String = "Listening...", assistantReply: String = "At your service, Sir.") {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "JARVIS::WakeScreen"
            )
            screenLock.acquire(4000)

            // Launch Google Assistant-style bottom overlay (not full-screen stuck app!)
            val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
                putExtra("EXTRA_USER_QUERY", userQuery)
                putExtra("EXTRA_ASSISTANT_REPLY", assistantReply)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(overlayIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Screen wake error: ${e.message}")
        }
    }

    private fun launchGoogleLensOrCamera() {
        val pm: PackageManager = packageManager
        val lensIntent = pm.getLaunchIntentForPackage("com.google.ar.lens")
        if (lensIntent != null) {
            lensIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(lensIntent)
            speak("Google Lens activate kar diya hai, Sir.")
            return
        }

        try {
            val lensActionIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.android.googlequicksearchbox")
                data = Uri.parse("googleapp://lens")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(lensActionIntent)
            speak("Opening Google Lens, Sir.")
            return
        } catch (e: Exception) {}

        try {
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(cameraIntent)
            speak("Camera open kar diya hai, Sir.")
        } catch (e: Exception) {}
    }

    // ==========================================
    // 📞 DIRECT CONTACT RESOLVER & CALL PLACER
    // ==========================================
    private fun findContactNumber(query: String): Pair<String, String>? {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: ""
                    val number = it.getString(numIdx) ?: ""
                    val lowerName = name.lowercase(Locale.ROOT)
                    if (lowerName.contains(cleanQuery) || cleanQuery.contains(lowerName)) {
                        val cleanNum = number.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
                        return Pair(name, cleanNum)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contacts query error: ${e.message}")
        }
        return null
    }

    private fun makeDirectPhoneCall(target: String) {
        val digitsOnly = target.filter { it.isDigit() }
        var finalNumber: String? = null
        var contactName = target

        if (digitsOnly.length >= 5) {
            finalNumber = digitsOnly
        } else {
            // Search phonebook contacts by name (e.g. Papa, Mummy, Amit)
            val match = findContactNumber(target)
            if (match != null) {
                contactName = match.first
                finalNumber = match.second
            }
        }

        if (!finalNumber.isNullOrEmpty()) {
            speak("Theek hai Sir, $contactName ko call connect kar raha hoon.")
            val hasCallPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val callIntent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$finalNumber"))
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$finalNumber"))
            }
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(callIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Call failed: ${e.message}")
            }
        } else {
            speak("Contacts mein $target ka number nahi mila, Sir.")
            val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(dialerIntent)
        }
    }

    // ==========================================
    // 💬 DIRECT WHATSAPP SENDER TO CONTACT
    // ==========================================
    private fun sendWhatsAppToContact(rawCommand: String) {
        var recipient = ""
        var message = "Hello from Jarvis"

        // Pattern: "Papa ko whatsapp karo main aa raha hoon"
        // or "WhatsApp message bhejo Amit: Hello"
        val cmd = rawCommand.replace("whatsapp", "")
            .replace("message karo", "")
            .replace("message bhejo", "")
            .replace("bhejo", "")
            .replace("karo", "")
            .trim()

        if (cmd.contains("ko")) {
            recipient = cmd.substringBefore("ko").trim()
            message = cmd.substringAfter("ko").trim()
        } else if (cmd.contains(":")) {
            recipient = cmd.substringBefore(":").trim()
            message = cmd.substringAfter(":").trim()
        } else {
            recipient = cmd
        }

        val match = findContactNumber(recipient)
        val cleanNumber = match?.second ?: ""

        val sendIntent = if (cleanNumber.isNotEmpty()) {
            var formatted = cleanNumber
            if (!formatted.startsWith("+") && formatted.length == 10) {
                formatted = "91$formatted" // Default India country code
            }
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$formatted&text=${Uri.encode(message)}")).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            startActivity(sendIntent)
            speak("Theek hai Sir, WhatsApp par message likh diya hai.")
        } catch (e: Exception) {
            launchPackage("com.whatsapp", "WhatsApp")
        }
    }

    // ==========================================
    // 🤖 GOOGLE GEMINI AI BRAIN (LIVE REST CALL)
    // ==========================================
    private fun queryGeminiAI(userPrompt: String) {
        val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("GEMINI_API_KEY", "") ?: ""

        if (apiKey.isEmpty()) {
            // Intelligent fallback when no key is set
            speak("Aapke sawal ka direct jawab dhoondh raha hoon, Sir.")
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(userPrompt)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(searchIntent)
            return
        }

        thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 7000
                conn.readTimeout = 7000

                val payload = JSONObject().apply {
                    put("contents", org.json.JSONArray().put(
                        JSONObject().put("parts", org.json.JSONArray().put(
                            JSONObject().put("text", "You are JARVIS, personal AI assistant for Sir. Answer naturally in 1-2 spoken sentences in the exact language the user spoke (Hindi/English). Do not use bullet points or markdown: $userPrompt")
                        ))
                    ))
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    val json = JSONObject(response)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val text = candidates.getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                            .replace("*", "")
                            .replace("#", "")
                            .replace("`", "")
                            .trim()

                        mainHandler.post { speak(text) }
                        return@thread
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API error: ${e.message}")
            }

            mainHandler.post {
                val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(userPrompt)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(searchIntent)
                speak("Here are the search results for $userPrompt, Sir.")
            }
        }
    }

    private fun executeDirective(cmd: String) {
        val command = cmd.lowercase(Locale.ROOT)
        updateNotification("Directive: $command")

        when {
            // ==========================================
            // 1. Phone Calling (Direct & Contact Name)
            // ==========================================
            command.contains("call ") || command.contains("phone lagao") || command.contains("call lagao") || command.contains("phone karo") -> {
                val rawTarget = command
                    .replace("call lagao", "")
                    .replace("phone lagao", "")
                    .replace("phone karo", "")
                    .replace("ko call", "")
                    .replace("ko phone", "")
                    .replace("call", "")
                    .trim()
                makeDirectPhoneCall(rawTarget)
            }

            // ==========================================
            // 2. WhatsApp Direct Messaging
            // ==========================================
            command.contains("whatsapp") -> {
                sendWhatsAppToContact(command)
            }

            // ==========================================
            // 3. 📷 Google Lens / Visual Camera Mode
            // ==========================================
            command.contains("lens") || command.contains("photo scan") || command.contains("photo dekho") || 
            command.contains("scan karo") || command.contains("camera se dekho") -> {
                launchGoogleLensOrCamera()
            }

            // ==========================================
            // 4. Find My Phone / Siren Alert
            // ==========================================
            command.contains("where are you") || command.contains("find my phone") || 
            command.contains("kahan ho") || command.contains("kidhar ho") || command.contains("phone dhoondho") -> {
                findMyPhoneAlarm()
            }

            // ==========================================
            // 4. GPS Navigation & Google Maps
            // ==========================================
            command.contains("navigate to") || command.contains("directions to") || 
            command.contains("rasta dikhao") || command.contains("jaana hai") -> {
                val destination = command
                    .replace("rasta dikhao", "")
                    .replace("ka rasta", "")
                    .replace("jaana hai", "")
                    .substringAfter("to")
                    .trim()
                val gmmIntentUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(mapIntent)
                    speak("Theek hai Sir, $destination ka rasta Google Maps par dikha raha hoon.")
                } catch (e: Exception) {
                    speak("Google Maps open nahi ho saka, Sir.")
                }
            }

            // ==========================================
            // 5. Flashlight / Torch
            // ==========================================
            command.contains("flashlight on") || command.contains("torch on") || 
            command.contains("torch jalao") || command.contains("torch chalu") || command.contains("light jalao") || command.contains("batti jalao") -> {
                toggleTorch(true)
                speak("Torch jala di gayi hai, Sir. Illumination online.")
            }
            command.contains("flashlight off") || command.contains("torch off") || 
            command.contains("torch band") || command.contains("torch bujha") || command.contains("light band") -> {
                toggleTorch(false)
                speak("Torch band kar di gayi hai, Sir.")
            }

            // ==========================================
            // 6. Media & Music Playback
            // ==========================================
            command.startsWith("play ") || command.contains("on youtube") || 
            command.contains("gaana bajao") || command.contains("gaana chalao") || command.contains("sunao") -> {
                val query = command
                    .replace("gaana bajao", "")
                    .replace("gaana chalao", "")
                    .replace("sunao", "")
                    .replace("play", "")
                    .replace("on youtube", "")
                    .trim()
                val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(ytIntent)
                speak("Theek hai Sir, $query YouTube par chala raha hoon.")
            }

            // ==========================================
            // 7. Universal App Launcher
            // ==========================================
            command.startsWith("open ") || command.startsWith("launch ") || 
            command.contains("kholo") || command.contains("chalao") || command.contains("chalu karo") -> {
                val appTarget = command
                    .replace("open", "")
                    .replace("launch", "")
                    .replace("kholo", "")
                    .replace("chalu karo", "")
                    .replace("chalao", "")
                    .trim()
                val opened = launchAppByName(appTarget)
                if (!opened) {
                    queryGeminiAI(command)
                }
            }

            // ==========================================
            // 8. Volume Controls
            // ==========================================
            command.contains("max volume") || command.contains("full aawaz") || command.contains("puri aawaz") -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                speak("Aawaz full 100% kar di hai, Sir.")
            }
            command.contains("volume up") || command.contains("aawaz badhao") || command.contains("aawaz tez") -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                speak("Aawaz badha di gayi hai, Sir.")
            }
            command.contains("volume down") || command.contains("aawaz kam") || command.contains("aawaz dheemi") -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                speak("Aawaz kam kar di hai, Sir.")
            }
            command.contains("mute") || command.contains("aawaz band") || command.contains("silent") -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                speak("Phone silent kar diya hai, Sir.")
            }

            // ==========================================
            // 9. Time & Date Telemetry
            // ==========================================
            command.contains("time") || command.contains("baje") || command.contains("samay") -> {
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                speak("Abhi time $timeStr ho raha hai, Sir.")
            }
            command.contains("date") || command.contains("tarikh") || command.contains("din") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(Date())
                speak("Aaj $dateStr hai, Sir.")
            }

            // ==========================================
            // 10. AI BRAIN: Pass ALL Questions to Gemini
            // ==========================================
            else -> {
                queryGeminiAI(command)
            }
        }

        restartListeningWithDelay()
    }

    private fun findMyPhoneAlarm() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, AudioManager.FLAG_SHOW_UI)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            ringtone?.play()

            toggleTorch(true)
            mainHandler.postDelayed({ toggleTorch(false) }, 500)
            mainHandler.postDelayed({ toggleTorch(true) }, 1000)
            mainHandler.postDelayed({ toggleTorch(false) }, 1500)

            speak("Main yahi par hoon Sir! All visual and acoustic beacons active!")
        } catch (e: Exception) {
            speak("Main yahi par hoon Sir!")
        }
    }

    private fun launchAppByName(query: String): Boolean {
        try {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val cleanQuery = query.lowercase(Locale.ROOT).trim()

            for (info in resolveInfos) {
                val label = info.loadLabel(pm).toString().lowercase(Locale.ROOT)
                if (label == cleanQuery || label.contains(cleanQuery) || cleanQuery.contains(label)) {
                    val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        speak("${info.loadLabel(pm)} open kar diya hai, Sir.")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch app error: ${e.message}")
        }
        return false
    }

    private fun launchPackage(packageName: String, appName: String) {
        val pm: PackageManager = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            speak("$appName open kar diya hai, Sir.")
        } else {
            speak("Phone mein $appName install nahi mila, Sir.")
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

    private fun speak(text: String, userQuery: String? = null) {
        wakeUpScreenAndBringToFront(userQuery ?: "Directive executed", text)
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_VOICE")
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
