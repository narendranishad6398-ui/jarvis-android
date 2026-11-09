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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 200

    private lateinit var statusText: TextView
    private lateinit var serviceSwitch: Switch
    private lateinit var grantAllBtn: Button
    private lateinit var overlayPermBtn: Button
    private lateinit var batteryOptBtn: Button
    private var accessibilityBtn: Button? = null
    private lateinit var defaultAssistantBtn: Button
    private lateinit var geminiKeyBtn: Button
    private lateinit var testMicBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Wake screen when opened over lock screen
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

        // Safe identifier lookup for accessibilityBtn
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

        // Custom Voice Unlock Password Setup
        val voicePassId = resources.getIdentifier("voicePasswordBtn", "id", packageName)
        if (voicePassId != 0) {
            findViewById<Button>(voicePassId)?.setOnClickListener {
                showVoicePasswordDialog()
            }
        }

        grantAllBtn.setOnClickListener {
            handleGrantAllPermissions()
        }

        overlayPermBtn.setOnClickListener {
            requestOverlayPermission()
        }

        batteryOptBtn.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        defaultAssistantBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Select JARVIS in Device Assistant settings", Toast.LENGTH_SHORT).show()
            }
        }

        geminiKeyBtn.setOnClickListener {
            showGeminiKeyDialog()
        }

        testMicBtn.setOnClickListener {
            val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
                putExtra("EXTRA_START_LISTENING", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(overlayIntent)
        }

        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("SERVICE_ENABLED", isChecked).apply()

            if (isChecked) {
                startJarvisBackgroundService()
                statusText.text = "● 24/7 BACKGROUND MONITORING ACTIVE"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            } else {
                stopJarvisBackgroundService()
                statusText.text = "○ MONITORING STANDBY"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            refreshPermissionStates()
            handleGrantAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    private fun refreshPermissionStates() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val hasBatteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true

        val hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCallPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasAccessibility = JarvisAccessibilityService.isRunning()

        if (hasRecordAudio) {
            serviceSwitch.isChecked = true
            startJarvisBackgroundService()
        }

        // Update Overlay Button
        if (hasOverlay) {
            overlayPermBtn.text = "✅ 1. 'Display Over Apps' Allowed"
            overlayPermBtn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            overlayPermBtn.text = "⚠️ 1. Allow 'Display Over Other Apps'"
            overlayPermBtn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        }

        // Update Battery Button
        if (hasBatteryOpt) {
            batteryOptBtn.text = "✅ 2. Battery Saver Disabled (24/7 Active)"
            batteryOptBtn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            batteryOptBtn.text = "⚠️ 2. Disable Battery Saver"
            batteryOptBtn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        }

        // Update Accessibility Button
        accessibilityBtn?.let { btn ->
            if (hasAccessibility) {
                btn.text = "✅ 3. Phone & App Control (Active)"
                btn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            } else {
                btn.text = "⚠️ 3. Phone & App Control (Accessibility)"
                btn.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            }
        }

        val allGranted = hasOverlay && hasBatteryOpt && hasRecordAudio && hasCallPhone && hasContacts

        if (allGranted) {
            grantAllBtn.text = "✅ ALL CORE PERMISSIONS READY (ACTIVE)"
            grantAllBtn.setTextColor(ContextCompat.getColor(this, android.R.color.black))
            serviceSwitch.isChecked = true
            startJarvisBackgroundService()
            statusText.text = "● 24/7 BACKGROUND MONITORING ACTIVE"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            grantAllBtn.text = "⚡ GRANT ALL PERMISSIONS & START"
        }
    }

    private fun handleGrantAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            requestIgnoreBatteryOptimization()
            return
        }

        if (!JarvisAccessibilityService.isRunning()) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "JARVIS ko Accessibility mein ON karein taaki lock/app control chal sake!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {}
            return
        }

        serviceSwitch.isChecked = true
        startJarvisBackgroundService()
        Toast.makeText(this, "JARVIS is 100% active and listening for 'Hey Jarvis'!", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Please enable 'Display Over Other Apps' manually", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Please disable battery saver manually for JARVIS", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVoicePasswordDialog() {
        val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val currentPass = prefs.getString("CUSTOM_UNLOCK_PHRASE", "khul ja sim sim") ?: "khul ja sim sim"

        val input = EditText(this).apply {
            setText(currentPass)
            hint = "Jaise: khul ja sim sim, open sesame, jarvis unlock"
            setPadding(40, 30, 40, 30)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("🔑 Custom Voice Unlock Password")
            .setMessage("Apna secret voice password set karein. Lock screen par ye bolte hi turant screen unlock ho jayegi:")
            .setView(input)
            .setPositiveButton("Save Password") { _, _ ->
                val newPass = input.text.toString().trim()
                if (newPass.isNotEmpty()) {
                    prefs.edit().putString("CUSTOM_UNLOCK_PHRASE", newPass).apply()
                    Toast.makeText(this, "Voice Password Saved: '$newPass'", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGeminiKeyDialog() {
        val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("GEMINI_API_KEY", "") ?: ""

        val input = EditText(this).apply {
            hint = "AI works free without key. Optional custom key:"
            setText(currentKey)
            setPadding(40, 30, 40, 30)
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
}
