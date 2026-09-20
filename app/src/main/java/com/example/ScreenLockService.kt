package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that counts down interval minutes and automatically locks the screen
 * using DevicePolicyManager.lockNow() when the interval expires.
 * Compatible with Android 8.0 (API 26) through the latest Android versions.
 */
class ScreenLockService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    private var currentIntervalMinutes: Int = 15
    private var secondsRemaining: Int = 15 * 60

    companion object {
        const val CHANNEL_ID = "screen_lock_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START_SCREEN_LOCK"
        const val ACTION_STOP = "com.example.action.STOP_SCREEN_LOCK"
        const val ACTION_UPDATE_INTERVAL = "com.example.action.UPDATE_INTERVAL"
        const val ACTION_LOCK_NOW = "com.example.action.LOCK_NOW"
        const val EXTRA_INTERVAL_MINUTES = "extra_interval_minutes"

        fun start(context: Context, intervalMinutes: Int) {
            val intent = Intent(context, ScreenLockService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INTERVAL_MINUTES, intervalMinutes)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenLockService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateInterval(context: Context, intervalMinutes: Int) {
            val intent = Intent(context, ScreenLockService::class.java).apply {
                action = ACTION_UPDATE_INTERVAL
                putExtra(EXTRA_INTERVAL_MINUTES, intervalMinutes)
            }
            context.startService(intent)
        }

        fun lockNow(context: Context) {
            val intent = Intent(context, ScreenLockService::class.java).apply {
                action = ACTION_LOCK_NOW
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ScreenLockTimer:ServiceWakeLock"
        )?.apply {
            setReferenceCounted(false)
            try {
                acquire(12 * 60 * 60 * 1000L) // 12 hours safety timeout
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfService()
                return START_NOT_STICKY
            }
            ACTION_LOCK_NOW -> {
                performScreenLock()
                return START_STICKY
            }
            ACTION_UPDATE_INTERVAL -> {
                val newInterval = intent.getIntExtra(EXTRA_INTERVAL_MINUTES, currentIntervalMinutes)
                currentIntervalMinutes = newInterval.coerceIn(1, 180)
                secondsRemaining = secondsRemaining.coerceAtMost(currentIntervalMinutes * 60)
                updateNotification()
                return START_STICKY
            }
            ACTION_START, null -> {
                val interval = intent?.getIntExtra(EXTRA_INTERVAL_MINUTES, currentIntervalMinutes) ?: currentIntervalMinutes
                currentIntervalMinutes = interval.coerceIn(1, 180)
                startForegroundWithNotification()
                startCountdownLoop()
                ScreenLockRepository.setServiceRunning(true)
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(secondsRemaining)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCountdownLoop() {
        timerJob?.cancel()
        secondsRemaining = currentIntervalMinutes * 60
        ScreenLockRepository.updateCountdown(secondsRemaining)

        timerJob = serviceScope.launch {
            var tickCount = 0
            while (isActive) {
                delay(1000L)
                secondsRemaining--
                tickCount++

                if (secondsRemaining <= 0) {
                    performScreenLock()
                    secondsRemaining = currentIntervalMinutes * 60
                    tickCount = 0
                    updateNotification()
                } else {
                    ScreenLockRepository.updateCountdown(secondsRemaining)
                    // Update notification periodically to conserve battery while showing progress
                    if (tickCount % 5 == 0 || secondsRemaining <= 10) {
                        updateNotification()
                    }
                }
            }
        }
    }

    private fun performScreenLock() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(this, ScreenLockAdminReceiver::class.java)

            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                ScreenLockRepository.recordLockEvent(applicationContext)
            } else {
                ScreenLockRepository.updateAdminStatus(false)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(remainingSecs: Int): Notification {
        val mins = remainingSecs / 60
        val secs = remainingSecs % 60
        val formattedTime = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenLockService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockNowIntent = Intent(this, ScreenLockService::class.java).apply {
            action = ACTION_LOCK_NOW
        }
        val lockNowPendingIntent = PendingIntent.getService(
            this,
            2,
            lockNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Screen Lock Active (${currentIntervalMinutes}m interval)")
            .setContentText("Next lock in $formattedTime")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_lock_power_off, "Lock Now", lockNowPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn Off", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        try {
            val notification = buildNotification(secondsRemaining)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun stopSelfService() {
        timerJob?.cancel()
        timerJob = null
        ScreenLockRepository.setServiceRunning(false)
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfService()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
