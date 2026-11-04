package com.puli.islanddrop

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/** Publishes each nearby Mac as a Direct Share target so it shows in the top row of the share sheet. */
object Shortcuts {
    const val CATEGORY = "com.puli.islanddrop.category.MAC"
    private const val PREFIX = "mac:"

    fun macName(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun publish(c: Context, macs: List<String>) {
        val sm = c.getSystemService(ShortcutManager::class.java) ?: return
        macs.forEachIndexed { rank, name ->
            val s = ShortcutInfo.Builder(c, PREFIX + name)
                .setShortLabel(name.take(24))
                .setLongLabel(name)
                .setIcon(Icon.createWithResource(c, R.drawable.ic_mac_shortcut))
                .setIntent(Intent(c, ShareActivity::class.java).setAction(Intent.ACTION_SEND))
                .setLongLived(true)
                .setCategories(setOf(CATEGORY))
                .setRank(rank)
                .build()
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) sm.pushDynamicShortcut(s) else sm.addDynamicShortcuts(listOf(s))
            }
        }
    }

    fun used(c: Context, name: String) {
        runCatching { c.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(PREFIX + name) }
    }
}
