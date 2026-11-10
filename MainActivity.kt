package com.stark.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * JARVIS Command Deck & Control Center
 * - Liquid Frosted Glass UI.
 * - Direct 1-Tap Hardware Toggles (Net, Wi-Fi, Hotspot, Bluetooth, Torch, Lock, Camera, Silent).
 * - Custom Voice Command Center (Custom Wake-Word, Voice Screen Unlock & Lock).
 * - Silent Hands-Free Ear Engine (Zero Beep).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var grantAllBtn: Button
    private lateinit var accessibilityBtn: Button
    private lateinit var testMicBtn: Button
    private lateinit var liveCameraBtn: Button
    private lateinit var geminiKeyBtn: Button

    // Hardware Toggles
    private var btnDataToggle: Button? = null
    private var btnWifiToggle: Button? = null
    private var btnHotspotToggle: Button? = null
    private var btnBtToggle: Button? = null
    private var btnTorchToggle: Button? = null
    private var btnLockToggle: Button? = null
    private var btnCameraToggle: Button? = null
    private var btnRotateToggle: Button? = null
    private var btnGpsToggle: Button? = null
    private var btnDarkToggle: Button? = null
    private var btnBatterySaverToggle: Button? = null
    private var btnDndToggle: Button? = null

    // Custom Voice Command Center
    private var customWakeWordInput: EditText? = null
    private var customUnlockInput: EditText? = null
    private var customLockInput: EditText? = null
    private var screenPinInput: EditText? = null
    private var voicePasswordBtn: Button? = null
    private var silentEarSwitch: Switch? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startJarvisService()
            checkBatteryOptimization()
            checkAccessibilityStatus()
        } else {
            Toast.makeText(this, "Permissions needed for JARVIS voice & phone control protocols.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        grantAllBtn = findViewById(R.id.grantAllBtn)
        accessibilityBtn = findViewById(R.id.accessibilityBtn)
        testMicBtn = findViewById(R.id.testMicBtn)
        liveCameraBtn = findViewById(R.id.liveCameraBtn)
        geminiKeyBtn = findViewById(R.id.geminiKeyBtn)

        setupHardwareToggles()
        setupCustomVoiceCommandCenter()

        grantAllBtn.setOnClickListener {
            requestAllPermissions()
        }

        accessibilityBtn.setOnClickListener {
            openAccessibilitySettings()
        }

        liveCameraBtn.setOnClickListener {
            openGoogleLensOrCamera()
        }

        geminiKeyBtn.setOnClickListener {
            showGeminiKeyDialog()
        }

        autoStartJarvisServiceIfReady()

        testMicBtn.setOnClickListener {
            val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
                putExtra("EXTRA_START_LISTENING", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(overlayIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityStatus()
        autoStartJarvisServiceIfReady()
    }

    private fun autoStartJarvisServiceIfReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startJarvisService()
        }
    }

    private fun setupHardwareToggles() {
        btnDataToggle = findViewById(R.id.btnDataToggle)
        btnWifiToggle = findViewById(R.id.btnWifiToggle)
        btnHotspotToggle = findViewById(R.id.btnHotspotToggle)
        btnBtToggle = findViewById(R.id.btnBtToggle)
        btnTorchToggle = findViewById(R.id.btnTorchToggle)
        btnLockToggle = findViewById(R.id.btnLockToggle)
        btnCameraToggle = findViewById(R.id.btnCameraToggle)
        btnRotateToggle = findViewById(R.id.btnRotateToggle)
        btnGpsToggle = findViewById(R.id.btnGpsToggle)
        btnDarkToggle = findViewById(R.id.btnDarkToggle)
        btnBatterySaverToggle = findViewById(R.id.btnBatterySaverToggle)
        btnDndToggle = findViewById(R.id.btnDndToggle)

        btnDataToggle?.setOnClickListener {
            CommandExecutor.execute(this, "mobile data") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnWifiToggle?.setOnClickListener {
            CommandExecutor.execute(this, "wifi") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnHotspotToggle?.setOnClickListener {
            CommandExecutor.execute(this, "hotspot") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnBtToggle?.setOnClickListener {
            CommandExecutor.execute(this, "bluetooth") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnTorchToggle?.setOnClickListener {
            CommandExecutor.execute(this, "torch") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnLockToggle?.setOnClickListener {
            CommandExecutor.execute(this, "phone lock") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnCameraToggle?.setOnClickListener {
            CommandExecutor.execute(this, "photo kheencho") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnRotateToggle?.setOnClickListener {
            CommandExecutor.execute(this, "rotate") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnGpsToggle?.setOnClickListener {
            CommandExecutor.execute(this, "gps") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnDarkToggle?.setOnClickListener {
            CommandExecutor.execute(this, "dark mode") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnBatterySaverToggle?.setOnClickListener {
            CommandExecutor.execute(this, "battery saver") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }

        btnDndToggle?.setOnClickListener {
            CommandExecutor.execute(this, "dnd") { reply, _ ->
                Toast.makeText(this, reply, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupCustomVoiceCommandCenter() {
        customWakeWordInput = findViewById(R.id.customWakeWordInput)
        customUnlockInput = findViewById(R.id.customUnlockInput)
        customLockInput = findViewById(R.id.customLockInput)
        screenPinInput = findViewById(R.id.screenPinInput)
        voicePasswordBtn = findViewById(R.id.voicePasswordBtn)
        silentEarSwitch = findViewById(R.id.silentEarSwitch)

        val prefs = getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
        val savedWake = prefs.getString("CUSTOM_WAKE_WORD", "") ?: ""
        val savedUnlock = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim") ?: "khul ja sim sim"
        val savedLock = prefs.getString("CUSTOM_LOCK_PHRASE", "phone lock karo") ?: "phone lock karo"
        val savedPin = prefs.getString("SCREEN_PIN", "") ?: ""
        val isSilent = prefs.getBoolean("SILENT_EAR_MODE", true)

        if (savedWake.isNotEmpty()) customWakeWordInput?.setText(savedWake)
        customUnlockInput?.setText(savedUnlock)
        customLockInput?.setText(savedLock)
        if (savedPin.isNotEmpty()) screenPinInput?.setText(savedPin)
        silentEarSwitch?.isChecked = isSilent

        silentEarSwitch?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("SILENT_EAR_MODE", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Silent Hands-Free Ear Active (Zero Beep)" else "Chime Beep Active", Toast.LENGTH_SHORT).show()
        }

        voicePasswordBtn?.setOnClickListener {
            val newWake = customWakeWordInput?.text?.toString()?.trim() ?: ""
            val newUnlock = customUnlockInput?.text?.toString()?.trim() ?: "khul ja sim sim"
            val newLock = customLockInput?.text?.toString()?.trim() ?: "phone lock karo"
            val newPin = screenPinInput?.text?.toString()?.trim() ?: ""

            prefs.edit()
                .putString("CUSTOM_WAKE_WORD", newWake)
                .putString("CUSTOM_UNLOCK_PHRASE", newUnlock)
                .putString("CUSTOM_LOCK_PHRASE", newLock)
                .putString("SCREEN_PIN", newPin)
                .apply()

            val msg = "Custom Voice Commands Saved!\nWake: ${if (newWake.isNotEmpty()) newWake else "Hey Jarvis"}\nUnlock: '$newUnlock'\nLock: '$newLock'"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun showVoicePasswordDialog() {
        val prefs = getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
        val currentUnlock = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim")
        val currentLock = prefs.getString("CUSTOM_LOCK_PHRASE", "phone lock karo")
        Toast.makeText(this, "Unlock: " + currentUnlock + " / Lock: " + currentLock, Toast.LENGTH_LONG).show()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startJarvisService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusText.text = "JARVIS Hands-Free Ear: ONLINE"
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        Toast.makeText(this, "JARVIS Online! Say 'Hey Jarvis' or your voice password.", Toast.LENGTH_LONG).show()
    }

    private fun checkAccessibilityStatus() {
        if (JarvisAccessibilityService.isRunning()) {
            accessibilityBtn.text = "ACCESSIBILITY SERVICE: ACTIVE"
            accessibilityBtn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            accessibilityBtn.text = "ENABLE ACCESSIBILITY (FULL PHONE CONTROL)"
            accessibilityBtn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Tap 'JARVIS' or 'Installed Services' and turn switch ON.", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun openGoogleLensOrCamera() {
        try {
            val lensIntent = Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(lensIntent)
        } catch (e: Exception) {
            val cameraIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(cameraIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Camera not accessible.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showGeminiKeyDialog() {
        val prefs = getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("GEMINI_API_KEY", "") ?: ""

        val input = EditText(this).apply {
            hint = "Paste Google Gemini API Key here"
            setText(currentKey)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Gemini Live Vision & Brain Key")
            .setMessage("Optional: Enter your free Gemini API key from ai.google.dev for unlimited AI intelligence.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                prefs.edit().putString("GEMINI_API_KEY", newKey).apply()
                Toast.makeText(this, "API Key Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
