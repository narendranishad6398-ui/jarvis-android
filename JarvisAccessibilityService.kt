package com.stark.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

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

    /**
     * Executes standard Android system global actions:
     * Home, Back, Recents, Notifications, Quick Settings, Lock Screen, Screenshot, Power Dialog
     */
    fun performAction(actionId: Int): Boolean {
        return performGlobalAction(actionId)
    }

    /**
     * Direct Screen Lock
     */
    fun lockPhoneScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    /**
     * Pull down Control Panel / Quick Settings
     */
    fun openControlPanel(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /**
     * Pull down Notification Shade
     */
    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * Open Power Off / Restart Menu
     */
    fun openPowerDialog(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    /**
     * Take a screenshot hands-free
     */
    fun captureScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    /**
     * Automated swipe up to unlock screen
     */
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

    /**
     * Scroll down in any app (Reels, Instagram, YouTube, Browser, WhatsApp)
     */
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

    /**
     * Scroll up in any active app
     */
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

    /**
     * Type text into currently focused or editable input field in any app
     */
    fun typeTextInFocusedField(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        // Recursive search for any editable field
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

    /**
     * Click any button or element on screen matching target text or description in any app
     */
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

    /**
     * Click Camera Shutter / Take Photo in camera app
     */
    fun clickShutterButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val shutterKeywords = listOf("shutter", "capture", "take photo", "camera", "photo", "record", "snap", "click photo")
        for (kw in shutterKeywords) {
            if (findAndClickNodeRecursive(root, kw)) {
                return true
            }
        }
        return false
    }

    /**
     * Disconnect / Cut active phone call
     */
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
