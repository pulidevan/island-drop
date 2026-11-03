package com.puli.islanddrop

import android.content.Context

object Prefs {
    private const val FILE = "island_drop_prefs"
    private const val KEY_AUTO_ACCEPT = "auto_accept"

    fun autoAccept(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_ACCEPT, true)

    fun setAutoAccept(c: Context, value: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_ACCEPT, value).apply()
    }
}
