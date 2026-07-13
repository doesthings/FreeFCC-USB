package com.freefcc.n1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the USB/TCP transport alive while FCC mode
 * is being applied or maintained. On USB accessory mode, releasing the
 * activity's focus can cause the OS to revoke the accessory handle —
 * running as a foreground service prevents that.
 *
 * This mirrors the standard Android foreground-service pattern, stripped down
 * to just the essentials: create the channel, start foreground, stay alive.
 */
class DumlForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "freefcc_n1_transport"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FCC Transport",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the USB/TCP link to the controller alive during FCC apply"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FreeFCC-N1")
            .setContentText("Maintaining controller link")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }
}