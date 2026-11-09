package com.stark.jarvis

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AssistantOverlayActivity : AppCompatActivity() {

    private lateinit var overlayRoot: View
    private lateinit var overlayUserText: TextView
    private lateinit var overlayResponseText: TextView
    private lateinit var lensBtn: Button
    private lateinit var overlayMicBtn: Button
    private lateinit var closeBtn: Button

    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable {
        finishAndDismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant_overlay)

        // Ensure window pops up over lockscreen and wakes screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        overlayRoot = findViewById(R.id.overlayRoot)
        overlayUserText = findViewById(R.id.overlayUserText)
        overlayResponseText = findViewById(R.id.overlayResponseText)
        lensBtn = findViewById(R.id.lensBtn)
        overlayMicBtn = findViewById(R.id.overlayMicBtn)
        closeBtn = findViewById(R.id.closeBtn)

        val query = intent.getStringExtra("EXTRA_USER_QUERY") ?: "Listening..."
        val reply = intent.getStringExtra("EXTRA_ASSISTANT_REPLY") ?: "At your service, Sir."
        val isQuickDismiss = intent.getBooleanExtra("EXTRA_QUICK_DISMISS", false)

        overlayUserText.text = query
        overlayResponseText.text = reply

        // 1. Tap on dimmed outside area to immediately dismiss
        overlayRoot.setOnClickListener {
            finishAndDismiss()
        }

        // 2. Close button
        closeBtn.setOnClickListener {
            finishAndDismiss()
        }

        // 3. 📷 Google Lens / Visual Camera Mode
        lensBtn.setOnClickListener {
            launchGoogleLensOrCamera()
            finishAndDismiss()
        }

        // 4. Tap mic to re-listen
        overlayMicBtn.setOnClickListener {
            restartListeningService()
            overlayUserText.text = "Listening..."
            overlayResponseText.text = "Haan Sir, boliye..."
            resetAutoDismissTimer(5000)
        }

        // 5. Google Assistant / Gemini Auto-Dismiss:
        // Closes automatically so phone screen is never stuck!
        val delay = if (isQuickDismiss) 1200L else 3200L
        resetAutoDismissTimer(delay)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val query = intent.getStringExtra("EXTRA_USER_QUERY") ?: "Listening..."
        val reply = intent.getStringExtra("EXTRA_ASSISTANT_REPLY") ?: "At your service, Sir."
        val isQuickDismiss = intent.getBooleanExtra("EXTRA_QUICK_DISMISS", false)

        overlayUserText.text = query
        overlayResponseText.text = reply

        val delay = if (isQuickDismiss) 1200L else 3200L
        resetAutoDismissTimer(delay)
    }

    private fun resetAutoDismissTimer(delayMillis: Long) {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, delayMillis)
    }

    private fun finishAndDismiss() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun launchGoogleLensOrCamera() {
        val pm: PackageManager = packageManager

        // Attempt 1: Launch Google Lens App directly
        val lensIntent = pm.getLaunchIntentForPackage("com.google.ar.lens")
        if (lensIntent != null) {
            lensIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(lensIntent)
            return
        }

        // Attempt 2: Launch Google App Lens Intent
        try {
            val lensActionIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.android.googlequicksearchbox")
                data = Uri.parse("googleapp://lens")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(lensActionIntent)
            return
        } catch (e: Exception) {}

        // Attempt 3: Native Camera capture
        try {
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(cameraIntent)
        } catch (e: Exception) {}
    }

    private fun restartListeningService() {
        val serviceIntent = Intent(this, JarvisService::class.java).apply {
            putExtra("FORCE_LISTEN", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
    }
}
