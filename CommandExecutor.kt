package com.stark.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Path
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Universal In-App & Phone Control Accessibility Service
 * Configured programmatically - requires ZERO xml config files!
 * Auto-toggles settings switches (Mobile data, Wi-Fi, Hotspot, Bluetooth).
 * Automates text typing, button clicking, and scrolling in any active app.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val TAG = "JarvisAccessibility"
    private val mainHandler = Handler(Looper.getMainLooper())

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
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
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

            dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
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

        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
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

        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * Automatically toggles settings switches (Mobile data, Wi-Fi, Hotspot, Bluetooth).
     * Finds the relevant toggle on screen and clicks it!
     */
    fun toggleSettingSwitch(enable: Boolean, targetKeyword: String? = null, callback: ((Boolean) -> Unit)? = null) {
        mainHandler.postDelayed({
            try {
                val root = rootInActiveWindow ?: run {
                    callback?.invoke(false)
                    return@postDelayed
                }

                // If target keyword is provided (e.g. "Mobile data", "Wi-Fi", "Hotspot", "Bluetooth")
                if (!targetKeyword.isNullOrEmpty()) {
                    val matchingTextNodes = root.findAccessibilityNodeInfosByText(targetKeyword)
                    if (!matchingTextNodes.isNullOrEmpty()) {
                        for (node in matchingTextNodes) {
                            if (node.isClickable) {
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                callback?.invoke(true)
                                return@postDelayed
                            }
                            var currentParent = node.parent
                            for (depth in 0 until 3) {
                                if (currentParent == null) break
                                if (currentParent.isClickable) {
                                    currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    callback?.invoke(true)
                                    return@postDelayed
                                }
                                for (c in 0 until currentParent.childCount) {
                                    val child = currentParent.getChild(c) ?: continue
                                    val cls = child.className?.toString() ?: ""
                                    if (cls.contains("Switch") || cls.contains("Button") || child.isClickable) {
                                        child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        callback?.invoke(true)
                                        return@postDelayed
                                    }
                                }
                                currentParent = currentParent.parent
                            }
                        }
                    }
                }

                // Generic switch fallback on screen
                val switches = ArrayList<AccessibilityNodeInfo>()
                findSwitchesRecursive(root, switches)
                if (switches.isNotEmpty()) {
                    for (sw in switches) {
                        if (sw.isChecked != enable || targetKeyword == null) {
                            sw.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            callback?.invoke(true)
                            return@postDelayed
                        }
                    }
                    switches[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    callback?.invoke(true)
                    return@postDelayed
                }

                callback?.invoke(false)
            } catch (e: Exception) {
                Log.e(TAG, "toggleSettingSwitch error: ${e.message}")
                callback?.invoke(false)
            }
        }, 500)
    }

    private fun findSwitchesRecursive(node: AccessibilityNodeInfo?, result: ArrayList<AccessibilityNodeInfo>) {
        if (node == null) return
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Switch") || cls.contains("ToggleButton") || cls.contains("CompoundButton")) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            findSwitchesRecursive(node.getChild(i), result)
        }
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
        val cls = node.className?.toString() ?: ""
        if (node.isEditable || cls.contains("EditText")) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun clickElementByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val clean = text.trim().lowercase(Locale.ROOT)

        val directNodes = root.findAccessibilityNodeInfosByText(clean)
        if (!directNodes.isNullOrEmpty()) {
            for (node in directNodes) {
                if (performClickRecursive(node)) return true
            }
        }

        return findAndClickNodeRecursive(root, clean)
    }

    private fun findAndClickNodeRecursive(node: AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

        if (text.contains(target) || desc.contains(target)) {
            if (performClickRecursive(node)) return true
        }

        for (i in 0 until node.childCount) {
            if (findAndClickNodeRecursive(node.getChild(i), target)) return true
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
        val shutterKeywords = listOf("shutter", "take picture", "capture", "photo", "camera button", "click")
        for (kw in shutterKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (performClickRecursive(n)) return true
                }
            }
        }
        return false
    }

    fun clickEndCallButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val endCallKeywords = listOf("end call", "disconnect", "decline", "cut call", "hang up", "call end", "end")
        for (kw in endCallKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (performClickRecursive(n)) return true
                }
            }
        }
        return false
    }
}

