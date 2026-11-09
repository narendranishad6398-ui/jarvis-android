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
            // Test popup overlay immediately
            val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
                putExtra("EXTRA_USER_QUERY", "JARVIS Test Trigger")
                putExtra("EXTRA_ASSISTANT_REPLY", "Systems fully operational, Sir! Overlay working smoothly.")
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

        val allGranted = hasOverlay && hasBatteryOpt && hasRecordAudio && hasCallPhone && hasContacts

        if (allGranted) {
            grantAllBtn.text = "✅ ALL PERMISSIONS READY (ACTIVE)"
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
        // Step 1: Request runtime permissions
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

        // Step 2: Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        // Step 3: Battery Optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                requestIgnoreBatteryOptimization()
                return
            }
        }

        serviceSwitch.isChecked = true
        startJarvisBackgroundService()
        Toast.makeText(this, "JARVIS Activated! Say 'Hey Jarvis' anytime.", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable 'Display over other apps' for JARVIS", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Display over other apps already granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery optimization already disabled for JARVIS!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startJarvisBackgroundService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopJarvisBackgroundService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        stopService(serviceIntent)
    }

    private fun showGeminiKeyDialog() {
        val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("GEMINI_API_KEY", "") ?: ""

        val input = EditText(this).apply {
            setText(currentKey)
            hint = "Custom Gemini API Key (Optional)"
        }

        AlertDialog.Builder(this)
            .setTitle("🤖 JARVIS AI Brain Active")
            .setMessage("JARVIS bina kisi API key ke bilkul FREE baat karega aur sawalon ke jawab dega!\n\n(Optional) Agar aap apna custom Gemini API key lagana chahein to niche paste kar sakte hain:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                prefs.edit().putString("GEMINI_API_KEY", newKey).apply()
                Toast.makeText(this, if (newKey.isNotEmpty()) "Custom AI Key Saved!" else "Default Free AI Active!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("OK", null)
            .show()
    }
}
