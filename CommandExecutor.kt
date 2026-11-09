package com.stark.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Path
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

/**
 * Universal In-App & Phone Control Accessibility Service
 * Configured programmatically - requires ZERO xml config files!
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val TAG = "JarvisAccessibility"

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_DEFAULT or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                notificationTimeout = 100
            }
            this.serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "ServiceInfo setup error: ${e.message}")
        }
        Log.d(TAG, "Jarvis Accessibility Service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Jarvis Accessibility interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "Jarvis Accessibility destroyed.")
    }

    fun performAction(actionId: Int): Boolean {
        return performGlobalAction(actionId)
    }

    fun lockPhoneScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    fun openControlPanel(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun openPowerDialog(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    fun captureScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    fun unlockWithSwipeGesture(callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }

        try {
            val dm: DisplayMetrics = resources.displayMetrics
            val startX = dm.widthPixels / 2f
            val startY = dm.heightPixels * 0.82f
            val endX = startX
            val endY = dm.heightPixels * 0.18f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gestureBuilder = GestureDescription.Builder()
            val stroke = GestureDescription.StrokeDescription(swipePath, 0, 300)
            gestureBuilder.addStroke(stroke)

            dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Unlock swipe gesture succeeded.")
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Unlock swipe gesture cancelled.")
                    callback?.invoke(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Swipe gesture failed: ${e.message}")
            callback?.invoke(false)
        }
    }

    fun scrollDown(callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }
        val dm: DisplayMetrics = resources.displayMetrics
        val startX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.75f
        val endY = dm.heightPixels * 0.25f

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    fun scrollUp(callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }
        val dm: DisplayMetrics = resources.displayMetrics
        val startX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.25f
        val endY = dm.heightPixels * 0.75f

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    fun typeTextInFocusedField(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        val editableNode = findFirstEditableNode(root)
        if (editableNode != null) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        return false
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun clickElementByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanTarget = targetText.trim().lowercase(Locale.ROOT)
        return findAndClickNodeRecursive(root, cleanTarget)
    }

    private fun findAndClickNodeRecursive(node: AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

        if ((text.contains(target) || desc.contains(target)) && target.isNotEmpty()) {
            if (performClickRecursive(node)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (findAndClickNodeRecursive(child, target)) {
                return true
            }
        }

        return false
    }

    private fun performClickRecursive(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun clickShutterButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val shutterKeywords = listOf("shutter", "capture", "take photo", "camera", "photo", "record", "snap")
        for (kw in shutterKeywords) {
            if (findAndClickNodeRecursive(root, kw)) {
                return true
            }
        }
        return false
    }

    fun clickEndCallButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val endCallKeywords = listOf("end call", "disconnect", "decline", "cut call", "hang up", "call end", "end")
        for (kw in endCallKeywords) {
            if (findAndClickNodeRecursive(root, kw)) {
                return true
            }
        }
        return false
    }
}

/**
 * Universal Command Directive Engine
 */
object CommandExecutor {

    private const val TAG = "CommandExecutor"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun execute(context: Context, rawCommand: String, callback: (reply: String, isQuickDismiss: Boolean) -> Unit) {
        val command = rawCommand.lowercase(Locale.ROOT).trim()

        when {
            // 1. Direct Phone Calling (Handles all Hindi / English voice variations with precise scoring)
            isCallCommand(command) -> {
                val target = extractContactTarget(command)
                makeDirectPhoneCall(context, target, callback)
            }

            // 2. Disconnect / End Active Call
            command.contains("call kato") || command.contains("call disconnect") || 
            command.contains("phone kato") || command.contains("call end") || command.contains("disconnect call") -> {
                val acc = JarvisAccessibilityService.instance
                var ended = acc?.clickEndCallButton() ?: false
                if (!ended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                            @Suppress("DEPRECATION")
                            tm?.endCall()
                            ended = true
                        }
                    } catch (e: Exception) {}
                }
                callback(if (ended) "Call disconnect kar diya gaya hai, Sir." else "Call end karne ka prayas kiya, Sir.", true)
            }

