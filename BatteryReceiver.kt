package com.stark.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

/**
 * Battery and Charging State Receiver.
 * Silent and battery-friendly:
 * Does NOT wake up speech recognizer on charger connect to prevent unwanted ear activation and beeping.
 */
class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val prefs = context.getSharedPreferences("JARVIS_PREFS", Context.MODE_PRIVATE)
        val allowChargingVoice = prefs.getBoolean("CHARGING_VOICE_ALERT", false)

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                if (allowChargingVoice) {
                    val serviceIntent = Intent(context, JarvisService::class.java).apply {
                        putExtra("SPEAK_TEXT", "Power connected, Boss. Charging initiated.")
                    }
                    try { context.startService(serviceIntent) } catch (ignored: Exception) {}
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                if (allowChargingVoice) {
                    val serviceIntent = Intent(context, JarvisService::class.java).apply {
                        putExtra("SPEAK_TEXT", "Disconnected from power grid, Boss.")
                    }
                    try { context.startService(serviceIntent) } catch (ignored: Exception) {}
                }
            }
            Intent.ACTION_BATTERY_LOW -> {
                val serviceIntent = Intent(context, JarvisService::class.java).apply {
                    putExtra("SPEAK_TEXT", "Warning Boss, battery level is below 15 percent.")
                }
                try { context.startService(serviceIntent) } catch (ignored: Exception) {}
            }
        }
    }
}
