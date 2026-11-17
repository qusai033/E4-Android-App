package com.empatica.e4companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.empatica.e4companion.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service for maintaining BLE streaming.
 *
 * Uses foregroundServiceType="connectedDevice" as recommended for
 * always-connected BLE use cases on Android 14+ [3].
 *
 * Updates the notification every 5 seconds with current HR and
 * session duration so researchers can glance at the notification
 * shade without opening the app.
 */
class E4StreamingService : Service() {

    companion object {
        private const val TAG = "E4StreamingService"
        private const val CHANNEL_ID = "e4_streaming_channel"
        private const val CHANNEL_NAME = "E4 Streaming"
        private const val NOTIFICATION_ID = 1337

        // Shared state for notification updates
        var heartRateFlow: StateFlow<Float>? = null
        var sessionSecondsFlow: StateFlow<Long>? = null
        var deviceName: String = "E4"

        fun start(context: Context) {
            val intent = Intent(context, E4StreamingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, E4StreamingService::class.java)
            context.stopService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): E4StreamingService = this@E4StreamingService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Connecting…", ""))
        startNotificationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        scope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * Update notification every 5 seconds with live HR and duration.
     * Researchers can glance at the notification shade without opening the app.
     */
    private fun startNotificationUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                val hr = heartRateFlow?.value ?: 0f
                val seconds = sessionSecondsFlow?.value ?: 0L

                val hrText = if (hr > 0f) "❤ ${hr.toInt()} BPM" else "❤ --"
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                val secs = seconds % 60
                val timeText = if (hours > 0) {
                    "%d:%02d:%02d".format(hours, mins, secs)
                } else {
                    "%02d:%02d".format(mins, secs)
                }

                val notification = createNotification(
                    title = "$deviceName — $hrText",
                    text = "Recording: $timeText"
                )

                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)

                delay(5000)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "E4 wristband streaming service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}