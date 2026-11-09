package com.stark.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
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

object CommandExecutor {

    private const val TAG = "CommandExecutor"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun execute(context: Context, rawCommand: String, callback: (reply: String, isQuickDismiss: Boolean) -> Unit) {
        val command = rawCommand.lowercase(Locale.ROOT).trim()

        when {
            // 1. Direct Phone Calling (Handles all Hindi / English voice variations)
            command.contains("call") || command.contains("phone") || command.contains("dial") || 
            command.contains("कॉल") || command.contains("फोन") -> {
                val target = command
                    .replace("call lagao", "")
                    .replace("phone lagao", "")
                    .replace("call karo", "")
                    .replace("phone karo", "")
                    .replace("ko call", "")
                    .replace("ko phone", "")
                    .replace("call", "")
                    .replace("phone", "")
                    .replace("dial", "")
                    .replace("lagao", "")
                    .replace("karo", "")
                    .replace("ko", "")
                    .replace("par", "")
                    .replace("कॉल लगाओ", "")
                    .replace("फोन लगाओ", "")
                    .replace("कॉल", "")
                    .replace("फोन", "")
                    .trim()
                makeDirectPhoneCall(context, target, callback)
            }

            // 2. WhatsApp Direct Messaging
            command.contains("whatsapp") || command.contains("व्हाट्सएप") -> {
                sendWhatsAppToContact(context, command, callback)
            }

            // 3. 📷 Google Lens / Visual Camera Mode
            command.contains("lens") || command.contains("photo scan") || command.contains("photo dekho") || 
            command.contains("scan karo") || command.contains("camera se dekho") -> {
                launchGoogleLensOrCamera(context, callback)
            }

            // 4. Find My Phone / Siren Alert
            command.contains("where are you") || command.contains("find my phone") || 
            command.contains("kahan ho") || command.contains("kidhar ho") || command.contains("phone dhoondho") -> {
                findMyPhoneAlarm(context)
                callback("Main yahi par hoon Sir! Siren and beacon activated!", false)
            }

            // 5. GPS Navigation & Google Maps
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
                    context.startActivity(mapIntent)
                    callback("Theek hai Sir, $destination ka rasta Google Maps par dikha raha hoon.", true)
                } catch (e: Exception) {
                    callback("Google Maps open nahi ho saka, Sir.", false)
                }
            }

            // 6. Flashlight / Torch
            command.contains("flashlight on") || command.contains("torch on") || 
            command.contains("torch jalao") || command.contains("torch chalu") || command.contains("light jalao") || command.contains("batti jalao") -> {
                toggleTorch(context, true)
                callback("Torch jala di gayi hai, Sir.", false)
            }
            command.contains("flashlight off") || command.contains("torch off") || 
            command.contains("torch band") || command.contains("torch bujha") || command.contains("light band") -> {
                toggleTorch(context, false)
                callback("Torch band kar di gayi hai, Sir.", false)
            }

