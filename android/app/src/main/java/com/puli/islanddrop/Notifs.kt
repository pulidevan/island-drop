package com.puli.islanddrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.Formatter

object Notifs {
    const val READY_ID = 1
    private const val CH_READY = "ready"
    private const val CH_INCOMING = "incoming"
    private const val CH_DONE = "done"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun ensureChannels(c: Context) {
        val nm = nm(c)
        nm.createNotificationChannel(NotificationChannel(CH_READY, "Receiver", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps Island Drop discoverable by your Mac"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming files", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pop-up when your Mac sends something"
            setShowBadge(false)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Received files", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun ready(c: Context, text: String): Notification =
        Notification.Builder(c, CH_READY)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Island Drop")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(c))
            .build()

    fun updateReady(c: Context, text: String) = nm(c).notify(READY_ID, ready(c, text))

    fun incoming(c: Context, t: Transfer) {
        val full = PendingIntent.getActivity(
            c, t.id, PopupActivity.intent(c, t.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (t.kind) {
            Transfer.Kind.LINK -> "Shared a link · ${t.summary}"
            else -> "${t.summary} · ${Formatter.formatShortFileSize(c, t.total)}"
        }
        val b = Notification.Builder(c, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t.sender)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
        if (t.kind == Transfer.Kind.FILES && t.state == Transfer.State.ASKING) {
            b.addAction(Notification.Action.Builder(null, "Decline", action(c, t, false)).build())
            b.addAction(Notification.Action.Builder(null, "Accept", action(c, t, true)).build())
        }
        nm(c).notify(t.id, b.build())
    }

    fun progress(c: Context, t: Transfer) {
        val pct = (t.progress * 100).toInt()
        nm(c).notify(t.id, Notification.Builder(c, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Receiving from ${t.sender}")
            .setContentText(t.summary)
            .setProgress(100, pct, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build())
    }

    fun done(c: Context, t: Transfer) {
        if (Presenter.isVisible(t.id)) { cancel(c, t.id); return }
        val uri = t.saved.firstOrNull() ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.primaryMime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        nm(c).notify(t.id, Notification.Builder(c, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Received from ${t.sender}")
            .setContentText("${t.summary} · Downloads/Island Drop")
            .setContentIntent(PendingIntent.getActivity(c, t.id, view, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }

    fun cancel(c: Context, id: Int) = nm(c).cancel(id)

    private fun action(c: Context, t: Transfer, accept: Boolean): PendingIntent {
        val i = Intent(c, ActionReceiver::class.java)
            .setAction(if (accept) ActionReceiver.ACCEPT else ActionReceiver.DECLINE)
            .putExtra("id", t.id)
        return PendingIntent.getBroadcast(c, t.id * 2 + if (accept) 1 else 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val t = Transfer.active[i.getIntExtra("id", -1)] ?: return
        t.decide(i.action == ACCEPT)
        Notifs.cancel(c, t.id)
    }

    companion object {
        const val ACCEPT = "com.puli.islanddrop.ACCEPT"
        const val DECLINE = "com.puli.islanddrop.DECLINE"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { ReceiverService.start(c) }
    }
}
