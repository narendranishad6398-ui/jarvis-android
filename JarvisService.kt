package com.stark.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class JarvisService : Service() {

    private val TAG = "JarvisService"
    private val CHANNEL_ID = "JarvisForegroundChannel"
    private val NOTIFICATION_ID = 1001

    private var wakeLock: PowerManager.WakeLock? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isMonitoring = false
    @Volatile private var isOverlayActive = false
    @Volatile private var isPhoneCallActive = false

    private var monitorThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.stark.jarvis.OVERLAY_DISMISSED") {
                Log.d(TAG, "Overlay dismissed broadcast received. Resuming continuous ear.")
                mainHandler.postDelayed({
                    isOverlayActive = false
                }, 600)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis Service initialized.")

        // Acquire WakeLock so CPU never sleeps
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::BackgroundAudioLock")
        try {
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire error: ${e.message}")
        }

        // Register dismiss receiver
        val filter = IntentFilter("com.stark.jarvis.OVERLAY_DISMISSED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayReceiver, filter)
        }

        setupPhoneStateListener()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Active: Voice detection running 24/7"))

        startContinuousEar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isMonitoring) {
            startContinuousEar()
        }
        return START_STICKY
    }

    private fun setupPhoneStateListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    super.onCallStateChanged(state, phoneNumber)
                    if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                        isPhoneCallActive = true
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                        isPhoneCallActive = false
                    }
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "Telephony listener error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS AI Protocol",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS listening 24/7 without opening the app"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        val openIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
            putExtra("EXTRA_START_LISTENING", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Protocol Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Continuous Hardware Ear
     * Reads PCM audio continuously.
     * Uses dynamic noise-floor tracking and RMS thresholding to detect human voice hands-free.
     */
    private fun startContinuousEar() {
        if (isMonitoring) return

        val hasAudioPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) {
            Log.e(TAG, "Audio permission not granted for continuous ear.")
            return
        }

        isMonitoring = true
        monitorThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = Math.max(minBufSize, 2048)
            val audioBuffer = ShortArray(bufferSize)

            var ambientFloor = 600.0
            var speechFrameCount = 0

            while (isMonitoring) {
                try {
                    if (isOverlayActive || isPhoneCallActive) {
                        // Release mic hardware so overlay or phone call has 100% clean mic access
                        if (audioRecord != null) {
                            try {
                                audioRecord?.stop()
                                audioRecord?.release()
                            } catch (e: Exception) {}
                            audioRecord = null
                        }
                        Thread.sleep(300)
                        continue
                    }

                    // Initialize AudioRecord if needed
                    if (audioRecord == null) {
                        try {
                            audioRecord = AudioRecord(
                                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                sampleRate,
                                channelConfig,
                                audioFormat,
                                bufferSize * 2
                            )
                        } catch (e: Exception) {
                            // Fallback to standard MIC
                            audioRecord = AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                channelConfig,
                                audioFormat,
                                bufferSize * 2
                            )
                        }

                        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                            audioRecord?.release()
                            audioRecord = null
                            Thread.sleep(1000)
                            continue
                        }

                        audioRecord?.startRecording()
                        Log.d(TAG, "Hardware continuous ear started successfully.")
                    }

                    val readShorts = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readShorts > 0) {
                        var sum = 0.0
                        for (i in 0 until readShorts) {
                            sum += audioBuffer[i] * audioBuffer[i]
                        }
                        val rms = Math.sqrt(sum / readShorts)

                        // Update dynamic ambient noise floor
                        ambientFloor = (ambientFloor * 0.93) + (rms * 0.07)

                        // Voice energy detection: sustained voice above background noise
                        val speechThreshold = Math.max(2000.0, ambientFloor * 2.1)
                        if (rms > speechThreshold) {
                            speechFrameCount++
                            // Require 2 consecutive frames (~200ms) of sustained human voice to avoid random clicks
                            if (speechFrameCount >= 2) {
                                speechFrameCount = 0
                                Log.d(TAG, "Human voice detected! RMS: $rms, Ambient: $ambientFloor. Waking up JARVIS...")
                                triggerWakeAssistant()
                            }
                        } else {
                            if (speechFrameCount > 0) {
                                speechFrameCount = 0
                            }
                        }
                    } else {
                        Thread.sleep(20)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Continuous ear error: ${e.message}")
                    try { Thread.sleep(500) } catch (ignored: Exception) {}
                }
            }

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {}
            audioRecord = null
            Log.d(TAG, "Continuous ear stopped.")
        }, "JARVIS_Continuous_Ear_Thread")

        monitorThread?.start()
    }

    private fun triggerWakeAssistant() {
        mainHandler.post {
            try {
                isOverlayActive = true

                // 1. Double Haptic feedback
                triggerVibrateFeedback()

                // 2. Wake screen
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val screenLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "JARVIS::ScreenWake"
                )
                screenLock.acquire(4000)

                // 3. Launch AssistantOverlayActivity
                val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
                    putExtra("EXTRA_START_LISTENING", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(overlayIntent)

                // 4. Also fire High-Priority FullScreenIntent for locked screens
                val pendingIntent = PendingIntent.getActivity(
                    this, 101, overlayIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val wakeNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("JARVIS Protocol")
                    .setContentText("Listening to your voice command...")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pendingIntent, true)
                    .setAutoCancel(true)
                    .build()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(2002, wakeNotification)
                mainHandler.postDelayed({ manager.cancel(2002) }, 4000)

                // Safeguard: auto-reset isOverlayActive after 12s in case activity closed abruptly
                mainHandler.postDelayed({
                    if (isOverlayActive) {
                        isOverlayActive = false
                    }
                }, 12000)

            } catch (e: Exception) {
                Log.e(TAG, "Trigger wake assistant error: ${e.message}")
                isOverlayActive = false
            }
        }
    }

    private fun triggerVibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), intArrayOf(0, 200, 0, 255), -1)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), intArrayOf(0, 200, 0, 255), -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(180)
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        isOverlayActive = false
        try { telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE) } catch (e: Exception) {}
        try { unregisterReceiver(overlayReceiver) } catch (e: Exception) {}
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {}
        Log.d(TAG, "Jarvis Service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
