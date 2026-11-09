package com.stark.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * JARVIS Main Command Dashboard
 * - Apple iOS Liquid Glass Aesthetic.
 * - Custom Voice Lock & Unlock Command Center.
 * - Gemini Live Camera Optical Vision Scanner.
 * - Master One-Click Permissions Setup.
 */
class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 200

    private lateinit var statusText: TextView
    private lateinit var serviceSwitch: SwitchCompat
    private lateinit var grantAllBtn: Button
    private lateinit var overlayPermBtn: Button
    private lateinit var batteryOptBtn: Button
    private var accessibilityBtn: Button? = null
    private lateinit var defaultAssistantBtn: Button
    private lateinit var geminiKeyBtn: Button
    private lateinit var testMicBtn: Button

    private var customUnlockInput: EditText? = null
    private var customLockInput: EditText? = null
    private var voicePasswordBtn: Button? = null
    private var liveCameraBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        statusText = findViewById(R.id.statusText)
        serviceSwitch = findViewById(R.id.serviceSwitch)
        grantAllBtn = findViewById(R.id.grantAllBtn)
        overlayPermBtn = findViewById(R.id.overlayPermBtn)
        batteryOptBtn = findViewById(R.id.batteryOptBtn)
        defaultAssistantBtn = findViewById(R.id.defaultAssistantBtn)
        geminiKeyBtn = findViewById(R.id.geminiKeyBtn)
        testMicBtn = findViewById(R.id.testMicBtn)

        val btnId = resources.getIdentifier("accessibilityBtn", "id", packageName)
        if (btnId != 0) {
            accessibilityBtn = findViewById(btnId)
            accessibilityBtn?.setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this, "Turn ON 'JARVIS' in Accessibility settings for full phone control!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Open Accessibility in Phone Settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Custom Voice Lock & Unlock Inputs Setup
        setupCustomVoiceCommandCenter()

        // Gemini Live Camera Vision Button Setup
        val camBtnId = resources.getIdentifier("liveCameraBtn", "id", packageName)
        if (camBtnId != 0) {
            liveCameraBtn = findViewById(camBtnId)
            liveCameraBtn?.setOnClickListener {
                CommandExecutor.launchGoogleLensOrCamera(this) { reply, _ ->
                    Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
                }
            }
        }

        setupButtons()
        checkAndRequestPermissions()
        startJarvisBackgroundService()
    }

    private fun setupCustomVoiceCommandCenter() {
        val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)

        val unlockInputId = resources.getIdentifier("customUnlockInput", "id", packageName)
        if (unlockInputId != 0) {
            customUnlockInput = findViewById(unlockInputId)
            val currentUnlock = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim")
            customUnlockInput?.setText(currentUnlock)
        }

        val lockInputId = resources.getIdentifier("customLockInput", "id", packageName)
        if (lockInputId != 0) {
            customLockInput = findViewById(lockInputId)
            val currentLock = prefs.getString("CUSTOM_LOCK_PHRASE", "phone lock karo")
            customLockInput?.setText(currentLock)
        }

        val voiceSaveId = resources.getIdentifier("voicePasswordBtn", "id", packageName)
        if (voiceSaveId != 0) {
            voicePasswordBtn = findViewById(voiceSaveId)
            voicePasswordBtn?.setOnClickListener {
                val newUnlock = customUnlockInput?.text?.toString()?.trim() ?: "khul ja sim sim"
                val newLock = customLockInput?.text?.toString()?.trim() ?: "phone lock karo"

                prefs.edit()
                    .putString("CUSTOM_UNLOCK_PHRASE", newUnlock)
                    .putString("CUSTOM_LOCK_PHRASE", newLock)
                    .apply()

                Toast.makeText(this, "Custom Voice Passwords Saved!\nUnlock: '$newUnlock'\nLock: '$newLock'", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupButtons() {
        grantAllBtn.setOnClickListener {
            checkAndRequestPermissions()
            requestOverlayPermission()
            requestIgnoreBatteryOptimizations()
            Toast.makeText(this, "All Protocols Activated! Say 'Hey Jarvis' or your custom voice password.", Toast.LENGTH_LONG).show()
        }

        testMicBtn.setOnClickListener {
            val intent = Intent(this, AssistantOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }

        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startJarvisBackgroundService()
                statusText.text = "● 24/7 Silent Ear • Standby"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            } else {
                stopJarvisBackgroundService()
                statusText.text = "○ JARVIS Offline"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            }
        }

        overlayPermBtn.setOnClickListener { requestOverlayPermission() }
        batteryOptBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }

        defaultAssistantBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (ex: Exception) {
                    Toast.makeText(this, "Set JARVIS as Default Assistant in Phone Settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        geminiKeyBtn.setOnClickListener { showGeminiKeyDialog() }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Please enable overlay permission manually in settings", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Overlay permission already active!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Please disable battery saver manually for JARVIS", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Battery optimization already disabled!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showGeminiKeyDialog() {
        val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("GEMINI_API_KEY", "") ?: ""

        val input = EditText(this).apply {
            hint = "AI works free without key. Optional custom key:"
            setText(currentKey)
            setPadding(40, 30, 40, 30)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("AI Brain Configuration")
            .setMessage("JARVIS already has built-in free AI brain. You do not need any key. If you have a custom key, you can enter it below:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                prefs.edit().putString("GEMINI_API_KEY", newKey).apply()
                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startJarvisBackgroundService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopJarvisBackgroundService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        stopService(serviceIntent)
    }

    fun showVoicePasswordDialog() {
        val prefs = getSharedPreferences("JARVIS_PREFS", MODE_PRIVATE)
        val currentUnlock = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim")
        val currentLock = prefs.getString("CUSTOM_LOCK_PHRASE", "phone lock karo")
        android.widget.Toast.makeText(this, "Unlock: " + currentUnlock + " / Lock: " + currentLock, android.widget.Toast.LENGTH_LONG).show()
    }
}