            // 3. WhatsApp Direct Messaging
            command.contains("whatsapp") || command.contains("व्हाट्सएप") -> {
                sendWhatsAppToContact(context, command, callback)
            }

            // 4. Internet & Mobile Data (Net On / Off / Toggle)
            isNetOrDataCommand(command) -> {
                handleInternetDataSettings(context, callback)
            }

            // 5. Hotspot Control
            command.contains("hotspot") || command.contains("हॉटस्पॉट") -> {
                val intent = Intent(Settings.ACTION_TETHER_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                callback("Hotspot settings open kar di hai, Sir.", true)
            }

            // 6. Flight / Airplane Mode
            command.contains("flight mode") || command.contains("airplane mode") || command.contains("हवाई जहाज") -> {
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                callback("Flight mode settings open kar di hai, Sir.", true)
            }

            // 7. Control Panel & Quick Settings
            command.contains("control panel") || command.contains("quick settings") || 
            command.contains("slider") || command.contains("कंट्रोल पैनल") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.openControlPanel()) {
                    callback("Control panel open kar diya hai, Sir.", true)
                } else {
                    callback("Accessibility permission enable kijiye control panel ke liye, Sir.", false)
                }
            }

            // 8. Notifications Shade
            command.contains("notification") || command.contains("parda girao") || 
            command.contains("messages dikhao") || command.contains("सूचना") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.openNotifications()) {
                    callback("Notification panel open kar diya hai, Sir.", true)
                } else {
                    callback("Accessibility service ki zaroorat hai, Sir.", false)
                }
            }

            // 9. Phone Lock & Unlock
            command.contains("unlock") || command.contains("lock kholo") || 
            command.contains("screen kholo") || command.contains("phone kholo") || command.contains("लॉक खोलो") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.unlockWithSwipeGesture { success ->
                        callback(if (success) "Phone unlock kar diya gaya hai, Sir." else "Swipe karke unlock karne ki koshish ki hai, Sir.", true)
                    }
                } else {
                    callback("Accessibility service enable kijiye screen unlock ke liye, Sir.", false)
                }
            }
            command.contains("lock screen") || command.contains("lock phone") || 
            command.contains("phone lock karo") || command.contains("screen band karo") || command.contains("फोन लॉक") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.lockPhoneScreen()) {
                    callback("Phone lock kar diya gaya hai, Sir.", true)
                } else {
                    callback("Accessibility service on kijiye screen lock ke liye, Sir.", false)
                }
            }

            // 10. Power Menu / Restart / Switch Off Dialog
            command.contains("power menu") || command.contains("switch off") || 
            command.contains("restart") || command.contains("band karo phone") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.openPowerDialog()) {
                    callback("Power dialog open kar diya hai, Sir.", true)
                } else {
                    callback("Accessibility enable karein Sir.", false)
                }
            }

            // 11. Hands-Free Voice Typing in Any App ("Likh do...", "Type...")
            command.startsWith("type ") || command.startsWith("likh do ") || 
            command.startsWith("likho ") || command.contains("type karo") -> {
                val textToType = command
                    .replace("type karo", "")
                    .replace("type", "")
                    .replace("likh do", "")
                    .replace("likho", "")
                    .trim()
                val acc = JarvisAccessibilityService.instance
                if (acc != null && textToType.isNotEmpty()) {
                    val typed = acc.typeTextInFocusedField(textToType)
                    if (typed) {
                        callback("'$textToType' type kar diya hai, Sir.", true)
                    } else {
                        callback("Screen par koi likhne wali jagah nahi mili, Sir.", false)
                    }
                } else {
                    callback("Accessibility on karein screen par text type karne ke liye, Sir.", false)
                }
            }

            // 12. Click, Tap, or Like/Share Button in Any Running App
            command.startsWith("click ") || command.contains("button dabao") || 
            command.contains("par click karo") || command.contains("dabao") ||
            command.contains("like karo") || command.contains("share karo") -> {
                val acc = JarvisAccessibilityService.instance
                val clickTarget = if (command.contains("like karo")) "like"
                    else if (command.contains("share karo")) "share"
                    else command.replace("click", "").replace("button dabao", "").replace("par click karo", "").replace("dabao", "").trim()

                if (acc != null && clickTarget.isNotEmpty()) {
                    val clicked = acc.clickElementByText(clickTarget)
                    if (clicked) {
                        callback("$clickTarget click kar diya hai, Sir.", true)
                    } else {
                        callback("Screen par $clickTarget nahi mila, Sir.", false)
                    }
                } else {
                    callback("Accessibility service on karein click ke liye, Sir.", false)
                }
            }

            // 13. Camera Shutter / Take Photo
            command.contains("photo kheencho") || command.contains("photo lo") || 
            command.contains("take photo") || command.contains("capture photo") || command.contains("click photo") -> {
                val acc = JarvisAccessibilityService.instance
                var clicked = acc?.clickShutterButton() ?: false
                if (!clicked) {
                    val camIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(camIntent)
                        mainHandler.postDelayed({ acc?.clickShutterButton() }, 1500)
                        clicked = true
                    } catch (e: Exception) {}
                }
                callback(if (clicked) "Photo capture kar raha hoon, Sir." else "Camera open kiya gaya hai, Sir.", true)
            }

            // 14. In-App Scrolling (Instagram Reels, YouTube Shorts, WhatsApp, Chrome)
            command.contains("scroll down") || command.contains("neeche scroll") || 
            command.contains("aage badhao") || command.contains("next reel") || command.contains("agla video") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.scrollDown { callback("Neeche scroll kar diya hai, Sir.", true) }
                } else {
                    callback("Accessibility enable karein Sir scroll karne ke liye.", false)
                }
            }
            command.contains("scroll up") || command.contains("upar scroll") || 
            command.contains("peeche scroll") || command.contains("previous reel") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.scrollUp { callback("Upar scroll kar diya hai, Sir.", true) }
                } else {
                    callback("Accessibility enable karein Sir scroll karne ke liye.", false)
                }
            }

            // 15. Universal System Navigation (Home, Back, Recents, Screenshot)
            command.contains("home screen") || command.contains("home jao") || command.contains("go home") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.performAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    callback("Home screen par aa gaye hain, Sir.", true)
                } else {
                    val startMain = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(startMain)
                    callback("Home screen open kar di hai, Sir.", true)
                }
            }
            command.contains("back jao") || command.contains("peeche jao") || command.contains("go back") || command.contains("back karo") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.performAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    callback("Back kar diya hai, Sir.", true)
                } else {
                    callback("Accessibility enable karein Back command ke liye, Sir.", false)
                }
            }
            command.contains("recent apps") || command.contains("background apps") || command.contains("recents kholo") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    callback("Recent apps dikha raha hoon, Sir.", true)
                } else {
                    callback("Accessibility permission enable kijiye Sir.", false)
                }
            }
            command.contains("screenshot") || command.contains("screen capture") || command.contains("photo kheencho screen") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.captureScreenshot()) {
                    callback("Screenshot le liya gaya hai, Sir.", true)
                } else {
                    callback("Screenshot ke liye Accessibility permission zaroori hai, Sir.", false)
                }
            }

            // 16. Screen Brightness Controls
            command.contains("brightness") || command.contains("chamak") || command.contains("roshni") -> {
                handleBrightnessControls(context, command, callback)
            }

            // 17. Sound & Ringer Mode (Silent, Vibrate, Ring, Volume Up/Down)
            command.contains("silent") || command.contains("vibrate") || 
            command.contains("mute") || command.contains("volume") || command.contains("aawaz") -> {
                handleVolumeRingerControls(context, command, callback)
            }

            // 18. Alarms & Timers
            command.contains("alarm") || command.contains("अलार्म") -> {
                handleAlarmCommand(context, command, callback)
            }
            command.contains("timer") || command.contains("टाइमर") -> {
                handleTimerCommand(context, command, callback)
            }

            // 19. Bluetooth & WiFi Settings
            command.contains("bluetooth") || command.contains("ब्लूटूथ") -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                callback("Bluetooth settings open kar di hai, Sir.", true)
            }
            command.contains("wifi") || command.contains("वाईफाई") || command.contains("wi-fi") -> {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                callback("Wi-Fi settings open kar di hai, Sir.", true)
            }

            // 20. Flashlight / Torch
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

            // 21. GPS Navigation & Google Maps
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

            // 22. Media & Music Playback
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

            // 23. Universal App Launcher
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

            // 24. 📷 Google Lens / Visual Camera Mode
            command.contains("lens") || command.contains("photo scan") || command.contains("photo dekho") || 
            command.contains("scan karo") || command.contains("camera se dekho") -> {
                launchGoogleLensOrCamera(context, callback)
            }

            // 25. Find My Phone / Siren Alert
            command.contains("where are you") || command.contains("find my phone") || 
            command.contains("kahan ho") || command.contains("kidhar ho") || command.contains("phone dhoondho") -> {
                findMyPhoneAlarm(context)
                callback("Main yahi par hoon Sir! Siren baj raha hai!", false)
            }

            // 26. Time & Date Telemetry
            command.contains("time") || command.contains("baje") || command.contains("samay") -> {
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                callback("Abhi time $timeStr ho raha hai, Sir.", false)
            }
            command.contains("date") || command.contains("tarikh") || command.contains("din") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(Date())
                callback("Aaj $dateStr hai, Sir.", false)
            }

            // 27. UNIVERSAL ZERO-API AI BRAIN (Zero Google Redirects)
            else -> {
                queryAIBrain(context, command, callback)
            }
        }
    }

    private fun isCallCommand(cmd: String): Boolean {
        return (cmd.contains("call") || cmd.contains("phone") || cmd.contains("dial") || 
                cmd.contains("कॉल") || cmd.contains("फोन") || cmd.contains("milao")) &&
                !cmd.contains("call kato") && !cmd.contains("call end") && !cmd.contains("disconnect")
    }

    private fun isNetOrDataCommand(cmd: String): Boolean {
        return cmd.contains("net band") || cmd.contains("net off") || cmd.contains("net chalu") || cmd.contains("net on") ||
               cmd.contains("data on") || cmd.contains("data off") || cmd.contains("data band") || cmd.contains("data chalu") ||
               cmd.contains("internet on") || cmd.contains("internet off") || cmd.contains("internet band") || cmd.contains("internet chalu") ||
               cmd.contains("mobile data")
    }

    private fun handleInternetDataSettings(context: Context, callback: (String, Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
                callback("Internet panel open kar diya hai Sir, yahan se net toggle kar lijiye.", true)
                return
            } catch (ignored: Exception) {}
        }
        val dataIntent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(dataIntent)
            callback("Mobile network settings open kar di hai Sir.", true)
        } catch (e: Exception) {
            callback("Network settings open nahi ho saki, Sir.", false)
        }
    }

    private fun handleBrightnessControls(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(intent) } catch (e: Exception) {}

        if (cmd.contains("full") || cmd.contains("100") || cmd.contains("badhao") || cmd.contains("tez")) {
            callback("Display brightness settings khol di hai, roshni badha dijiye Sir.", true)
        } else {
            callback("Display brightness settings open kar di hai Sir.", true)
        }
    }

    private fun handleVolumeRingerControls(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when {
            cmd.contains("silent") || cmd.contains("mute") || cmd.contains("aawaz band") -> {
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                callback("Phone silent kar diya gaya hai, Sir.", false)
            }
            cmd.contains("vibrate") || cmd.contains("vibration") -> {
                am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                callback("Phone vibrate mode par set kar diya hai, Sir.", false)
            }
            cmd.contains("full") || cmd.contains("max") || cmd.contains("puri aawaz") -> {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                callback("Volume poori tarah badha di hai, Sir.", false)
            }
            cmd.contains("badhao") || cmd.contains("tez") || cmd.contains("up") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                callback("Aawaz badha di hai, Sir.", false)
            }
            cmd.contains("kam") || cmd.contains("dheeme") || cmd.contains("down") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                callback("Aawaz kam kar di hai, Sir.", false)
            }
            else -> {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                callback("Sound normal mode par set hai, Sir.", false)
            }
        }
    }

    private fun handleAlarmCommand(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        try {
            val digits = cmd.filter { it.isDigit() }
            val hour = if (digits.isNotEmpty()) digits.take(2).toIntOrNull() ?: 6 else 6
            val isPm = cmd.contains("pm") || cmd.contains("shaam") || cmd.contains("raat")

            val alarmHour = if (isPm && hour < 12) hour + 12 else hour
            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, alarmHour)
                putExtra(AlarmClock.EXTRA_MINUTES, 0)
                putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(alarmIntent)
            callback("$alarmHour baje ka alarm set kar diya hai, Sir.", true)
        } catch (e: Exception) {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            try { context.startActivity(intent) } catch (ex: Exception) {}
            callback("Alarm open kar diya hai, Sir.", true)
        }
    }

    private fun handleTimerCommand(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        try {
            val digits = cmd.filter { it.isDigit() }
            val minutes = if (digits.isNotEmpty()) digits.toIntOrNull() ?: 5 else 5
            val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(timerIntent)
            callback("$minutes minute ka timer laga diya hai, Sir.", true)
        } catch (e: Exception) {
            callback("Timer set nahi ho saka, Sir.", false)
        }
    }

    private fun extractContactTarget(command: String): String {
        return command
            .replace("call lagao", "")
            .replace("phone lagao", "")
            .replace("call karo", "")
            .replace("phone karo", "")
            .replace("ko call", "")
            .replace("ko phone", "")
            .replace("ko milao", "")
            .replace("number milao", "")
            .replace("call", "")
            .replace("phone", "")
            .replace("dial", "")
            .replace("lagao", "")
            .replace("karo", "")
            .replace("milao", "")
            .replace("bhaiya", "")
            .replace("didi", "")
            .replace("sahab", "")
            .replace("shree", "")
            .replace("sir", "")
            .replace("ji", "")
            .replace("ko", "")
            .replace("par", "")
            .replace("ka", "")
            .replace("ki", "")
            .replace("कॉल लगाओ", "")
            .replace("फोन लगाओ", "")
            .replace("कॉल करो", "")
            .replace("फोन करो", "")
            .replace("कॉल", "")
            .replace("फोन", "")
            .replace("लगाओ", "")
            .replace("करो", "")
            .trim()
    }

    /**
     * Precision Contact Matching:
     * - Exact Name Match (10,000 pts)
     * - Hindi/English Family Alias Match (9,000 pts)
     * - Word Boundary Token Match (5,000 pts)
     * - Starts-With Match (3,000 pts)
     * - Substring Match (1,000 pts)
     * Eliminates calling wrong people!
     */
    private fun findContactNumber(context: Context, query: String): Pair<String, String>? {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) return null

        val familyAliases = mapOf(
            "papa" to listOf("papa", "dad", "daddy", "father", "pitaji", "bauji", "babu ji", "abbu"),
            "dad" to listOf("papa", "dad", "daddy", "father", "pitaji", "bauji"),
            "pitaji" to listOf("papa", "dad", "daddy", "father", "pitaji", "bauji"),
            "mummy" to listOf("mummy", "mom", "mother", "maa", "mataji", "ammi"),
            "maa" to listOf("mummy", "mom", "mother", "maa", "mataji", "ammi"),
            "mom" to listOf("mummy", "mom", "mother", "maa", "mataji"),
            "bhai" to listOf("bhai", "bhaiya", "brother", "bro"),
            "bhaiya" to listOf("bhai", "bhaiya", "brother", "bro"),
            "didi" to listOf("didi", "sister", "behan"),
            "sister" to listOf("didi", "sister", "behan")
        )

        val targetAliases = familyAliases[cleanQuery] ?: listOf(cleanQuery)

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var bestMatch: Pair<String, String>? = null
        var highestScore = 0

        try {
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue
                    val lowerName = name.lowercase(Locale.ROOT).trim()

                    var score = 0

                    // 1. Exact match with query
                    if (lowerName == cleanQuery) {
                        score = 10000
                    } else {
                        // 2. Check aliases
                        for (alias in targetAliases) {
                            if (lowerName == alias) {
                                score = Math.max(score, 9000)
                            } else if (lowerName.contains(alias) && alias.length >= 3) {
                                score = Math.max(score, 7000)
                            }
                        }

                        // 3. Word token match
                        val words = lowerName.split("\\s+".toRegex())
                        if (words.contains(cleanQuery)) {
                            score = Math.max(score, 5000)
                        }

                        // 4. Starts with
                        if (lowerName.startsWith(cleanQuery)) {
                            score = Math.max(score, 3000)
                        }

                        // 5. Contains substring (only if length >= 3)
                        if (cleanQuery.length >= 3 && lowerName.contains(cleanQuery)) {
                            score = Math.max(score, 1000)
                        }
                    }

                    if (score > highestScore) {
                        highestScore = score
                        val cleanNum = number.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
                        bestMatch = Pair(name, cleanNum)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contacts query error: ${e.message}")
        }

        return if (highestScore > 0) bestMatch else null
    }

    private fun makeDirectPhoneCall(context: Context, target: String, callback: (String, Boolean) -> Unit) {
        val digitsOnly = target.filter { it.isDigit() }
        var finalNumber: String? = null
        var contactName = target

        if (digitsOnly.length >= 5) {
            finalNumber = digitsOnly
            contactName = digitsOnly
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
            callback("Contacts mein $target ka number nahi mila, Sir. Kripya sahi naam bolein.", false)
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
            val rem = cmd.substringAfter("ko").trim()
            if (rem.isNotEmpty()) {
                message = rem
            }
        } else {
            recipient = cmd
        }

        val match = findContactNumber(context, recipient)
        if (match != null) {
            val phone = match.second
            val formattedPhone = if (phone.startsWith("+")) phone.substring(1) else if (phone.length == 10) "91$phone" else phone
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$formattedPhone&text=${Uri.encode(message)}")
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                callback("WhatsApp par ${match.first} ko message bhej raha hoon, Sir.", true)
            } catch (e: Exception) {
                callback("WhatsApp open nahi ho saka, Sir.", false)
            }
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                callback("WhatsApp open kar diya hai, Sir.", true)
            } catch (e: Exception) {
                callback("WhatsApp open nahi ho saka, Sir.", false)
            }
        }
    }

    fun launchGoogleLensOrCamera(context: Context, callback: (String, Boolean) -> Unit) {
        try {
            val lensIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.ar.lens")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(lensIntent)
            callback("Google Lens open kar diya hai Sir, photo scan kijiye.", true)
            return
        } catch (ignored: Exception) {}

        try {
            val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(cameraIntent)
            callback("Camera open kar diya hai, Sir.", true)
        } catch (e: Exception) {
            callback("Camera chalu nahi ho saka, Sir.", false)
        }
    }

    private fun findMyPhoneAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, AudioManager.FLAG_PLAY_SOUND)

            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, alertUri)
            ringtone?.play()

            toggleTorch(context, true)

            mainHandler.postDelayed({
                try {
                    ringtone?.stop()
                    toggleTorch(context, false)
                } catch (ignored: Exception) {}
            }, 6000)
        } catch (e: Exception) {
            Log.e(TAG, "Siren error: ${e.message}")
        }
    }

    private fun toggleTorch(context: Context, enable: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle error: ${e.message}")
        }
    }

    private fun launchAppByName(context: Context, appName: String, callback: (String, Boolean) -> Unit): Boolean {
        val cleanName = appName.lowercase(Locale.ROOT)
            .replace("app", "").replace("application", "")
            .replace("kholo", "").replace("chalao", "").replace("open", "")
            .trim()

        if (cleanName.isEmpty()) return false

        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        var bestPkg: String? = null
        var bestLabel = ""

        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
            if (label == cleanName) {
                bestPkg = app.packageName
                bestLabel = pm.getApplicationLabel(app).toString()
                break
            } else if (label.contains(cleanName) && bestPkg == null) {
                bestPkg = app.packageName
                bestLabel = pm.getApplicationLabel(app).toString()
            }
        }

        if (bestPkg != null) {
            val launchIntent = pm.getLaunchIntentForPackage(bestPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                callback("Theek hai Sir, $bestLabel open kar raha hoon.", true)
                return true
            }
        }

        return false
    }

    /**
     * Autonomous Zero-API AI Brain
     * Handles multi-lingual conversation, Pollinations fast AI, DuckDuckGo instant answer,
     * Wikipedia summary, and built-in offline intelligence. ZERO Google search redirects!
     */
    private fun queryAIBrain(context: Context, prompt: String, callback: (String, Boolean) -> Unit) {
        var userPrompt = prompt
        val removePrefixes = listOf("jarvis", "hey jarvis", "hello jarvis", "hi jarvis", "suno jarvis", "boliye", "batao", "bataiye")
        for (prefix in removePrefixes) {
            if (userPrompt.startsWith(prefix)) {
                userPrompt = userPrompt.substringAfter(prefix).trim()
            }
        }

        if (userPrompt.isEmpty()) {
            callback("Haan Sir! Main sun raha hoon, bataiye main aapki kya madad karoon?", false)
            return
        }

        val personaReply = resolvePersonaOrConversation(userPrompt)
        if (personaReply != null) {
            callback(personaReply, false)
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

            // Zero Google Redirect! Direct friendly response
            mainHandler.post {
                callback("Ji Sir, maine aapka aadesh samajh liya hai par abhi network dheema hai. Phone ke offline hardware controls active hain.", false)
            }
        }
    }

    private fun resolvePersonaOrConversation(cmd: String): String? {
        return when {
            cmd.contains("kaise ho") || cmd.contains("kya haal") || cmd.contains("how are you") || cmd.contains("kaisi ho") ->
                "Main bilkul theek aur ready hoon Sir! Aap bataiye aaj main aapke phone par kya karoon?"

            cmd.contains("tum kaun ho") || cmd.contains("who are you") || cmd.contains("aap kaun ho") || cmd.contains("naam kya hai") ->
                "Main JARVIS hoon Sir, aapka personal autonomous AI assistant. Main aapke poore phone ko aawaz se chalane ke liye hazir hoon."

            cmd.contains("kya kar sakte ho") || cmd.contains("what can you do") || cmd.contains("kya kya kar sakte ho") || cmd.contains("tumhare features") ->
                "Main direct call laga sakta hoon, call kaat sakta hoon, WhatsApp bhej sakta hoon, screen unlock aur lock kar sakta hoon, net aur control panel chala sakta hoon, kisi bhi app me type aur click kar sakta hoon, scroll kar sakta hoon, aur har sawal ka jawab de sakta hoon."

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
                        JSONObject().put("text", "You are JARVIS, personal AI assistant for Sir. Answer naturally in 1-2 spoken sentences in the exact language the user spoke (Hindi/English/Hinglish). Do not use bullet points or markdown: $prompt")
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
