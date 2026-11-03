package com.puli.islanddrop

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.WindowManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides how to surface an incoming transfer, AirPods-style:
 *  - unlocked + "display over other apps" → card slides up over whatever app is open
 *  - locked / screen off → wake the screen, full-screen intent → card over the lock screen
 *  - otherwise → heads-up notification with Accept / Decline
 */
object Presenter {
    private var overlay: DropCardView? = null
    private val visible = ConcurrentHashMap.newKeySet<Int>()

    fun isVisible(id: Int) = id in visible
    fun setVisible(id: Int, v: Boolean) { if (v) visible += id else visible -= id }

    fun present(ctx: Context, t: Transfer) {
        val app = ctx.applicationContext
        Transfer.main.post { show(app, t) }
    }

    private fun show(c: Context, t: Transfer) {
        wake(c)
        val km = c.getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked && Settings.canDrawOverlays(c)) {
            if (runCatching { showOverlay(c, t) }.isSuccess) return
        }
        Notifs.incoming(c, t)
        if (!km.isKeyguardLocked) runCatching { c.startActivity(PopupActivity.intent(c, t.id)) }
    }

    @Suppress("DEPRECATION")
    private fun wake(c: Context) {
        val pm = c.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "islanddrop:wake"
        ).acquire(8_000)
    }

    private fun showOverlay(c: Context, t: Transfer) {
        overlay?.let { old -> runCatching { c.getSystemService(WindowManager::class.java).removeView(old) } }
        val wm = c.getSystemService(WindowManager::class.java)
        val themed = ContextThemeWrapper(c, R.style.AppTheme)
        val view = DropCardView(themed, t, null) { v ->
            runCatching { wm.removeView(v) }
            if (overlay === v) overlay = null
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(view, lp)
        overlay = view
    }
}

/** Translucent activity used over the lock screen (launched by the full-screen intent). */
class PopupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val t = Transfer.active[intent.getIntExtra("id", -1)]
        if (t == null || t.state == Transfer.State.DECLINED) { finish(); return }
        Notifs.cancel(this, t.id)
        setContentView(DropCardView(this, t, this) {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        })
    }

    companion object {
        fun intent(c: Context, id: Int) = Intent(c, PopupActivity::class.java)
            .putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