/**
 * High-Precision Autonomous Directives Engine for JARVIS
 * - Always addresses user respectfully as "Boss".
 * - Fully bilingual (Hindi + English) with instant direct calling.
 * - Auto-clicks settings switches so options are turned ON/OFF, not just opened.
 * - Seamless zero-Google-redirect AI Brain.
 */
object CommandExecutor {

    private const val TAG = "CommandExecutor"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun execute(context: Context, rawCommand: String, callback: (String, Boolean) -> Unit) {
        val command = rawCommand.lowercase(Locale.ROOT).trim()
        Log.d(TAG, "Executing directive: '$command'")

        when {
            // 1. Direct Phone Calling (Bilingual English & Devanagari Hindi)
            isCallCommand(command) -> {
                val target = extractContactTarget(command)
                makeDirectPhoneCall(context, target, callback)
            }

            // 2. Disconnect / End Active Call
            command.contains("call kato") || command.contains("call disconnect") || 
            command.contains("phone kato") || command.contains("call end") || command.contains("disconnect call") ||
            command.contains("\u0915\u093e\u091f\u094b") -> {
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
                callback(if (ended) "Call disconnect kar diya gaya hai, Boss." else "Call end karne ka prayas kiya, Boss.", true)
            }

            // 3. WhatsApp Direct Messaging
            command.contains("whatsapp") || command.contains("\u0935\u094d\u0939\u093e\u091f\u094d\u0938\u090f\u092a") -> {
                sendWhatsAppToContact(context, command, callback)
            }

            // 4. Internet & Mobile Data (Net On / Off / Toggle with Auto-Switch Click)
            isNetOrDataCommand(command) -> {
                handleInternetDataSettings(context, command, callback)
            }

            // 5. Hotspot Control (Auto-Clicks Switch)
            command.contains("hotspot") || command.contains("\u0939\u0949\u091f\u0938\u094d\u092a\u0949\u091f") -> {
                val turnOn = !command.contains("band") && !command.contains("off")
                val intent = Intent("android.settings.TETHER_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Hotspot")
                callback(if (turnOn) "Ji Boss, Hotspot on kar diya hai." else "Ji Boss, Hotspot band kar diya hai.", true)
            }

            // 6. Wi-Fi Control (Auto-Clicks Switch)
            command.contains("wifi") || command.contains("wi-fi") || command.contains("\u0935\u093e\u0907\u092b\u093e\u0907") -> {
                val turnOn = !command.contains("band") && !command.contains("off")
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    wm?.isWifiEnabled = turnOn
                } catch (ignored: Exception) {}
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Wi-Fi")
                callback(if (turnOn) "Ji Boss, Wi-Fi on kar diya hai." else "Ji Boss, Wi-Fi band kar diya hai.", true)
            }

            // 7. Bluetooth Control (Auto-Clicks Switch)
            command.contains("bluetooth") || command.contains("\u092c\u094d\u0932\u0942\u091f\u0942\u0925") -> {
                val turnOn = !command.contains("band") && !command.contains("off")
                try {
                    val ba = BluetoothAdapter.getDefaultAdapter()
                    if (turnOn) ba?.enable() else ba?.disable()
                } catch (ignored: Exception) {}
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Bluetooth")
                callback(if (turnOn) "Theek hai Boss, Bluetooth on kar diya hai." else "Theek hai Boss, Bluetooth band kar diya hai.", true)
            }

            // 8. Flight / Airplane Mode
            command.contains("flight mode") || command.contains("airplane mode") || command.contains("\u0939\u0935\u093e\u0908 \u091c\u0939\u093e\u091c") -> {
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                callback("Flight mode settings open kar di hai, Boss.", true)
            }

            // 9. Control Panel & Quick Settings Dropdown
            command.contains("control panel") || command.contains("quick settings") || 
            command.contains("pannel kholo") || command.contains("panel kholo") ||
            command.contains("slider") || command.contains("\u0915\u0902\u091f\u094d\u0930\u094b\u0932 \u092a\u0948\u0928\u0932") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.openControlPanel()) {
                    callback("Ji Boss, Control panel open kar diya hai.", true)
                } else {
                    callback("Accessibility service enable karein Boss.", false)
                }
            }

            // 10. Notification Shade
            command.contains("notification") || command.contains("notifications") || 
            command.contains("messages dikhao") || command.contains("\u0938\u0942\u091a\u0928\u093e") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.openNotifications()) {
                    callback("Ji Boss, Notification shade open kar diya hai.", true)
                } else {
                    callback("Accessibility on kijiye Boss notification shade ke liye.", false)
                }
            }

            // 11. Screen Unlock (Swipe Gesture Automation)
            command.contains("screen unlock") || command.contains("phone unlock") || 
            command.contains("screen kholo") || command.contains("phone kholo") || command.contains("\u0932\u0949\u0915 \u0916\u094b\u0932\u094b") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.unlockWithSwipeGesture { success ->
                        callback(if (success) "Hello Boss! Screen unlock kar di hai." else "Lock screen swipe prayas kiya Boss.", true)
                    }
                } else {
                    callback("Accessibility permission enable kijiye screen unlock ke liye Boss.", false)
                }
            }

            // 12. Screen Lock (Hardware Screen Lock)
            command.contains("screen lock") || command.contains("lock screen") || 
            command.contains("phone lock karo") || command.contains("screen band karo") || command.contains("\u092b\u094b\u0928 \u0932\u0949\u0915") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.lockPhoneScreen()) {
                    callback("Ji Boss, Phone lock kar diya hai.", true)
                } else {
                    callback("Accessibility permission enable kijiye phone lock ke liye Boss.", false)
                }
            }

            // 13. Power Menu / Restart / Switch Off Dialog
            command.contains("power menu") || command.contains("switch off") || 
            command.contains("restart") || command.contains("band karo phone") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.openPowerDialog()) {
                    callback("Ji Boss, Power dialog open kar diya hai.", true)
                } else {
                    callback("Accessibility enable karein Boss.", false)
                }
            }

            // 14. Hands-Free Voice Typing in Any App ("Likh do...", "Type...")
            command.startsWith("type ") || command.startsWith("likh do ") || 
            command.startsWith("likho ") || command.contains("type karo") -> {
                val textToType = command
                    .replace("type karo", "")
                    .replace("type", "")
                    .replace("likh do", "")
                    .replace("likho", "")
                    .trim()
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null && textToType.isNotEmpty()) {
                        val typed = acc.typeTextInFocusedField(textToType)
                        callback(if (typed) "'$textToType' likh diya hai, Boss." else "Screen par likhne ki jagah dhoondh raha hoon Boss.", true)
                    } else {
                        callback("Accessibility on karein screen par type karne ke liye Boss.", false)
                    }
                }, 300)
            }

            // 15. Click, Tap, or Like/Share Button in Any Running App
            command.startsWith("click ") || command.contains("button dabao") || 
            command.contains("par click karo") || command.contains("dabao") ||
            command.contains("like karo") || command.contains("share karo") -> {
                val clickTarget = if (command.contains("like karo")) "like"
                    else if (command.contains("share karo")) "share"
                    else command.replace("click", "").replace("button dabao", "").replace("par click karo", "").replace("dabao", "").trim()

                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null && clickTarget.isNotEmpty()) {
                        val clicked = acc.clickElementByText(clickTarget)
                        callback(if (clicked) "Theek hai Boss, $clickTarget click kar diya hai." else "Screen par $clickTarget dhoondha Boss.", true)
                    } else {
                        callback("Accessibility service on karein click ke liye Boss.", false)
                    }
                }, 300)
            }

            // 16. Camera Shutter / Take Photo
            command.contains("photo kheencho") || command.contains("photo lo") || 
            command.contains("take photo") || command.contains("capture photo") || command.contains("click photo") -> {
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    var clicked = acc?.clickShutterButton() ?: false
                    if (!clicked) {
                        val camIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(camIntent)
                            mainHandler.postDelayed({ acc?.clickShutterButton() }, 1400)
                            clicked = true
                        } catch (e: Exception) {}
                    }
                    callback(if (clicked) "Ji Boss, photo capture kar raha hoon." else "Camera open kiya gaya hai Boss.", true)
                }, 300)
            }

            // 17. In-App Scrolling (Instagram Reels, YouTube Shorts, WhatsApp, Chrome)
            command.contains("scroll down") || command.contains("neeche scroll") || 
            command.contains("aage badhao") || command.contains("next reel") || command.contains("agla video") -> {
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.scrollDown { callback("Ji Boss, neeche scroll kar diya hai.", true) }
                    } else {
                        callback("Accessibility enable karein Boss scroll karne ke liye.", false)
                    }
                }, 300)
            }
            command.contains("scroll up") || command.contains("upar scroll") || 
            command.contains("peeche scroll") || command.contains("previous reel") -> {
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.scrollUp { callback("Ji Boss, upar scroll kar diya hai.", true) }
                    } else {
                        callback("Accessibility enable karein Boss scroll karne ke liye.", false)
                    }
                }, 300)
            }

            // 18. Universal System Navigation (Home, Back, Recents, Screenshot)
            command.contains("home screen") || command.contains("home jao") || command.contains("go home") -> {
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.performAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        callback("Theek hai Boss, Home screen par aa gaye hain.", true)
                    } else {
                        val startMain = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(startMain)
                        callback("Home screen open kar di hai Boss.", true)
                    }
                }, 200)
            }
            command.contains("back jao") || command.contains("peeche jao") || command.contains("go back") || command.contains("back karo") -> {
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.performAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        callback("Theek hai Boss, Back kar diya hai.", true)
                    } else {
                        callback("Accessibility enable karein Back command ke liye Boss.", false)
                    }
                }, 200)
            }
            command.contains("recent apps") || command.contains("background apps") || command.contains("recents kholo") -> {
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                        callback("Ji Boss, Recent apps dikha raha hoon.", true)
                    } else {
                        callback("Accessibility permission enable kijiye Boss.", false)
                    }
                }, 200)
            }
            command.contains("screenshot") || command.contains("screen capture") || command.contains("photo kheencho screen") -> {
                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null && acc.captureScreenshot()) {
                        callback("Ji Boss, Screenshot le liya gaya hai.", true)
                    } else {
                        callback("Screenshot ke liye Accessibility permission zaroori hai Boss.", false)
                    }
                }, 200)
            }

            // 19. Screen Brightness Controls
            command.contains("brightness") || command.contains("chamak") || command.contains("roshni") -> {
                handleBrightnessControls(context, command, callback)
            }

            // 20. Sound & Ringer Mode (Silent, Vibrate, Ring, Volume Up/Down)
            command.contains("silent") || command.contains("vibrate") || 
            command.contains("mute") || command.contains("volume") || command.contains("aawaz") -> {
                handleVolumeRingerControls(context, command, callback)
            }

            // 21. Alarms & Timers
            command.contains("alarm") || command.contains("\u0905\u0932\u093e\u0930\u094d\u092e") -> {
                handleAlarmCommand(context, command, callback)
            }
            command.contains("timer") || command.contains("\u091f\u093e\u0907\u092e\u0930") -> {
                handleTimerCommand(context, command, callback)
            }

            // 22. Flashlight / Torch (Direct Hardware Toggle)
            command.contains("torch") || command.contains("flashlight") || command.contains("roshni") || command.contains("flash light") -> {
                val enable = !command.contains("off") && !command.contains("band")
                val success = toggleTorch(context, enable)
                callback(if (success) "Ji Boss, Flashlight ${if (enable) "on" else "off"} kar di hai." else "Torch toggle nahi ho saki Boss.", true)
            }

            // 23. Find My Phone / Emergency Siren
            command.contains("find my phone") || command.contains("mera phone kahan") || command.contains("ring loudly") -> {
                findMyPhoneAlarm(context)
                callback("Siren bajna shuru ho gaya hai Boss. Phone milne par screen tap karein.", true)
            }

            // 24. Launch Apps by Voice
            command.startsWith("open ") || command.startsWith("kholo ") || command.contains("chalao") -> {
                val appTarget = command.replace("open ", "").replace("kholo ", "").replace("chalao ", "").trim()
                launchAppByName(context, appTarget, callback)
            }

            // 25. General Knowledge & Free Built-in AI Brain
            else -> {
                queryAIBrain(context, command, callback)
            }
        }
    }

    private fun isCallCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        return (lower.contains("call") || lower.contains("phone") || lower.contains("dial") || 
                lower.contains("milao") || lower.contains("lagao") ||
                lower.contains("\u0915\u0949\u0932") || lower.contains("\u092b\u094b\u0928") || 
                lower.contains("\u092e\u093f\u0932\u093e\u0913") || lower.contains("\u0932\u0917\u093e\u0913")) &&
                !lower.contains("call kato") && !lower.contains("call end") && !lower.contains("disconnect") &&
                !lower.contains("\u0915\u093e\u091f\u094b")
    }

    private fun isNetOrDataCommand(cmd: String): Boolean {
        return cmd.contains("net band") || cmd.contains("net off") || cmd.contains("net chalu") || cmd.contains("net on") ||
               cmd.contains("data on") || cmd.contains("data off") || cmd.contains("data band") || cmd.contains("data chalu") ||
               cmd.contains("internet on") || cmd.contains("internet off") || cmd.contains("internet band") || cmd.contains("internet chalu") ||
               cmd.contains("mobile data") || cmd.contains("\u0921\u0947\u091f\u093e")
    }

    private fun handleInternetDataSettings(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val turnOn = !cmd.contains("band") && !cmd.contains("off")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Mobile data")
                callback(if (turnOn) "Ji Boss, Mobile data on kar diya hai." else "Ji Boss, Mobile data band kar diya hai.", true)
                return
            } catch (ignored: Exception) {}
        }
        val dataIntent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(dataIntent)
            val acc = JarvisAccessibilityService.instance
            acc?.toggleSettingSwitch(turnOn, "Mobile data")
            callback(if (turnOn) "Ji Boss, Mobile data on kar diya hai." else "Ji Boss, Mobile data band kar diya hai.", true)
        } catch (e: Exception) {
            callback("Network settings open nahi ho saki Boss.", false)
        }
    }

    private fun handleBrightnessControls(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(intent) } catch (e: Exception) {}

        if (cmd.contains("full") || cmd.contains("100") || cmd.contains("badhao") || cmd.contains("tez")) {
            callback("Display brightness settings open kar di hai Boss.", true)
        } else if (cmd.contains("low") || cmd.contains("kam") || cmd.contains("zero")) {
            callback("Display brightness settings open kar di hai Boss.", true)
        } else {
            callback("Brightness settings open kar di hai Boss.", true)
        }
    }

    private fun handleVolumeRingerControls(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (cmd.contains("silent") || cmd.contains("shant")) {
            try { am.ringerMode = AudioManager.RINGER_MODE_SILENT } catch (e: Exception) {}
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI) } catch (e: Exception) {}
            callback("Ji Boss, phone Silent mode par set kar diya hai.", true)
        } else if (cmd.contains("vibrate")) {
            try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (e: Exception) {}
            callback("Theek hai Boss, phone Vibrate mode par daal diya hai.", true)
        } else if (cmd.contains("full") || cmd.contains("poori") || cmd.contains("100")) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) {}
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI) } catch (e: Exception) {}
            callback("Ji Boss, volume Full kar diya hai.", true)
        } else if (cmd.contains("badhao") || cmd.contains("up") || cmd.contains("tez")) {
            try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) } catch (e: Exception) {}
            callback("Volume badha diya hai Boss.", true)
        } else if (cmd.contains("kam") || cmd.contains("down") || cmd.contains("ghatao")) {
            try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) } catch (e: Exception) {}
            callback("Volume kam kar diya hai Boss.", true)
        } else {
            try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) {}
            callback("Ringer mode normal set kar diya hai Boss.", true)
        }
    }

    private fun handleAlarmCommand(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val numbers = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
        var hour = 7
        var minutes = 0

        if (numbers.isNotEmpty()) {
            hour = numbers[0]
            if (numbers.size > 1) {
                minutes = numbers[1]
            }
        }

        if (cmd.contains("shaam") || cmd.contains("pm") || cmd.contains("raat")) {
            if (hour in 1..11) hour += 12
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alert")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            callback("Ji Boss, $hour:$minutes baje ka alarm set kar diya hai.", true)
        } catch (e: Exception) {
            val fallback = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            try { context.startActivity(fallback) } catch (ex: Exception) {}
            callback("Alarm settings open kar di hai Boss.", true)
        }
    }

    private fun handleTimerCommand(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val numbers = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
        val minutes = if (numbers.isNotEmpty()) numbers[0] else 5
        val seconds = minutes * 60

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            callback("Ji Boss, $minutes minute ka timer shuru kar diya hai.", true)
        } catch (e: Exception) {
            callback("Timer set nahi ho saka Boss.", false)
        }
    }

    private fun extractContactTarget(command: String): String {
        var text = command.lowercase(Locale.ROOT)
        val phrasesToRemove = listOf(
            "direct call mila do", "direct call lagao", "direct call karo",
            "call lagao", "phone lagao", "call karo", "phone karo",
            "ko call lagao", "ko phone lagao", "ko call karo", "ko phone karo",
            "ko call", "ko phone", "ko milao", "number milao", "call", "phone", "dial",
            "lagao", "karo", "milao", "sahab", "shree", "sir", "boss",
            "\u0915\u0949\u0932 \u0932\u0917\u093e\u0913", "\u092b\u094b\u0928 \u0932\u0917\u093e\u0913",
            "\u0915\u0949\u0932 \u0915\u0930\u094b", "\u092b\u094b\u0928 \u0915\u0930\u094b",
            "\u0915\u094b \u0915\u0949\u0932", "\u0915\u094b \u092b\u094b\u0928", "\u0915\u094b \u092e\u093f\u0932\u093e\u0913",
            "\u0915\u0949\u0932", "\u092b\u094b\u0928", "\u0932\u0917\u093e\u0913", "\u0915\u0930\u094b", "\u092e\u093f\u0932\u093e\u0913"
        )
        for (p in phrasesToRemove) {
            text = text.replace(p, " ")
        }
        val tokens = text.split("\\s+".toRegex()).filter { 
            it !in listOf("ko", "par", "ka", "ki", "\u0915\u094b", "\u092a\u0930", "\u0915\u093e", "\u0915\u0940", "") 
        }
        return tokens.joinToString(" ").trim()
    }

    private fun findContactNumber(context: Context, query: String): Pair<String, String>? {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) return null

        val familyAliases = mapOf(
            "papa" to listOf("papa", "dad", "daddy", "father", "pitaji", "bauji", "babu ji", "abbu", "\u092a\u093e\u092a\u093e", "\u092a\u093f\u0924\u093e\u091c\u0940"),
            "dad" to listOf("papa", "dad", "daddy", "father", "pitaji", "bauji"),
            "pitaji" to listOf("papa", "dad", "daddy", "father", "pitaji", "bauji"),
            "\u092a\u093e\u092a\u093e" to listOf("papa", "dad", "daddy", "father", "pitaji", "bauji", "abbu", "\u092a\u093e\u092a\u093e"),
            "mummy" to listOf("mummy", "mom", "mother", "maa", "mataji", "ammi", "\u092e\u092e\u094d\u092e\u0940", "\u092e\u093e\u0901"),
            "maa" to listOf("mummy", "mom", "mother", "maa", "mataji", "ammi"),
            "mom" to listOf("mummy", "mom", "mother", "maa", "mataji"),
            "\u092e\u092e\u094d\u092e\u0940" to listOf("mummy", "mom", "mother", "maa", "mataji", "ammi", "\u092e\u092e\u094d\u092e\u0940"),
            "bhai" to listOf("bhai", "bhaiya", "brother", "bro", "\u092d\u093e\u0908", "\u092d\u0948\u092f\u093e"),
            "bhaiya" to listOf("bhai", "bhaiya", "brother", "bro", "\u092d\u093e\u0908", "\u092d\u0948\u092f\u093e"),
            "\u092d\u093e\u0908" to listOf("bhai", "bhaiya", "brother", "bro", "\u092d\u093e\u0908", "\u092d\u0948\u092f\u093e"),
            "didi" to listOf("didi", "sister", "behan", "\u0926\u0940\u0926\u0940"),
            "sister" to listOf("didi", "sister", "behan", "\u0926\u0940\u0926\u0940")
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

                    if (lowerName == cleanQuery) {
                        score = 10000
                    } else {
                        for (alias in targetAliases) {
                            if (lowerName == alias) {
                                score = Math.max(score, 9000)
                            } else if (lowerName.contains(alias) && alias.length >= 3) {
                                score = Math.max(score, 7000)
                            }
                        }

                        val words = lowerName.split("\\s+".toRegex())
                        if (words.contains(cleanQuery)) {
                            score = Math.max(score, 5000)
                        }

                        if (lowerName.startsWith(cleanQuery)) {
                            score = Math.max(score, 3000)
                        }

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
            callback("Theek hai Boss, $contactName ko direct call mila raha hoon.", true)
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
            callback("Contacts mein $target ka number nahi mila Boss.", false)
        }
    }

    private fun sendWhatsAppToContact(context: Context, rawCommand: String, callback: (String, Boolean) -> Unit) {
        var recipient = ""
        var message = "Hello from Jarvis"

        val cmd = rawCommand.replace("whatsapp", "")
        if (cmd.contains("likho") || cmd.contains("bhejo") || cmd.contains("send")) {
            val split = cmd.split("likho", "bhejo", "send")
            recipient = extractContactTarget(split[0])
            if (split.size > 1) {
                message = split[1].trim()
            }
        } else {
            recipient = extractContactTarget(cmd)
        }

        val match = if (recipient.isNotEmpty()) findContactNumber(context, recipient) else null
        val number = match?.second ?: recipient.filter { it.isDigit() }

        if (number.isNotEmpty()) {
            try {
                val cleanNumber = if (number.startsWith("+")) number.substring(1) else if (number.length == 10) "91$number" else number
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                callback("WhatsApp open kar diya hai Boss, send par click kijiye.", true)
            } catch (e: Exception) {
                callback("WhatsApp launch error, Boss.", false)
            }
        } else {
            val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                callback("WhatsApp open kar diya hai Boss.", true)
            } else {
                callback("WhatsApp phone mein installed nahi hai Boss.", false)
            }
        }
    }

    fun launchGoogleLensOrCamera(context: Context, callback: (String, Boolean) -> Unit) {
        val lensPackage = "com.google.ar.lens"
        val pm = context.packageManager
        val lensIntent = pm.getLaunchIntentForPackage(lensPackage)
        if (lensIntent != null) {
            lensIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(lensIntent)
            callback("Visual scan initiated, Boss. Google Lens open kar diya hai.", true)
            return
        }

        val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(cameraIntent)
            callback("Optical scanner open kar diya hai Boss.", true)
        } catch (e: Exception) {
            callback("Camera launch nahi ho saka Boss.", false)
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
            ringtone.play()

            mainHandler.postDelayed({
                try { if (ringtone.isPlaying) ringtone.stop() } catch (ignored: Exception) {}
            }, 30000)
        } catch (e: Exception) {
            Log.e(TAG, "Siren error: ${e.message}")
        }
    }

    private fun toggleTorch(context: Context, enable: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle error: ${e.message}")
            false
        }
    }

    private fun launchAppByName(context: Context, rawTarget: String, callback: (String, Boolean) -> Unit) {
        val target = rawTarget.lowercase(Locale.ROOT).trim()
        val pm = context.packageManager

        val knownPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "camera" to "com.google.android.GoogleCamera",
            "gmail" to "com.google.android.gm"
        )

        for ((key, pkg) in knownPackages) {
            if (target.contains(key)) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    callback("Ji Boss, $key open kar diya hai.", true)
                    return
                }
            }
        }

        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in packages) {
            val appLabel = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.ROOT)
            if (appLabel == target || appLabel.contains(target)) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    callback("Ji Boss, $appLabel open kar diya hai.", true)
                    return
                }
            }
        }

        callback("Boss, '$rawTarget' app phone mein nahi mila.", false)
    }

    private fun queryAIBrain(context: Context, prompt: String, callback: (String, Boolean) -> Unit) {
        val persona = resolvePersonaOrConversation(prompt)
        if (persona != null) {
            callback(persona, false)
            return
        }

        executorService.execute {
            val prefs = context.getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
            val userKey = prefs.getString("GEMINI_API_KEY", "")?.trim() ?: ""

            if (userKey.isNotEmpty()) {
                val geminiReply = callGeminiApi(prompt, userKey)
                if (!geminiReply.isNullOrEmpty()) {
                    mainHandler.post { callback(geminiReply, false) }
                    return@execute
                }
            }

            val pollinationsReply = callPollinationsAi(prompt)
            if (!pollinationsReply.isNullOrEmpty()) {
                mainHandler.post { callback(pollinationsReply, false) }
                return@execute
            }

            val ddgReply = queryDuckDuckGo(prompt)
            if (!ddgReply.isNullOrEmpty()) {
                mainHandler.post { callback(ddgReply, false) }
                return@execute
            }

            val wikiReply = queryWikipedia(prompt)
            if (!wikiReply.isNullOrEmpty()) {
                mainHandler.post { callback(wikiReply, false) }
                return@execute
            }

            mainHandler.post {
                callback("Boss, main aapke liye hamesha taiyaar hoon. Boliye phone mein kya karna hai?", false)
            }
        }
    }

    private fun resolvePersonaOrConversation(p: String): String? {
        val lower = p.lowercase(Locale.ROOT).trim()

        if (lower.contains("kaun ho") || lower.contains("who are you") || lower.contains("tumhara naam")) {
            return "Main JARVIS hoon Boss. Aapka personal autonomous assistant. Main aapka poora phone hands-free control kar sakta hoon."
        }
        if (lower.contains("kaise ho") || lower.contains("how are you") || lower.contains("kya haal")) {
            return "Main bilkul shandar hoon Boss! Aapka device 100% active hai. Boliye kya hukum hai?"
        }
        if (lower.contains("kisne banaya") || lower.contains("who created you")) {
            return "Mujhe Stark Industries ki prerna se Narendra Sir ke phone ke liye autonomous banaya gaya hai Boss."
        }
        if (lower.contains("time") || lower.contains("samay") || lower.contains("kitne baje")) {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return "Boss, abhi samay hua hai ${sdf.format(Date())}."
        }
        if (lower.contains("tarikh") || lower.contains("date") || lower.contains("aaj kaun sa din")) {
            val sdf = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault())
            return "Boss, aaj ${sdf.format(Date())} hai."
        }
        if (lower.contains("kya kar sakte ho") || lower.contains("features") || lower.contains("help")) {
            return "Boss, main call laga sakta hoon, net/hotspot/wifi on-off kar sakta hoon, screen lock/unlock kar sakta hoon, kisi bhi app mein likh ya click kar sakta hoon, aur sawaalon ke jawaab de sakta hoon."
        }
        if (lower.contains("alvida") || lower.contains("bye") || lower.contains("so jao") || lower.contains("sleep")) {
            return "Alvida Boss! Standby mode par ja raha hoon. Zaroorat padte hi 'Hey Jarvis' kahiye."
        }
        return null
    }

    private fun callGeminiApi(prompt: String, apiKey: String): String? {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000

            val json = JSONObject().apply {
                val contents = org.json.JSONArray().apply {
                    val part = JSONObject().apply {
                        put("text", "You are JARVIS, an autonomous voice assistant. Answer concisely in natural Hindi/Hinglish in 1-2 short sentences. Address user as Boss. User says: $prompt")
                    }
                    val parts = org.json.JSONArray().apply { put(part) }
                    put(JSONObject().apply { put("parts", parts) })
                }
                put("contents", contents)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val resJson = JSONObject(response)
                val candidates = resJson.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val text = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    return text.trim()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error: ${e.message}")
        }
        return null
    }

    private fun callPollinationsAi(prompt: String): String? {
        try {
            val encodedPrompt = URLEncoder.encode("You are JARVIS. Answer directly and concisely in natural Hindi/Hinglish in 1-2 short sentences. Address user as Boss. $prompt", "UTF-8")
            val url = URL("https://text.pollinations.ai/$encodedPrompt")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "JARVIS-Android/1.0")

            if (conn.responseCode == 200) {
                val text = BufferedReader(InputStreamReader(conn.inputStream)).readText().trim()
                if (text.isNotEmpty() && !text.contains("error", ignoreCase = true)) {
                    return text
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pollinations error: ${e.message}")
        }
        return null
    }

    private fun queryDuckDuckGo(prompt: String): String? {
        try {
            val url = URL("https://api.duckduckgo.com/?q=${URLEncoder.encode(prompt, "UTF-8")}&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
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
