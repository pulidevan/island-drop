package com.puli.islanddrop

import android.content.Context

/** Keeps track of recently-seen Mac names. Kept intentionally minimal — a fuller version could
 *  publish these as launcher dynamic shortcuts. */
object Shortcuts {
    @Volatile var lastSeen: List<String> = emptyList()
        private set

    fun publish(c: Context, names: List<String>) {
        lastSeen = names
    }
}
