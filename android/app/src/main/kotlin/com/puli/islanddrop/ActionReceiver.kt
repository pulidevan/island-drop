package com.puli.islanddrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val accept = intent.getBooleanExtra(EXTRA_ACCEPT, false)
        Transfer.active[id]?.decide(accept)
        Notifs.cancel(context, id)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_ACCEPT = "accept"
        const val ACTION_DECIDE = "com.puli.islanddrop.ACTION_DECIDE"
    }
}
