package com.puli.islanddrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object Notifs {
    private const val CHANNEL_STATUS = "island_drop_status"
    private const val CHANNEL_TRANSFERS = "island_drop_transfers"

    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Receiver status", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, "Incoming files", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun ready(c: Context, status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(c, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Island Drop")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun progress(c: Context, t: Transfer) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(c, CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Receiving from ${t.sender}")
            .setContentText(t.summary)
            .setProgress(100, (t.progress * 100).toInt(), false)
            .setOngoing(true)
            .build()
        nm.notify(t.id, n)
    }

    fun done(c: Context, t: Transfer) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(c, CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Received from ${t.sender}")
            .setContentText(t.summary)
            .setAutoCancel(true)
            .build()
        nm.notify(t.id, n)
    }

    fun cancel(c: Context, id: Int) {
        c.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
