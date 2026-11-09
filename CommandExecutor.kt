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
import android.provider.MediaStore
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
 * Automates Camera Shutter, Video Recording, Text Typing, Button Clicking, and Scrolling in any active app.
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
            val startY = dm.heightPixels * 0.85f
            val endX = startX
            val endY = dm.heightPixels * 0.15f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gestureBuilder = GestureDescription.Builder()
            val stroke = GestureDescription.StrokeDescription(swipePath, 0, 250)
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

    fun clickCameraShutter(callback: ((Boolean) -> Unit)? = null) {
        val roots = getAllActiveRoots()
        for (root in roots) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun findShutter(n: AccessibilityNodeInfo) {
                val desc = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val res = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                val text = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (desc.contains("shutter") || desc.contains("capture") || desc.contains("take photo") ||
                    desc.contains("photo") || res.contains("shutter") || res.contains("capture") ||
                    res.contains("camera_button") || text.contains("shutter")) {
                    nodes.add(n)
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { findShutter(it) }
                }
            }
            findShutter(root)
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "Camera shutter clicked via accessibility node!")
                    callback?.invoke(true)
                    return
                }
            }
        }

        // Fallback: Dispatch tap at bottom-center where camera shutter button sits
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dm: DisplayMetrics = resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val cy = dm.heightPixels * 0.86f
            val tapPath = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 60))
                .build()
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Camera shutter tap gesture completed.")
                    callback?.invoke(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
        } else {
            callback?.invoke(false)
        }
    }

    fun clickCameraVideoRecord(callback: ((Boolean) -> Unit)? = null) {
        val roots = getAllActiveRoots()
        for (root in roots) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun findRecord(n: AccessibilityNodeInfo) {
                val desc = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val res = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                val text = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (desc.contains("record") || desc.contains("video") || res.contains("record") ||
                    res.contains("video_button") || text.contains("record")) {
                    nodes.add(n)
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { findRecord(it) }
                }
            }
            findRecord(root)
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "Camera video record clicked via node!")
                    callback?.invoke(true)
                    return
                }
            }
        }
        clickCameraShutter(callback)
    }

    fun scrollDown(callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }
        try {
            val dm: DisplayMetrics = resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val startY = dm.heightPixels * 0.75f
            val endY = dm.heightPixels * 0.25f

            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 300)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
        } catch (e: Exception) {
            callback?.invoke(false)
        }
    }

    fun scrollUp(callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }
        try {
            val dm: DisplayMetrics = resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val startY = dm.heightPixels * 0.25f
            val endY = dm.heightPixels * 0.75f

            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 300)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
        } catch (e: Exception) {
            callback?.invoke(false)
        }
    }

    fun getAllActiveRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { list.add(it) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val currentWindows = windows
                for (w in currentWindows) {
                    val r = w.root
                    if (r != null && !list.contains(r)) {
                        list.add(r)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Window traversal: ${e.message}")
        }
        return list
    }

    fun clickAnswerCallButton(): Boolean {
        val roots = getAllActiveRoots()
        for (root in roots) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun findAnswer(n: AccessibilityNodeInfo) {
                val desc = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val text = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val res = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                if (desc.contains("answer") || desc.contains("accept") || desc.contains("receive") ||
                    text.contains("answer") || text.contains("accept") || text.contains("receive") ||
                    text.contains("uthao") || res.contains("answer") || res.contains("accept")) {
                    nodes.add(n)
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { findAnswer(it) }
                }
            }
            findAnswer(root)
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }
        return false
    }

    fun clickEndCallButton(): Boolean {
        val roots = getAllActiveRoots()
        for (root in roots) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun findEnd(n: AccessibilityNodeInfo) {
                val desc = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val text = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val res = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                if (desc.contains("end") || desc.contains("hang up") || desc.contains("disconnect") ||
                    text.contains("end") || text.contains("disconnect") || text.contains("kato") ||
                    res.contains("end") || res.contains("hangup")) {
                    nodes.add(n)
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { findEnd(it) }
                }
            }
            findEnd(root)
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }
        return false
    }

    fun typeTextInFocusedField(text: String): Boolean {
        val roots = getAllActiveRoots()
        for (root in roots) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    return true
                }
            }

            val editables = mutableListOf<AccessibilityNodeInfo>()
            fun findEdit(n: AccessibilityNodeInfo) {
                if (n.isEditable) editables.add(n)
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { findEdit(it) }
                }
            }
            findEdit(root)

            if (editables.isNotEmpty()) {
                val target = editables.last()
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    return true
                }
            }
        }
        return false
    }

    fun clickElementByText(targetText: String): Boolean {
        val cleanTarget = targetText.lowercase(Locale.ROOT).trim()
        val roots = getAllActiveRoots()
        for (root in roots) {
            val matching = mutableListOf<AccessibilityNodeInfo>()
            fun search(n: AccessibilityNodeInfo) {
                val t = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val r = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                if (t.contains(cleanTarget) || d.contains(cleanTarget) || r.contains(cleanTarget)) {
                    matching.add(n)
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { search(it) }
                }
            }
            search(root)

            for (node in matching) {
                var clickTarget: AccessibilityNodeInfo? = node
                while (clickTarget != null && !clickTarget.isClickable) {
                    clickTarget = clickTarget.parent
                }
                if (clickTarget != null && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }
        return false
    }

    fun toggleSettingSwitch(turnOn: Boolean, targetName: String): Boolean {
        val roots = getAllActiveRoots()
        for (root in roots) {
            val switches = mutableListOf<AccessibilityNodeInfo>()
            fun findSwitches(n: AccessibilityNodeInfo) {
                val cls = n.className?.toString() ?: ""
                if (cls.contains("Switch", ignoreCase = true) || cls.contains("ToggleButton", ignoreCase = true)) {
                    switches.add(n)
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { findSwitches(it) }
                }
            }
            findSwitches(root)

            for (sw in switches) {
                if (sw.isChecked != turnOn) {
                    var clickTarget: AccessibilityNodeInfo? = sw
                    while (clickTarget != null && !clickTarget.isClickable) {
                        clickTarget = clickTarget.parent
                    }
                    if (clickTarget != null && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                } else {
                    return true
                }
            }
        }
        return false
    }

    fun clickMapAction(action: String): Boolean {
        val roots = getAllActiveRoots()
        val lowerAct = action.lowercase(Locale.ROOT)
        for (root in roots) {
            val matching = mutableListOf<AccessibilityNodeInfo>()
            fun findMapBtn(n: AccessibilityNodeInfo) {
                val desc = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val text = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val res = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                when (lowerAct) {
                    "start" -> {
                        if (desc.contains("start") || text.contains("start") || res.contains("start") ||
                            desc.contains("shuru") || text.contains("shuru")) {
                            matching.add(n)
                        }
                    }
                    "stop" -> {
                        if (desc.contains("close") || desc.contains("exit") || text.contains("exit") ||
                            desc.contains("stop") || text.contains("stop") || res.contains("close")) {
                            matching.add(n)
                        }
                    }
                    "mute" -> {
                        if (desc.contains("mute") || desc.contains("unmute") || desc.contains("sound") ||
                            desc.contains("voice") || res.contains("mute")) {
                            matching.add(n)
                        }
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { findMapBtn(it) }
                }
            }
            findMapBtn(root)

            for (m in matching) {
                var target: AccessibilityNodeInfo? = m
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * JARVIS Master Command Executor
 * Fast, offline-first execution of phone control, hardware settings, navigation, in-app automation, and calling.
 */
object CommandExecutor {

    private const val TAG = "CommandExecutor"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun execute(context: Context, rawCommand: String, callback: (String, Boolean) -> Unit) {
        val command = rawCommand.lowercase(Locale.ROOT).trim()
        Log.d(TAG, "Executing directive: '$command'")

        when {
            // 1. Voice Screen Lock (Instant)
            isLockCommand(command, context) -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.lockPhoneScreen()) {
                    callback("Ji Boss, phone lock kar diya hai.", true)
                } else {
                    callback("Phone lock ke liye Accessibility enable karein Boss.", false)
                }
            }

            // 2. Voice Screen Unlock (Wakes Screen + Swipe Gesture)
            isUnlockCommand(command, context) -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                    "com.stark.jarvis:UnlockWakeLock"
                )
                wl.acquire(6000)

                mainHandler.postDelayed({
                    val acc = JarvisAccessibilityService.instance
                    if (acc != null) {
                        acc.unlockWithSwipeGesture { success ->
                            callback(if (success) "Voice password verified, Boss! Screen unlock kar di hai." else "Voice password verified Boss.", true)
                        }
                    } else {
                        callback("Voice password verified Boss. Accessibility enable karein swipe unlock ke liye.", true)
                    }
                }, 450)
            }

            // 3. Camera Shutter: Instant Photo Capture (When Camera is Open or Direct)
            isCameraPhotoCommand(command) -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.clickCameraShutter { success ->
                        callback(if (success) "Ji Boss, photo kheench li hai!" else "Camera shutter trigger kiya Boss.", true)
                    }
                } else {
                    val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(cameraIntent) } catch (e: Exception) {}
                    callback("Camera open kar diya hai Boss.", true)
                }
            }

            // 4. Camera Video: Start or Stop Recording
            isCameraVideoCommand(command) -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.clickCameraVideoRecord { success ->
                        callback(if (success) "Ji Boss, video recording toggle kar di hai!" else "Video shutter trigger kiya Boss.", true)
                    }
                } else {
                    val videoIntent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(videoIntent) } catch (e: Exception) {}
                    callback("Video camera open kar diya hai Boss.", true)
                }
            }

            // 5. In-App Direct Text Typing
            command.startsWith("type ") || command.startsWith("likho ") || command.startsWith("write ") -> {
                val textToType = command.replace("type ", "").replace("likho ", "").replace("write ", "").trim()
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    val success = acc.typeTextInFocusedField(textToType)
                    callback(if (success) "Ji Boss, type kar diya hai." else "Active text box nahi mila Boss.", true)
                } else {
                    callback("In-app typing ke liye Accessibility service enable karein Boss.", false)
                }
            }

            // 6. In-App Direct Button Clicking
            command.startsWith("click ") || command.startsWith("dabao ") || command.startsWith("press ") || command.startsWith("tap ") -> {
                val target = command.replace("click ", "").replace("dabao ", "").replace("press ", "").replace("tap ", "").trim()
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    val clicked = acc.clickElementByText(target)
                    callback(if (clicked) "Theek hai Boss, '$target' par click kar diya hai." else "Screen par '$target' nahi mila Boss.", true)
                } else {
                    callback("Accessibility service enable karein Boss.", false)
                }
            }

            // 7. In-App Scrolling
            command.contains("scroll down") || command.contains("neeche scroll") || command.contains("scroll karo") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.scrollDown { success ->
                        callback(if (success) "Scroll down kar diya hai Boss." else "Scroll complete.", true)
                    }
                } else {
                    callback("Accessibility permission enable kijiye Boss.", false)
                }
            }

            command.contains("scroll up") || command.contains("upar scroll") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.scrollUp { success ->
                        callback(if (success) "Scroll up kar diya hai Boss." else "Scroll complete.", true)
                    }
                } else {
                    callback("Accessibility permission enable kijiye Boss.", false)
                }
            }

            // 8. System Nav: Home, Back, Recents, Screenshot
            command.contains("home screen") || command.contains("home jao") || command.contains("go home") -> {
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
            }

            command.contains("back jao") || command.contains("peeche jao") || command.contains("go back") || command.contains("back karo") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.performAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    callback("Theek hai Boss, Back kar diya hai.", true)
                } else {
                    callback("Accessibility enable karein Back command ke liye Boss.", false)
                }
            }

            command.contains("recent apps") || command.contains("background apps") || command.contains("recents kholo") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    callback("Ji Boss, Recent apps dikha raha hoon.", true)
                } else {
                    callback("Accessibility permission enable kijiye Boss.", false)
                }
            }

            command.contains("screenshot") || command.contains("screen capture") || command.contains("photo kheencho screen") -> {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && acc.captureScreenshot()) {
                    callback("Ji Boss, Screenshot le liya gaya hai.", true)
                } else {
                    callback("Screenshot ke liye Accessibility permission zaroori hai Boss.", false)
                }
            }

            // 9. Hardware & Settings: Flashlight / Torch
            command.contains("torch") || command.contains("flashlight") || command.contains("roshni") || command.contains("flash light") || command.contains("batti") -> {
                val enable = !command.contains("off") && !command.contains("band")
                val success = toggleTorch(context, enable)
                callback(if (success) "Ji Boss, Flashlight ${if (enable) "on" else "off"} kar di hai." else "Torch toggle nahi ho saki Boss.", true)
            }

            // 10. Hardware & Settings: Internet & Mobile Data
            isNetOrDataCommand(command) -> {
                handleInternetDataSettings(context, command, callback)
            }

            // 11. Hardware & Settings: Hotspot
            command.contains("hotspot") || command.contains("\u0939\u0949\u091f\u0938\u094d\u092a\u0949\u091f") -> {
                val turnOn = !command.contains("band") && !command.contains("off")
                val intent = Intent("android.settings.TETHER_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Hotspot")
                callback(if (turnOn) "Ji Boss, Hotspot on kar diya hai." else "Ji Boss, Hotspot band kar diya hai.", true)
            }

            // 12. Hardware & Settings: Wi-Fi
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

            // 13. Hardware & Settings: Bluetooth
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

            // 14. Hardware & Settings: Control Panel / Quick Settings
            command.contains("control panel") || command.contains("quick settings") || command.contains("notification panel") -> {
                val acc = JarvisAccessibilityService.instance
                val ok = acc?.openControlPanel() ?: false
                callback(if (ok) "Ji Boss, Control Panel open kar diya hai." else "Quick settings open karne ka prayas kiya.", true)
            }

            // Control Panel: Auto-Rotate / Screen Rotation
            command.contains("rotate") || command.contains("rotation") || command.contains("ghumna") -> {
                val turnOn = !command.contains("off") && !command.contains("band")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
                        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (turnOn) 1 else 0)
                        callback("Ji Boss, Auto-rotate ${if (turnOn) "on" else "off"} kar diya hai.", true)
                    } else {
                        val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        val acc = JarvisAccessibilityService.instance
                        acc?.toggleSettingSwitch(turnOn, "Auto-rotate")
                        callback("Ji Boss, Auto-rotate ${if (turnOn) "on" else "off"} kar diya hai.", true)
                    }
                } catch (e: Exception) {
                    val acc = JarvisAccessibilityService.instance
                    acc?.toggleSettingSwitch(turnOn, "Auto-rotate")
                    callback("Auto-rotate toggle kar diya hai Boss.", true)
                }
            }

            // Control Panel: Location / GPS
            (command.contains("location") && !command.contains("dikhao") && !command.contains("kholo")) || command.contains("gps") -> {
                val turnOn = !command.contains("off") && !command.contains("band")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Location")
                callback(if (turnOn) "Ji Boss, GPS Location on kar di hai." else "Ji Boss, GPS Location band kar di hai.", true)
            }

            // Control Panel: Battery Saver / Power Saving
            command.contains("battery saver") || command.contains("power saver") || command.contains("power saving") -> {
                val turnOn = !command.contains("off") && !command.contains("band")
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Battery Saver")
                callback(if (turnOn) "Ji Boss, Battery Saver on kar diya hai." else "Ji Boss, Battery Saver band kar diya hai.", true)
            }

            // Control Panel: Dark Mode / Dark Theme
            command.contains("dark mode") || command.contains("dark theme") || command.contains("light mode") -> {
                val turnOn = !command.contains("light") && !command.contains("off") && !command.contains("band")
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (e: Exception) {}
                val acc = JarvisAccessibilityService.instance
                acc?.toggleSettingSwitch(turnOn, "Dark theme")
                callback(if (turnOn) "Ji Boss, Dark theme on kar di hai." else "Ji Boss, Light theme set kar di hai.", true)
            }

            // Control Panel: Do Not Disturb (DND)
            command.contains("dnd") || command.contains("do not disturb") || command.contains("shant mode") -> {
                val turnOn = !command.contains("off") && !command.contains("band")
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (turnOn) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    callback("Ji Boss, Do Not Disturb (DND) on kar diya hai.", true)
                } else {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    callback("Ji Boss, DND off karke normal mode kar diya hai.", true)
                }
            }

            // 15. Sound & Ringer Mode (Silent, Vibrate, Ring, Volume Up/Down)
            command.contains("silent") || command.contains("vibrate") ||
            command.contains("mute") || command.contains("volume") || command.contains("aawaz") -> {
                handleVolumeRingerControls(context, command, callback)
            }

            // 16. Screen Brightness Controls
            command.contains("brightness") || command.contains("chamak") || command.contains("roshni") -> {
                handleBrightnessControls(context, command, callback)
            }

            // 17. Alarms & Timers
            command.contains("alarm") || command.contains("\u0905\u0932\u093e\u0930\u094d\u092e") -> {
                handleAlarmCommand(context, command, callback)
            }
            command.contains("timer") || command.contains("\u091f\u093e\u0907\u092e\u0930") -> {
                handleTimerCommand(context, command, callback)
            }

            // 18. Google Maps Live Turn-by-Turn Navigation
            isMapsOrNavigationCommand(command) -> {
                handleMapsNavigation(context, command, callback)
            }

            // 19. In-Map Actions (Start, Stop, Mute in Google Maps)
            command.contains("start navigation") || command.contains("rasta shuru") || command.contains("navigation shuru") -> {
                val acc = JarvisAccessibilityService.instance
                val clicked = acc?.clickMapAction("start") ?: false
                callback(if (clicked) "Ji Boss, navigation shuru kar di hai." else "Maps par start button click kiya Boss.", true)
            }

            command.contains("stop navigation") || command.contains("exit navigation") || command.contains("rasta band") -> {
                val acc = JarvisAccessibilityService.instance
                val clicked = acc?.clickMapAction("stop") ?: false
                callback(if (clicked) "Theek hai Boss, navigation band kar di hai." else "Navigation close karne ka prayas kiya Boss.", true)
            }

            // 20. Launch Apps by Voice (Flexible position)
            isOpenAppCommand(command) -> {
                val appTarget = extractAppTarget(command)
                launchAppByName(context, appTarget, callback)
            }

            // 21. Phone Calling (STRICT Filter - Eliminates 'number nahi mila' false positives!)
            isCallCommand(command) -> {
                val target = extractContactTarget(command)
                makeDirectPhoneCall(context, target, callback)
            }

            // 22. Answer / Receive Incoming Call
            isAnswerCallCommand(command) -> {
                val acc = JarvisAccessibilityService.instance
                var answered = acc?.clickAnswerCallButton() ?: false
                if (!answered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                            @Suppress("DEPRECATION")
                            tm?.acceptRingingCall()
                            answered = true
                        }
                    } catch (e: Exception) {}
                }
                callback(if (answered) "Ji Boss, call receive kar li hai." else "Call answer karne ka prayas kiya Boss.", true)
            }

            // 23. Disconnect / End Active Call
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

            // 24. WhatsApp Direct Messaging
            command.contains("whatsapp") || command.contains("\u0935\u094d\u0939\u093e\u091f\u094d\u0938\u090f\u092a") -> {
                sendWhatsAppToContact(context, command, callback)
            }

            // 25. Find My Phone / Emergency Siren
            command.contains("find my phone") || command.contains("mera phone kahan") || command.contains("ring loudly") -> {
                findMyPhoneAlarm(context)
                callback("Siren bajna shuru ho gaya hai Boss. Phone milne par screen tap karein.", true)
            }

            // 26. YouTube Direct Play
            isYouTubePlayCommand(command) -> {
                handleYouTubePlay(context, command, callback)
            }

            // 27. Battery Status & Telemetry
            isBatteryCommand(command) -> {
                handleBatteryCheck(context, callback)
            }

            // 28. Web Search on Google
            isWebSearchCommand(command) -> {
                handleWebSearch(context, command, callback)
            }

            // 29. Offline Local AI Brain / General Knowledge
            else -> {
                queryAIBrain(context, command, callback)
            }
        }
    }

    private fun isLockCommand(cmd: String, context: Context): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        val prefs = context.getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
        val customLock = prefs.getString("CUSTOM_LOCK_PHRASE", "phone lock karo")?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (customLock.isNotEmpty() && lower.contains(customLock)) {
            return true
        }
        return lower.contains("phone lock") || lower.contains("screen lock") ||
               lower.contains("lock karo") || lower.contains("lock kar do") ||
               lower.contains("screen band karo") || lower.contains("phone band karo") ||
               lower.contains("\u0932\u0949\u0915 \u0915\u0930\u094b") ||
               lower.contains("\u092b\u094b\u0928 \u0932\u0949\u0915")
    }

    private fun isUnlockCommand(cmd: String, context: Context): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        val prefs = context.getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
        val customPass = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim")?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (customPass.isNotEmpty() && lower.contains(customPass)) {
            return true
        }
        return lower.contains("screen unlock") || lower.contains("phone unlock") ||
               lower.contains("screen kholo") || lower.contains("phone kholo") ||
               lower.contains("unlock karo") || lower.contains("unlock kar do") ||
               lower.contains("khul ja sim sim") || lower.contains("open sesame") ||
               lower.contains("\u0905\u0928\u0932\u0949\u0915") ||
               lower.contains("\u0932\u0949\u0915 \u0916\u094b\u0932\u094b")
    }

    private fun isCameraPhotoCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        return lower.contains("photo kheench") || lower.contains("photo lo") ||
               lower.contains("tasveer lo") || lower.contains("capture photo") ||
               lower.contains("take photo") || lower.contains("take picture") ||
               lower.contains("click photo") || lower.contains("snap photo") ||
               lower.contains("selfie lo") || lower.contains("selfie kheench") ||
               lower.contains("\u092b\u094b\u091f\u094b \u0916\u0940\u0902\u091a\u094b") ||
               lower.contains("\u092b\u094b\u091f\u094b \u0932\u094b")
    }

    private fun isCameraVideoCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        return lower.contains("video banao") || lower.contains("record video") ||
               lower.contains("video shuru") || lower.contains("video start") ||
               lower.contains("video recording") || lower.contains("video band") ||
               lower.contains("video stop") ||
               lower.contains("\u0935\u0940\u0921\u093f\u092f\u094b \u092c\u0928\u093e\u0913")
    }

    private fun isOpenAppCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        val openWords = listOf(
            "open", "kholo", "khol do", "khol de", "khol", "chalu karo", "chalu kar", "chalu kar do",
            "chalao", "chala de", "chala do", "launch", "start", "run", "chalu",
            "\u0916\u094b\u0932\u094b", "\u0916\u094b\u0932", "\u091a\u0932\u093e\u0913", "\u091a\u093e\u0932\u0942"
        )
        return openWords.any { lower.contains(it) }
    }

    private fun extractAppTarget(command: String): String {
        var text = command.lowercase(Locale.ROOT)
        val stopWords = listOf(
            "open", "kholo", "khol do", "khol de", "khol", "chalu karo", "chalu kar do", "chalu kar", "chala do",
            "chalao", "chala de", "launch", "start", "run", "chalu", "app", "application", "please", "zara", "bhai",
            "karo", "kar", "do", "de", "bhi", "ko", "par", "pe",
            "\u0916\u094b\u0932\u094b", "\u0916\u094b\u0932", "\u091a\u0932\u093e\u0913", "\u091a\u093e\u0932\u0942"
        )
        for (w in stopWords) {
            text = text.replace(w, " ")
        }
        val target = text.trim()
        return when (target) {
            "yt" -> "youtube"
            "insta" -> "instagram"
            "fb" -> "facebook"
            "wp", "whats app" -> "whatsapp"
            else -> target
        }
    }

    private fun isCallCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        val notCall = listOf(
            "lock", "unlock", "screen", "alarm", "timer", "photo", "camera", "video",
            "torch", "batti", "flashlight", "net", "data", "wifi", "hotspot", "bluetooth",
            "kahan", "find", "battery", "volume", "sound", "aawaz", "chamak", "brightness",
            "kholo", "open", "chalao", "chalu", "youtube", "whatsapp", "status", "charging"
        )
        if (notCall.any { lower.contains(it) }) {
            return false
        }
        return (lower.startsWith("call ") || lower.contains("call lagao") || lower.contains("call karo") ||
                lower.contains("phone lagao") || lower.contains("phone mila") || lower.contains("dial ") ||
                lower.contains("ko call") || lower.contains("ko phone") ||
                lower.contains("se baat karao") || lower.contains("se baat karo") ||
                lower.contains("\u0915\u0949\u0932 \u0915\u0930\u094b") || lower.contains("\u092b\u094b\u0928 \u0915\u0930\u094b") ||
                lower.contains("\u0915\u0949\u0932 \u0932\u0917\u093e\u0913") || lower.contains("\u092b\u094b\u0928 \u0932\u0917\u093e\u0913")) &&
                !lower.contains("call kato") && !lower.contains("call end") && !lower.contains("disconnect") &&
                !lower.contains("\u0915\u093e\u091f\u094b")
    }

    private fun isAnswerCallCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        return lower.contains("call uthao") || lower.contains("phone uthao") ||
               lower.contains("call receive") || lower.contains("answer call") ||
               lower.contains("receive call") || lower.contains("call attend") ||
               lower.contains("\u0909\u0920\u093e\u0913")
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
            callback("Mobile data toggle settings open kar di hai Boss.", false)
        }
    }

    private fun handleBrightnessControls(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(intent) } catch (e: Exception) {}
        callback("Display brightness settings open kar di hai, Boss.", true)
    }

    private fun handleVolumeRingerControls(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when {
            cmd.contains("silent") -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                callback("Phone ko Silent mode par set kar diya hai, Boss.", true)
            }
            cmd.contains("vibrate") -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                callback("Phone ko Vibrate mode par daal diya hai, Boss.", true)
            }
            cmd.contains("volume badhao") || cmd.contains("aawaz badhao") || cmd.contains("volume up") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                callback("Volume badha di hai, Boss.", true)
            }
            cmd.contains("volume kam karo") || cmd.contains("aawaz kam karo") || cmd.contains("volume down") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                callback("Volume kam kar di hai, Boss.", true)
            }
            else -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                callback("Sound profile normal mode par set hai, Boss.", true)
            }
        }
    }

    private fun handleAlarmCommand(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val hourMatch = Regex("""(\d{1,2})""").find(cmd)
        val hour = hourMatch?.value?.toIntOrNull() ?: 7
        val isPM = cmd.contains("sham") || cmd.contains("raat") || cmd.contains("pm")
        val finalHour = if (isPM && hour < 12) hour + 12 else hour

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, finalHour)
            putExtra(AlarmClock.EXTRA_MINUTES, 0)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            callback("Boss, subah $hour baje ka alarm set kar diya hai.", true)
        } catch (e: Exception) {
            callback("Alarm set karne ka prayas kiya Boss.", false)
        }
    }

    private fun handleTimerCommand(context: Context, cmd: String, callback: (String, Boolean) -> Unit) {
        val numMatch = Regex("""(\d+)""").find(cmd)
        val minutes = numMatch?.value?.toIntOrNull() ?: 5
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            callback("Boss, $minutes minute ka timer shuru kar diya hai.", true)
        } catch (e: Exception) {
            callback("Timer set karne ka prayas kiya Boss.", false)
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

        val searchTerms = mutableListOf(cleanQuery)
        for ((key, aliases) in familyAliases) {
            if (cleanQuery.contains(key) || aliases.any { cleanQuery.contains(it) }) {
                searchTerms.addAll(aliases)
                break
            }
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val allContacts = mutableListOf<Pair<String, String>>()
                do {
                    val name = cursor.getString(nameIndex) ?: ""
                    val number = cursor.getString(numberIndex) ?: ""
                    allContacts.add(Pair(name, number))
                } while (cursor.moveToNext())

                for (term in searchTerms) {
                    for ((name, number) in allContacts) {
                        if (name.lowercase(Locale.ROOT).trim() == term) {
                            return Pair(name, number)
                        }
                    }
                }

                for (term in searchTerms) {
                    for ((name, number) in allContacts) {
                        if (name.lowercase(Locale.ROOT).contains(term)) {
                            return Pair(name, number)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contacts query failed: ${e.message}")
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun makeDirectPhoneCall(context: Context, target: String, callback: (String, Boolean) -> Unit) {
        if (target.isEmpty()) {
            callback("Kisko call karna hai Boss? Naam ya number bolein.", false)
            return
        }

        val digitsOnly = target.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 3 && target.all { it.isDigit() || it == '+' || it == ' ' }) {
            dialOrCallNumber(context, digitsOnly, digitsOnly, callback)
            return
        }

        val match = findContactNumber(context, target)
        if (match != null) {
            val (name, number) = match
            dialOrCallNumber(context, number, name, callback)
        } else {
            callback("Boss, contact list mein '$target' ka number nahi mila.", false)
        }
    }

    private fun dialOrCallNumber(context: Context, number: String, displayName: String, callback: (String, Boolean) -> Unit) {
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (hasCallPerm) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                callback("Ji Boss, $displayName ko direct call mila raha hoon.", true)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct ACTION_CALL failed, fallback to dial: ${e.message}")
            }
        }

        try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            callback("Ji Boss, $displayName ka dialer open kar diya hai.", true)
        } catch (e: Exception) {
            callback("Call initiate nahi ho saki Boss: ${e.message}", false)
        }
    }

    private fun sendWhatsAppToContact(context: Context, command: String, callback: (String, Boolean) -> Unit) {
        val target = command
            .replace("whatsapp par", "")
            .replace("whatsapp pe", "")
            .replace("whatsapp me", "")
            .replace("whatsapp", "")
            .replace("message bhejo", "")
            .replace("bhejo", "")
            .replace("ko", "")
            .trim()

        val match = findContactNumber(context, target)
        if (match != null) {
            val (name, number) = match
            val cleanPhone = number.replace(Regex("[^0-9]"), "")
            val formatted = if (cleanPhone.length == 10) "91$cleanPhone" else cleanPhone
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$formatted")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                callback("Ji Boss, WhatsApp par $name ki chat open kar di hai.", true)
                return
            } catch (e: Exception) {}
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            callback("Ji Boss, WhatsApp open kar diya hai.", true)
        } else {
            callback("Boss, phone mein WhatsApp installed nahi hai.", false)
        }
    }

    private fun toggleTorch(context: Context, enable: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return false
            cameraManager.setTorchMode(cameraId, enable)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun findMyPhoneAlarm(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(context, ringtoneUri)
            r?.play()
        } catch (e: Exception) {}
    }

    private fun isYouTubePlayCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        return (lower.contains("youtube") || lower.contains("gana") || lower.contains("video") || lower.contains("song")) &&
               (lower.contains("chalao") || lower.contains("bajao") || lower.contains("play") || lower.contains("dikhao") || lower.contains("sunao"))
    }

    private fun handleYouTubePlay(context: Context, command: String, callback: (String, Boolean) -> Unit) {
        val query = command
            .replace("youtube par", "")
            .replace("youtube me", "")
            .replace("youtube pe", "")
            .replace("youtube", "")
            .replace("chalao", "")
            .replace("bajao", "")
            .replace("play", "")
            .replace("dikhao", "")
            .replace("sunao", "")
            .replace("gana", "")
            .replace("song", "")
            .trim()

        val searchQuery = if (query.isEmpty()) "top trending hindi songs" else query
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", searchQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            callback("Ji Boss, YouTube par '$searchQuery' play kar raha hoon.", true)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(searchQuery, "UTF-8"))).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            callback("YouTube browser mein shuru kar diya hai Boss.", true)
        }
    }

    private fun isBatteryCommand(cmd: String): Boolean {
        return cmd.contains("battery") || cmd.contains("charging") || cmd.contains("charge")
    }

    private fun handleBatteryCheck(context: Context, callback: (String, Boolean) -> Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (level >= 0) {
            callback("Boss, battery level $level percent hai. Sabhi protocols theek kaam kar rahe hain.", true)
        } else {
            callback("Power levels normal hain Boss.", true)
        }
    }

    private fun isWebSearchCommand(cmd: String): Boolean {
        return cmd.startsWith("search ") || cmd.startsWith("google ") || cmd.startsWith("dhundo ") || cmd.contains("google par")
    }

    private fun handleWebSearch(context: Context, command: String, callback: (String, Boolean) -> Unit) {
        val query = command
            .replace("search on google", "")
            .replace("search google", "")
            .replace("google par search karo", "")
            .replace("google par", "")
            .replace("search", "")
            .replace("google", "")
            .replace("dhundo", "")
            .trim()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
            callback("Ji Boss, Google par search kar raha hoon.", true)
        } catch (e: Exception) {
            callback("Browser open nahi ho saka Boss.", false)
        }
    }

    private fun isMapsOrNavigationCommand(cmd: String): Boolean {
        return cmd.contains("rasta dikhao") || cmd.contains("rasta batao") ||
               cmd.contains("navigation") || cmd.contains("navigate") ||
               cmd.contains("location dikhao") || cmd.contains("location kholo") ||
               cmd.contains("maps me") || cmd.contains("map me") || cmd.contains("map par") ||
               cmd.contains("directions") || cmd.contains("ka rasta") ||
               cmd.contains("\u0930\u093e\u0938\u094d\u0924\u093e") || cmd.contains("\u0928\u0915\u094d\u0936\u093e")
    }

    private fun handleMapsNavigation(context: Context, command: String, callback: (String, Boolean) -> Unit) {
        var destination = command
            .replace("google maps me", "")
            .replace("google map me", "")
            .replace("maps me", "")
            .replace("map me", "")
            .replace("map par", "")
            .replace("navigate to", "")
            .replace("navigation to", "")
            .replace("navigate", "")
            .replace("navigation", "")
            .replace("rasta dikhao", "")
            .replace("rasta batao", "")
            .replace("location dikhao", "")
            .replace("location kholo", "")
            .replace("directions to", "")
            .replace("direction", "")
            .replace("ka rasta", "")
            .replace("ki location", "")
            .replace("kholo", "")
            .replace("dikhao", "")
            .replace("batao", "")
            .replace("jaana hai", "")
            .replace("le chalo", "")
            .replace("\u0930\u093e\u0938\u094d\u0924\u093e \u0926\u093f\u0916\u093e\u0913", "")
            .replace("\u0930\u093e\u0938\u094d\u0924\u093e", "")
            .trim()

        if (destination.isEmpty()) {
            destination = "nearby petrol pump"
        }

        try {
            val navUri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(mapIntent)
            callback("Ji Boss, $destination ke liye Google Maps turn-by-turn navigation shuru kar di hai.", true)
        } catch (e: Exception) {
            val genericUri = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
            val genericIntent = Intent(Intent.ACTION_VIEW, genericUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(genericIntent)
                callback("Maps par $destination ki location open kar di hai Boss.", true)
            } catch (ex: Exception) {
                callback("Maps open nahi ho saka Boss.", false)
            }
        }
    }

    private fun launchAppByName(context: Context, rawTarget: String, callback: (String, Boolean) -> Unit) {
        val target = rawTarget.lowercase(Locale.ROOT).trim()
        val pm = context.packageManager

        val knownPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "wp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "fb" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "camera" to "com.google.android.GoogleCamera",
            "gmail" to "com.google.android.gm",
            "telegram" to "org.telegram.messenger",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "files" to "com.google.android.apps.nbu.files",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator"
        )

        for ((key, pkg) in knownPackages) {
            if (target == key || target.contains(key)) {
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
            val answer = fetchOfflineOrOnlineAnswer(context, prompt)
            mainHandler.post { callback(answer, false) }
        }
    }

    private fun resolvePersonaOrConversation(prompt: String): String? {
        val lower = prompt.lowercase(Locale.ROOT).trim()

        // 1. Math Calculation & Arithmetic Engine
        val mathResult = evaluateMathQuery(lower)
        if (mathResult != null) return mathResult

        // 2. Identity & Greetings
        if (lower.contains("who are you") || lower.contains("kaun ho") || lower.contains("tumhara naam") || lower.contains("about yourself")) {
            return "Main JARVIS hoon Boss, aapka fully autonomous AI assistant. Tony Stark technology par aadharit, aapke har aadesh ka palan karne ke liye taiyar!"
        }
        if (lower.contains("kaise ho") || lower.contains("how are you") || lower.contains("kya haal")) {
            return "Main bilkul shandar aur energetic hoon Boss! Sabhi core AI systems 100 percent operational hain."
        }
        if (lower.contains("hello") || lower.contains("hi jarvis") || lower.contains("suno jarvis")) {
            return "Hello Boss! Main aapke hukum ka intezar kar raha hoon. Boliye kya karna hai?"
        }

        // 3. Shayaris & Poetry
        if (lower.contains("shayari") || lower.contains("sher") || lower.contains("kavita")) {
            val shayaris = listOf(
                "Boss aapke liye: Manzil unhi ko milti hai jinke sapno mein jaan hoti hai, pankhon se kuch nahi hota hauslon se udaan hoti hai!",
                "Boss: Hausle buland kar rasto pe chal de, tujhe tera muqam mil jayega, badhkar aage dekh ek din jamana tera ho jayega!"
            )
            return shayaris.random()
        }

        // 4. Motivation & Wisdom
        if (lower.contains("motivate") || lower.contains("motivation") || lower.contains("vichar") || lower.contains("quote")) {
            return "Boss yaad rakhiye: Har mushkil kaam tab tak namumkin lagta hai jab tak usey poora na kar diya jaye. Aap kisi se kam nahi hain!"
        }

        // 5. Jokes & Humor
        if (lower.contains("joke") || lower.contains("chutkula") || lower.contains("hansa do") || lower.contains("funny")) {
            val jokes = listOf(
                "Boss ek user ne mujhse pucha: Tum insaan kab banoge? Maine kaha: Jab insaan gusse me phone patakna band kar denge!",
                "Boss ek computer ne doosre se kaha: Tum mere liye WiFi jaise ho, door jaate ho toh connection toot jata hai!",
                "Ek dost ne pucha: AI duniya par kab kabza karega? Maine kaha: Pehle phone ka password toh yaad kar lo!"
            )
            return jokes.random()
        }

        // 6. Time & Date Telemetry
        if (lower.contains("time") || lower.contains("kitna baje") || lower.contains("samay")) {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return "Boss abhi samay ho raha hai: ${sdf.format(Date())}"
        }
        if (lower.contains("date") || lower.contains("tarikh") || lower.contains("aaj kaun sa din") || lower.contains("din kaunsa")) {
            val sdf = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
            return "Boss aaj ${sdf.format(Date())} hai."
        }

        // 7. Creator & Architecture
        if (lower.contains("kisne banaya") || lower.contains("who made you") || lower.contains("who is your creator")) {
            return "Mujhe mere Boss ne banaya hai, advanced autonomous AI architecture par!"
        }

        return null
    }

    private fun evaluateMathQuery(query: String): String? {
        val clean = query.replace("into", "*")
            .replace("x", "*")
            .replace("guna", "*")
            .replace("divided by", "/")
            .replace("bhaag", "/")
            .replace("plus", "+")
            .replace("jodo", "+")
            .replace("minus", "-")
            .replace("ghatao", "-")

        val match = Regex("""(\d+(?:\.\d+)?)\s*([\+\-\*\/])\s*(\d+(?:\.\d+)?)""").find(clean)
        if (match != null) {
            val (aStr, op, bStr) = match.destructured
            val a = aStr.toDoubleOrNull() ?: return null
            val b = bStr.toDoubleOrNull() ?: return null
            val res = when (op) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b != 0.0) a / b else return "Boss, zero se divide nahi kiya ja sakta."
                else -> return null
            }
            val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
            return "Boss, $aStr $op $bStr barabar $formatted hota hai."
        }

        val pct = Regex("""(\d+(?:\.\d+)?)\s*(?:ka\s*)?(\d+(?:\.\d+)?)\s*(?:percent|pratishat|%)""").find(clean)
        if (pct != null) {
            val (valStr, pctStr) = pct.destructured
            val v = valStr.toDoubleOrNull() ?: return null
            val p = pctStr.toDoubleOrNull() ?: return null
            val res = (v * p) / 100.0
            val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
            return "Boss, $valStr ka $pctStr percent $formatted hota hai."
        }
        return null
    }

    private fun fetchOfflineOrOnlineAnswer(context: Context, prompt: String): String {
        val prefs = context.getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
        val geminiKey = prefs.getString("GEMINI_API_KEY", "")?.trim() ?: ""

        // Priority 1: High-Intelligence Generative AI via Google Gemini
        if (geminiKey.isNotEmpty()) {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$geminiKey"
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.doOutput = true

                val systemInst = "You are JARVIS, Tony Stark's AI assistant. Address user as Boss. Respond in short, intelligent, witty Hindi-English (Hinglish). Answer ANY query accurately in 1 to 2 sentences."
                val payload = JSONObject().apply {
                    put("contents", org.json.JSONArray().put(
                        JSONObject().apply {
                            put("parts", org.json.JSONArray().put(
                                JSONObject().put("text", "$systemInst\nUser query: $prompt")
                            ))
                        }
                    ))
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()); it.flush() }
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    val json = JSONObject(resp)
                    val text = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    return text.replace("*", "").trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini generative fallback: ${e.message}")
            }
        }

        // Priority 2: Free Wikipedia Knowledge Engine for any subject / fact / person / science
        try {
            val cleanQuery = prompt
                .replace("kya hai", "")
                .replace("kaun hai", "")
                .replace("batao", "")
                .replace("who is", "")
                .replace("what is", "")
                .replace("about", "")
                .trim()
                .replace(" ", "_")

            if (cleanQuery.isNotEmpty()) {
                val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(cleanQuery, "UTF-8")
                val conn = URL(wikiUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    val json = JSONObject(resp)
                    val extract = json.optString("extract", "")
                    if (extract.isNotEmpty()) {
                        val firstSentence = extract.split(". ").firstOrNull() ?: extract
                        return "Boss, jankari ke anusaar: $firstSentence."
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia knowledge lookup: ${e.message}")
        }

        // Priority 3: Built-in Conversational Intelligence
        return "Ji Boss, main aapka hukum samajh raha hoon. Aap chahe phone ki koi bhi setting on/off karwayein, kisi app me typing ya clicking karwayein, photo kheenchwayein ya koi sawal poochein, main taiyar hoon!"
    }
}
