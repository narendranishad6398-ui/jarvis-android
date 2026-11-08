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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 200

    private lateinit var statusText: TextView
    private lateinit var serviceSwitch: Switch
    private lateinit var batteryOptBtn: Button
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
        batteryOptBtn = findViewById(R.id.batteryOptBtn)
        testMicBtn = findViewById(R.id.testMicBtn)

        checkAndRequestPermissions()

        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startJarvisBackgroundService()
                statusText.text = "● 24/7 SCREEN-OFF MONITORING ACTIVE"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            } else {
                stopJarvisBackgroundService()
                statusText.text = "○ MONITORING STANDBY"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
        }

        batteryOptBtn.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        testMicBtn.setOnClickListener {
            Toast.makeText(this, "Say 'Hey Jarvis' or 'Jarvis' clearly", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

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

    private fun startJarvisBackgroundService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "JARVIS Background Service Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopJarvisBackgroundService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "JARVIS Service Stopped", Toast.LENGTH_SHORT).show()
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
}