            // 7. Media & Music Playback
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
                context.startActivity(ytIntent)
                callback("Theek hai Sir, $query YouTube par chala raha hoon.", true)
            }

            // 8. Universal App Launcher
            command.startsWith("open ") || command.startsWith("launch ") || 
            command.contains("kholo") || command.contains("chalao") || command.contains("chalu karo") -> {
                val appTarget = command
                    .replace("open", "")
                    .replace("launch", "")
                    .replace("kholo", "")
                    .replace("chalu karo", "")
                    .replace("chalao", "")
                    .trim()
                val opened = launchAppByName(context, appTarget, callback)
                if (!opened) {
                    queryAIBrain(context, command, callback)
                }
            }

            // 9. Volume Controls
            command.contains("max volume") || command.contains("full aawaz") || command.contains("puri aawaz") -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                callback("Aawaz full 100% kar di hai, Sir.", false)
            }
            command.contains("volume up") || command.contains("aawaz badhao") || command.contains("aawaz tez") -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                callback("Aawaz badha di gayi hai, Sir.", false)
            }
            command.contains("volume down") || command.contains("aawaz kam") || command.contains("aawaz dheemi") -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                callback("Aawaz kam kar di hai, Sir.", false)
            }
            command.contains("mute") || command.contains("aawaz band") || command.contains("silent") -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                callback("Phone silent kar diya hai, Sir.", false)
            }

            // 10. Time & Date Telemetry
            command.contains("time") || command.contains("baje") || command.contains("samay") -> {
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                callback("Abhi time $timeStr ho raha hai, Sir.", false)
            }
            command.contains("date") || command.contains("tarikh") || command.contains("din") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(Date())
                callback("Aaj $dateStr hai, Sir.", false)
            }

            // 11. UNIVERSAL AI BRAIN
            else -> {
                queryAIBrain(context, command, callback)
            }
        }
    }

    private fun findContactNumber(context: Context, query: String): Pair<String, String>? {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) return null

        val searchTerms = mutableListOf(cleanQuery)
        if (cleanQuery.contains("papa") || cleanQuery.contains("pitaji") || cleanQuery.contains("dad")) {
            searchTerms.addAll(listOf("papa", "dad", "father", "pitaji", "bauji", "daddy"))
        } else if (cleanQuery.contains("mummy") || cleanQuery.contains("maa") || cleanQuery.contains("mom")) {
            searchTerms.addAll(listOf("mummy", "mom", "mother", "maa", "mataji"))
        } else if (cleanQuery.contains("bhai") || cleanQuery.contains("brother")) {
            searchTerms.addAll(listOf("bhai", "brother", "bro"))
        } else if (cleanQuery.contains("didi") || cleanQuery.contains("sister")) {
            searchTerms.addAll(listOf("didi", "sister"))
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: ""
                    val number = it.getString(numIdx) ?: ""
                    val lowerName = name.lowercase(Locale.ROOT)
                    for (term in searchTerms) {
                        if (lowerName.contains(term) || term.contains(lowerName)) {
                            val cleanNum = number.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
                            return Pair(name, cleanNum)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contacts query error: ${e.message}")
        }
        return null
    }

    private fun makeDirectPhoneCall(context: Context, target: String, callback: (String, Boolean) -> Unit) {
        val digitsOnly = target.filter { it.isDigit() }
        var finalNumber: String? = null
        var contactName = target

        if (digitsOnly.length >= 5) {
            finalNumber = digitsOnly
        } else {
            val match = findContactNumber(context, target)
            if (match != null) {
                contactName = match.first
                finalNumber = match.second
            }
        }

        if (!finalNumber.isNullOrEmpty()) {
            callback("Theek hai Sir, $contactName ko direct call mila raha hoon.", true)
            val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val callIntent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$finalNumber"))
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$finalNumber"))
            }
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(callIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Call failed: ${e.message}")
            }
        } else {
            callback("Contacts mein $target ka number nahi mila, Sir.", false)
            val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialerIntent)
        }
    }

    private fun sendWhatsAppToContact(context: Context, rawCommand: String, callback: (String, Boolean) -> Unit) {
        var recipient = ""
        var message = "Hello from Jarvis"

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

        val match = findContactNumber(context, recipient)
        val cleanNumber = match?.second ?: ""

        val sendIntent = if (cleanNumber.isNotEmpty()) {
            var formatted = cleanNumber
            if (!formatted.startsWith("+") && formatted.length == 10) {
                formatted = "91$formatted"
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
            context.startActivity(sendIntent)
            callback("Theek hai Sir, WhatsApp par message likh diya hai.", true)
        } catch (e: Exception) {
            callback("WhatsApp open nahi ho saka, Sir.", false)
        }
    }

    fun launchGoogleLensOrCamera(context: Context, callback: (String, Boolean) -> Unit) {
        val pm = context.packageManager
        val lensIntent = pm.getLaunchIntentForPackage("com.google.ar.lens")
        if (lensIntent != null) {
            lensIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(lensIntent)
            callback("Google Lens activate kar diya hai, Sir.", true)
            return
        }

        try {
            val lensActionIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.android.googlequicksearchbox")
                data = Uri.parse("googleapp://lens")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(lensActionIntent)
            callback("Opening Google Lens, Sir.", true)
            return
        } catch (e: Exception) {}

        try {
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(cameraIntent)
            callback("Camera open kar diya hai, Sir.", true)
        } catch (e: Exception) {
            callback("Camera open nahi ho saka, Sir.", false)
        }
    }

    private fun launchAppByName(context: Context, query: String, callback: (String, Boolean) -> Unit): Boolean {
        try {
            val pm = context.packageManager
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
                        context.startActivity(launchIntent)
                        callback("${info.loadLabel(pm)} open kar diya hai, Sir.", true)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch app error: ${e.message}")
        }
        return false
    }

    fun toggleTorch(context: Context, enable: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Torch error: ${e.message}")
        }
    }

    private fun findMyPhoneAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, AudioManager.FLAG_SHOW_UI)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, alarmUri)
            ringtone?.play()

            toggleTorch(context, true)
            mainHandler.postDelayed({ toggleTorch(context, false) }, 500)
            mainHandler.postDelayed({ toggleTorch(context, true) }, 1000)
            mainHandler.postDelayed({ toggleTorch(context, false) }, 1500)
        } catch (e: Exception) {}
    }

    fun queryAIBrain(context: Context, userPrompt: String, callback: (String, Boolean) -> Unit) {
        val cleanPrompt = userPrompt.lowercase(Locale.ROOT).trim()

        val instantReply = resolvePersonaOrConversation(cleanPrompt)
        if (instantReply != null) {
            callback(instantReply, false)
            return
        }

        thread {
            val prefs = context.getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
            val customApiKey = prefs.getString("GEMINI_API_KEY", "") ?: ""

            if (customApiKey.isNotEmpty()) {
                val geminiReply = callGeminiApi(userPrompt, customApiKey)
                if (!geminiReply.isNullOrEmpty()) {
                    mainHandler.post { callback(geminiReply, false) }
                    return@thread
                }
            }

            val pollReply = callPollinationsAi(userPrompt)
            if (!pollReply.isNullOrEmpty()) {
                mainHandler.post { callback(pollReply, false) }
                return@thread
            }

            val ddgReply = queryDuckDuckGo(userPrompt)
            if (!ddgReply.isNullOrEmpty()) {
                mainHandler.post { callback(ddgReply, false) }
                return@thread
            }

            val wikiReply = queryWikipedia(userPrompt)
            if (!wikiReply.isNullOrEmpty()) {
                mainHandler.post { callback(wikiReply, false) }
                return@thread
            }

            mainHandler.post {
                val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(userPrompt)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(searchIntent)
                callback("Aapke sawal ke results screen par dikha raha hoon, Sir.", true)
            }
        }
    }

    private fun resolvePersonaOrConversation(cmd: String): String? {
        return when {
            cmd.contains("kaise ho") || cmd.contains("kya haal") || cmd.contains("how are you") || cmd.contains("kaisi ho") ->
                "Main bilkul theek aur ready hoon Sir! Aap bataiye aaj main aapki kya seva karoon?"

            cmd.contains("tum kaun ho") || cmd.contains("who are you") || cmd.contains("aap kaun ho") || cmd.contains("naam kya hai") ->
                "Main JARVIS hoon Sir, aapka personal autonomous AI assistant. Sir Tony Stark ne mujhe banaya tha, aur ab main aapke phone par hazir hoon."

            cmd.contains("kya kar sakte ho") || cmd.contains("what can you do") || cmd.contains("kya kya kar sakte ho") || cmd.contains("tumhare features") ->
                "Main aapke phone se direct phone call mila sakta hoon, WhatsApp bhej sakta hoon, koi bhi app open kar sakta hoon, camera aur lens se scan kar sakta hoon, torch jala sakta hoon, aur kisi bhi sawal ka jawab de sakta hoon."

            cmd.contains("good morning") || cmd.contains("shubh prabhat") || cmd.contains("namaste") || cmd.contains("namaskar") || cmd.contains("hello") || cmd.contains("hey jarvis") ->
                "Namaste Sir! JARVIS aapki seva mein hazir hai, aagya dijiye."

            cmd.contains("good night") || cmd.contains("shubh ratri") || cmd.contains("so jao") ->
                "Shubh ratri Sir! Aaram kijiye, main phone ki nigrani karta rahoonga."

            cmd.contains("shukriya") || cmd.contains("dhanyawad") || cmd.contains("thank you") || cmd.contains("thanks") ->
                "Aapka swagat hai Sir! Main hamesha aapki seva mein hazir hoon."

            cmd.contains("joke") || cmd.contains("chutkula") || cmd.contains("hansa") ->
                "Ek joke suniye Sir: Ek user ne computer se poocha: 'kya tum sach bolte ho?' Computer bola: 'Nahi bhai, main to sirf binary bolta hoon!' Haha!"

            cmd.contains("love you") || cmd.contains("pyar") || cmd.contains("dost") ->
                "Main hamesha aapka sabse vafadar aur bharosemand AI sathi rahoonga Sir!"

            cmd.contains("alvida") || cmd.contains("bye") || cmd.contains("tata") || cmd.contains("band ho jao") ->
                "Alvida Sir! Zaroorat padte hi bas 'Hey Jarvis' boliyega, main foran hazir ho jaoonga."

            else -> null
        }
    }

    private fun callGeminiApi(prompt: String, apiKey: String): String? {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            val payload = JSONObject().apply {
                put("contents", org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", "You are JARVIS, personal AI assistant for Sir. Answer naturally in 1-2 spoken sentences in the exact language the user spoke (Hindi/English). Do not use bullet points or markdown: $prompt")
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
                    return candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .replace("*", "").replace("#", "").replace("`", "").trim()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error: ${e.message}")
        }
        return null
    }

    private fun callPollinationsAi(prompt: String): String? {
        try {
            val encodedPrompt = Uri.encode(prompt)
            val systemPrompt = Uri.encode("You are JARVIS AI assistant. Answer in 1 or 2 spoken sentences in Hindi or English without markdown.")
            val url = URL("https://text.pollinations.ai/$encodedPrompt?model=openai-fast&system=$systemPrompt")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val text = BufferedReader(InputStreamReader(conn.inputStream)).readText().trim()
                if (text.isNotEmpty() && !text.contains("Free limit reached") && !text.contains("pollen key")) {
                    return text.replace("*", "").replace("#", "").replace("`", "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pollinations error: ${e.message}")
        }
        return null
    }

    private fun queryDuckDuckGo(prompt: String): String? {
        try {
            val cleanQuery = prompt
                .replace("who is", "").replace("what is", "").replace("where is", "")
                .replace("kaun hai", "").replace("kya hai", "").replace("kahan hai", "")
                .trim()
            val url = URL("https://api.duckduckgo.com/?q=${Uri.encode(cleanQuery)}&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "JARVIS-Android/1.0")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(response)
                val abstractText = json.optString("AbstractText", "")
                if (abstractText.isNotEmpty()) {
                    val firstSentence = abstractText.split(".").take(2).joinToString(". ").trim()
                    return "$firstSentence."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DuckDuckGo error: ${e.message}")
        }
        return null
    }

    private fun queryWikipedia(prompt: String): String? {
        try {
            val cleanQuery = prompt
                .replace("who is", "").replace("what is", "").replace("where is", "")
                .replace("kaun hai", "").replace("kya hai", "").replace("kahan hai", "")
                .trim()
            val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(cleanQuery)}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "JARVIS-Android/1.0")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(response)
                val extract = json.optString("extract", "")
                if (extract.isNotEmpty()) {
                    val firstSentence = extract.split(".").take(2).joinToString(". ").trim()
                    return "$firstSentence."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wiki error: ${e.message}")
        }
        return null
    }
}
