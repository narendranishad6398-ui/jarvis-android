package com.stark.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build

class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                sendVoiceBroadcast(context, "Power source connected, Boss. Charging initiated.")
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                sendVoiceBroadcast(context, "Disconnected from external power grid. Running on internal battery reserves, Boss.")
            }
            Intent.ACTION_BATTERY_LOW -> {
                sendVoiceBroadcast(context, "Warning Boss, auxiliary battery level is critically low. Please recharge immediately.")
            }
            Intent.ACTION_BATTERY_OKAY -> {
                sendVoiceBroadcast(context, "Power levels stabilized, Boss.")
            }
        }
    }

    private fun sendVoiceBroadcast(context: Context, message: String) {
        val serviceIntent = Intent(context, JarvisService::class.java).apply {
            putExtra("SPEAK_TEXT", message)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
