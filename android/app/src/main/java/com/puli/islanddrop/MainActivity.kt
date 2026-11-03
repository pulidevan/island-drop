package com.puli.islanddrop

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.min

class MainActivity : Activity() {

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val label get() = if (dark) Color.WHITE else Color.BLACK
    private val secondary get() = if (dark) 0xFF98989F.toInt() else 0xFF8A8A8E.toInt()
    private val cardColor get() = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
    private val blue get() = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    private val green = 0xFF34C759.toInt()

    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private val rows = mutableListOf<Pair<() -> Boolean, TextView>>()
    private val ticker = object : Runnable {
        override fun run() { refresh(); Transfer.main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.start(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(build())
    }

    override fun onResume() { super.onResume(); Transfer.main.post(ticker) }
    override fun onPause() { Transfer.main.removeCallbacks(ticker); super.onPause() }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(40))
        }
        root.addView(text("Island Drop", 34f, label, true))
        root.addView(text("Files from your Mac, straight to your phone.", 15f, secondary).apply { setPadding(0, dp(4), 0, dp(22)) })

        // Hero
        val hero = card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(10), dp(20), dp(22)) }
        hero.addView(RadarView(this, blue), LinearLayout.LayoutParams(dp(180), dp(180)))
        statusTitle = text("Starting…", 22f, label, true).apply { gravity = Gravity.CENTER }
        statusSub = text("", 14f, secondary).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        hero.addView(statusTitle, matchWrap())
        hero.addView(statusSub, matchWrap())
        root.addView(hero, matchWrap())

        // Setup
        root.addView(section("SETUP"))
        val setup = card()
        addRow(setup, "Notifications", "Get alerts when files arrive",
            { Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        addRow(setup, "Pop up over apps", "Slide-up card like AirPods", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addRow(setup, "Wake on lock screen", "Turn the screen on for incoming files",
            { Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }) {
            if (Build.VERSION.SDK_INT >= 34) startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
        addRow(setup, "Always ready", "Don't let the battery saver stop the receiver",
            { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }, last = true) {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        }
        root.addView(setup, matchWrap())

        // Options
        root.addView(section("OPTIONS"))
        val opts = card()
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(12), dp(12)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("Auto-accept from my Mac", 16f, label))
        col.addView(text("Files save immediately, the card still pops up", 13f, secondary))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = Prefs.autoAccept(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoAccept(this@MainActivity, v) }
        })
        opts.addView(row)
        root.addView(opts, matchWrap())

        // Actions
        root.addView(button("Preview Pop-up", filled = true) { preview() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(26) })
        root.addView(button("Open Received Files", filled = false) {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(text("On your Mac: install Island Drop, drag a file onto the notch and drop it on your phone. Both devices must be on the same Wi-Fi.",
            13f, secondary).apply { setPadding(dp(4), dp(18), dp(4), 0); gravity = Gravity.CENTER })

        return ScrollView(this).apply { isFillViewport = true; addView(root) }
    }

    private fun refresh() {
        val ready = ReceiverService.status == "Ready"
        statusTitle.text = if (ready) "Ready to receive" else ReceiverService.status
        statusSub.text = ReceiverService.visibleAs?.let { "Visible to your Mac as “$it”" } ?: "Connecting to Wi-Fi…"
        rows.forEach { (ok, badge) -> styleBadge(badge, ok()) }
    }

    private fun preview() {
        val f = FileInfo("Quarterly Report.pdf", 2_430_000, "application/pdf")
        val t = Transfer(Transfer.nextId(), "MacBook Pro", Transfer.Kind.FILES, listOf(f), f.size, demo = true)
        Transfer.active[t.id] = t
        Presenter.present(this, t)
        Thread {
            if (!t.awaitDecision(60)) { t.state = Transfer.State.DECLINED; t.changed(); return@Thread }
            t.state = Transfer.State.RECEIVING; t.changed()
            for (i in 1..50) { SystemClock.sleep(36); t.progress = i / 50f; t.changed() }
            t.state = Transfer.State.DONE; t.changed()
        }.start()
    }

    // region views

    private fun addRow(parent: LinearLayout, title: String, sub: String, ok: () -> Boolean, last: Boolean = false, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            isClickable = true
            background = with(TypedValue()) {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                getDrawable(resourceId)
            }
            setOnClickListener { if (!ok()) fix() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 16f, label))
        col.addView(text(sub, 13f, secondary))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val badge = text("", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(5), dp(12), dp(5)) }
        row.addView(badge)
        parent.addView(row)
        if (!last) parent.addView(View(this).apply { setBackgroundColor(if (dark) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()) },
            LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16) })
        rows += ok to badge
        styleBadge(badge, ok())
    }

    private fun styleBadge(b: TextView, ok: Boolean) {
        b.text = if (ok) "✓" else "Allow"
        b.setTextColor(if (ok) green else Color.WHITE)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (ok) Color.TRANSPARENT else blue)
        }
    }

    private fun section(t: String) = text(t, 13f, secondary).apply { setPadding(dp(16), dp(26), 0, dp(8)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(22).toFloat() }
        clipToOutline = true
    }

    private fun button(t: String, filled: Boolean, onClick: () -> Unit) = text(t, 17f, if (filled) Color.WHITE else blue, true).apply {
        gravity = Gravity.CENTER
        if (filled) background = GradientDrawable().apply { setColor(blue); cornerRadius = dp(15).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // endregion
}

/** Expanding rings around a phone glyph: "listening for your Mac". */
class RadarView(context: Context, private val tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val maxR = min(width, height) / 2f
        val core = maxR * 0.34f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxR * 0.025f
        for (i in 0..2) {
            val p = (phase + i / 3f) % 1f
            paint.color = tint
            paint.alpha = ((1 - p) * 140).toInt()
            c.drawCircle(cx, cy, core + (maxR - core) * p, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = tint
        paint.alpha = 255
        c.drawCircle(cx, cy, core, paint)
        // phone glyph
        val pw = core * 0.62f; val ph = core * 1.05f
        paint.color = Color.WHITE
        c.drawRoundRect(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2, pw * 0.22f, pw * 0.22f, paint)
        paint.color = tint
        c.drawRoundRect(cx - pw / 2 + pw * 0.12f, cy - ph / 2 + pw * 0.12f, cx + pw / 2 - pw * 0.12f, cy + ph / 2 - pw * 0.12f, pw * 0.12f, pw * 0.12f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = pw * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val a = Path().apply {
            moveTo(cx, cy - ph * 0.22f); lineTo(cx, cy + ph * 0.18f)
            moveTo(cx - pw * 0.2f, cy); lineTo(cx, cy + ph * 0.2f); lineTo(cx + pw * 0.2f, cy)
        }
        c.drawPath(a, paint)
    }
}
