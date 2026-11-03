package com.puli.islanddrop

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Surfaces an incoming [Transfer] to the user. A foreground activity can register itself as
 * visible (in which case it is expected to show its own in-app card); otherwise we fall back
 * to a heads-up notification with Accept/Decline actions.
 */
object Presenter {
    private val visible = CopyOnWriteArraySet<Int>()
    private val listeners = CopyOnWriteArraySet<(Transfer) -> Unit>()

    fun observe(l: (Transfer) -> Unit) = listeners.add(l)
    fun unobserve(l: (Transfer) -> Unit) = listeners.remove(l)

    fun markVisible(id: Int, isVisible: Boolean) {
        if (isVisible) visible.add(id) else visible.remove(id)
    }

    fun isVisible(id: Int): Boolean = id in visible

    fun present(c: Context, t: Transfer) {
        listeners.forEach { it(t) }
        if (t.kind == Transfer.Kind.FILES && t.state == Transfer.State.ASKING) {
            showAskNotification(c, t)
        }
    }

    private fun showAskNotification(c: Context, t: Transfer) {
        fun actionIntent(accept: Boolean) = PendingIntent.getBroadcast(
            c, t.id * 2 + (if (accept) 1 else 0),
            Intent(c, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_DECIDE
                putExtra(ActionReceiver.EXTRA_ID, t.id)
                putExtra(ActionReceiver.EXTRA_ACCEPT, accept)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            c, t.id, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(c, "island_drop_transfers")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("${t.sender} wants to send ${t.summary}")
            .setContentText("Tap Accept to receive")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "Decline", actionIntent(false))
            .addAction(0, "Accept", actionIntent(true))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(c).notify(t.id, n)
        }
    }
}
