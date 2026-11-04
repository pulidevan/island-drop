package com.puli.islanddrop

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Damped spring, normalised to t∈[0,1]. ζ≈0.7 gives the soft iOS overshoot. */
class SpringInterpolator(private val zeta: Float = 0.7f, private val omega: Float = 12.5f) : Interpolator {
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val wd = omega * sqrt(1 - zeta * zeta)
        val e = exp(-zeta * omega * t)
        return 1 - e * (cos(wd * t) + (zeta * omega / wd) * sin(wd * t))
    }
}

/** The AirPods-style bottom card. Hosted either in an overlay window or in PopupActivity. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DropCardView(
    context: Context,
    private val t: Transfer,
    private val activity: Activity?,
    private val onClosed: (DropCardView) -> Unit,
) : FrameLayout(context) {

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cardColor = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
    private val labelColor = if (dark) Color.WHITE else Color.BLACK
    private val secondaryColor = if (dark) 0xFF98989F.toInt() else 0xFF8A8A8E.toInt()
    private val fillColor = if (dark) 0xFF2C2C2E.toInt() else 0xFFE9E9EB.toInt()
    private val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()

    private val scrim = View(context)
    private val card = LinearLayout(context)
    private val titleView = label(22f, labelColor, Typeface.BOLD)
    private val subtitleView = label(15f, secondaryColor)
    private val icon = FileIconView(context, t)
    private val nameView = label(16f, labelColor, Typeface.BOLD)
    private val metaView = label(13f, secondaryColor)
    private val bar = ProgressLine(context, blue, fillColor)
    private val primary = label(17f, Color.WHITE, Typeface.BOLD)
    private val secondary = label(16f, blue, Typeface.BOLD)
    private val closeBtn = CloseButton(context, fillColor, secondaryColor)

    private var closing = false
    private var bob: ObjectAnimator? = null
    private var lastState: Transfer.State? = null
    private val listener: () -> Unit = { render() }

    init {
        clipChildren = false
        scrim.setBackgroundColor(0x66000000)
        scrim.alpha = 0f
        scrim.setOnClickListener { dismiss(decline = true) }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dpf(38f) }
        card.setPadding(dp(24), dp(22), dp(24), dp(12))
        card.elevation = dpf(24f)
        card.layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }

        val header = FrameLayout(context)
        titleView.gravity = Gravity.CENTER
        titleView.setPadding(dp(38), 0, dp(38), 0)
        titleView.isSingleLine = true
        titleView.ellipsize = TextUtils.TruncateAt.END
        header.addView(titleView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        header.addView(closeBtn, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.CENTER_VERTICAL))
        closeBtn.setOnClickListener { dismiss(decline = true) }
        card.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        subtitleView.gravity = Gravity.CENTER
        card.addView(subtitleView, lin(top = 2))
        card.addView(icon, LinearLayout.LayoutParams(dp(124), dp(124)).apply { topMargin = dp(14); bottomMargin = dp(8) })
        nameView.gravity = Gravity.CENTER
        nameView.isSingleLine = true
        nameView.ellipsize = TextUtils.TruncateAt.MIDDLE
        card.addView(nameView, lin())
        metaView.gravity = Gravity.CENTER
        metaView.isSingleLine = true
        metaView.ellipsize = TextUtils.TruncateAt.END
        card.addView(metaView, lin(top = 2))
        card.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(6)).apply {
            topMargin = dp(16); leftMargin = dp(10); rightMargin = dp(10)
        })
        primary.gravity = Gravity.CENTER
        primary.background = GradientDrawable().apply { setColor(blue); cornerRadius = dpf(15f) }
        card.addView(primary, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(20) })
        secondary.gravity = Gravity.CENTER
        card.addView(secondary, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) })

        pressable(primary); pressable(secondary); pressable(closeBtn)
        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { onSecondary() }
        setupSwipe()

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(10)
        })
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val inset = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom
                        else insets.systemWindowInsetBottom
            val fallback = if (activity == null) navBarHeight() else 0
            (card.layoutParams as LayoutParams).bottomMargin = dp(10) + max(inset, fallback)
            card.requestLayout()
            insets
        }
        render()
    }

    // region lifecycle + motion

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        t.observe(listener)
        Presenter.setVisible(t.id, true)
        render()
        card.visibility = INVISIBLE
        card.post {
            card.translationY = (card.height + dp(80)).toFloat()
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.visibility = VISIBLE
            scrim.animate().alpha(1f).setDuration(260).start()
            card.animate().translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(760).setInterpolator(SpringInterpolator()).start()
            haptic()
        }
    }

    override fun onDetachedFromWindow() {
        t.unobserve(listener)
        Presenter.setVisible(t.id, false)
        stopBob()
        super.onDetachedFromWindow()
    }

    private fun dismiss(decline: Boolean) {
        if (closing) return
        closing = true
        if (decline && t.state == Transfer.State.ASKING) t.decide(false)
        if (t.state == Transfer.State.RECEIVING) Presenter.setVisible(t.id, false)
        scrim.animate().alpha(0f).setDuration(220).start()
        card.animate().translationY(card.height + dp(80).toFloat())
            .setDuration(260).setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction { onClosed(this) }.start()
    }

    private fun setupSwipe() {
        var downY = 0f
        var vt: VelocityTracker? = null
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    card.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    card.translationY = if (dy > 0) dy else dy * 0.12f
                    scrim.alpha = 1f - (max(0f, dy) / max(1, card.height)).coerceIn(0f, 1f) * 0.8f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val v = vt?.let { it.addMovement(e); it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f
                    vt?.recycle(); vt = null
                    val dy = e.rawY - downY
                    if (dy > card.height * 0.28f || v > 1600f) dismiss(decline = true)
                    else {
                        card.animate().translationY(0f).setDuration(560).setInterpolator(SpringInterpolator()).start()
                        scrim.animate().alpha(1f).setDuration(200).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startBob() {
        if (bob != null) return
        bob = ObjectAnimator.ofFloat(icon, "translationY", -dpf(4f), dpf(4f)).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBob() {
        bob?.cancel(); bob = null
        icon.animate().translationY(0f).setDuration(300).start()
    }

    private fun haptic() {
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(if (view.isEnabled) 1f else 0.45f)
                        .setDuration(380).setInterpolator(SpringInterpolator(0.6f)).start()
            }
            false
        }
    }

    // endregion

    // region state

    private fun render() {
        val s = t.state
        titleView.text = t.sender
        nameView.text = t.summary
        val size = Formatter.formatShortFileSize(context, t.total)
        var primaryEnabled = true
        secondary.visibility = VISIBLE

        if (t.kind == Transfer.Kind.LINK) {
            subtitleView.text = "shared a link with you"
            metaView.text = t.url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: ""
            bar.visibility = GONE
            primary.text = "Open Link"
            secondary.text = "Copy Link"
        } else when (s) {
            Transfer.State.ASKING -> {
                subtitleView.text = if (t.files.size == 1) "wants to send you a file" else "wants to send you ${t.files.size} files"
                metaView.text = size
                bar.visibility = GONE
                primary.text = "Accept"
                secondary.text = "Decline"
            }
            Transfer.State.RECEIVING -> {
                subtitleView.text = "Receiving…"
                metaView.text = "${(t.progress * 100).toInt()}% of $size"
                bar.visibility = VISIBLE
                bar.setProgress(t.progress)
                primary.text = "Receiving…"
                primaryEnabled = false
                secondary.text = "Hide"
            }
            Transfer.State.DONE -> {
                subtitleView.text = "Saved to Downloads"
                metaView.text = "$size · Island Drop folder"
                bar.setProgress(1f)
                bar.visibility = GONE
                primary.text = if (t.files.size == 1) "Open" else "Show Files"
                secondary.text = "Done"
                if (lastState != Transfer.State.DONE) {
                    icon.showDone()
                    haptic()
                    loadThumbnail()
                }
            }
            Transfer.State.FAILED -> {
                subtitleView.text = "Transfer failed"
                metaView.text = t.error ?: ""
                bar.visibility = GONE
                primary.text = "Close"
                secondary.visibility = GONE
            }
            Transfer.State.DECLINED -> if (lastState != Transfer.State.DECLINED) dismiss(decline = false)
        }
        primary.isEnabled = primaryEnabled
        primary.alpha = if (primaryEnabled) 1f else 0.45f
        if (s == Transfer.State.ASKING) startBob() else if (bob != null) stopBob()
        lastState = s
    }

    private fun onPrimary() {
        when {
            t.kind == Transfer.Kind.LINK -> {
                t.url?.let { launch(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                dismiss(decline = false)
            }
            t.state == Transfer.State.ASKING -> t.decide(true)
            t.state == Transfer.State.DONE -> { openResult(); dismiss(decline = false) }
            t.state == Transfer.State.FAILED -> dismiss(decline = false)
        }
    }

    private fun onSecondary() {
        if (t.kind == Transfer.Kind.LINK) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Link", t.url ?: ""))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        }
        dismiss(decline = true)
    }

    private fun openResult() {
        val uri = t.saved.firstOrNull() ?: return
        val i = if (t.saved.size == 1) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.primaryMime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (t.primaryMime == "*/*" || t.primaryMime == "application/octet-stream") Intent.createChooser(view, "Open with") else view
        } else Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        launch(i)
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val km = context.getSystemService(KeyguardManager::class.java)
        val start = {
            try { context.startActivity(i) }
            catch (e: ActivityNotFoundException) { Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show() }
        }
        if (activity != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { start() }
            })
        } else start()
    }

    private fun loadThumbnail() {
        val uri = t.saved.firstOrNull() ?: return
        if (!t.primaryMime.startsWith("image/") && !t.primaryMime.startsWith("video/")) return
        Thread {
            val bmp = runCatching { context.contentResolver.loadThumbnail(uri, Size(480, 480), null) }.getOrNull()
            if (bmp != null) post { icon.setThumbnail(bmp) }
        }.start()
    }

    // endregion

    // region helpers

    private fun label(sp: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif", style).let { if (style == Typeface.BOLD) Typeface.create("sans-serif-medium", Typeface.BOLD) else it }
        includeFontPadding = false
    }

    private fun lin(top: Int = 0) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    // endregion
}

/** Document glyph tinted by file type, with a check badge on completion. */
class FileIconView(context: Context, private val t: Transfer) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private var check = 0f
    private var thumb: Bitmap? = null
    private val ext = t.files.firstOrNull()?.ext ?: ""

    private val color: Int = when (ext) {
        "pdf" -> 0xFFFF3B30.toInt()
        "doc", "docx", "pages", "rtf" -> 0xFF2F7CF6.toInt()
        "xls", "xlsx", "csv", "numbers" -> 0xFF34C759.toInt()
        "ppt", "pptx", "key" -> 0xFFFF9500.toInt()
        "zip", "rar", "7z", "tar", "gz", "dmg" -> 0xFF8E8E93.toInt()
        "jpg", "jpeg", "png", "heic", "gif", "webp", "svg" -> 0xFFAF52DE.toInt()
        "mp4", "mov", "mkv", "avi", "webm" -> 0xFF5856D6.toInt()
        "mp3", "m4a", "wav", "aac", "flac" -> 0xFFFF2D55.toInt()
        "apk" -> 0xFF3DDC84.toInt()
        "txt", "md", "json" -> 0xFF636366.toInt()
        else -> 0xFF007AFF.toInt()
    }

    fun showDone() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            startDelay = 120
            interpolator = SpringInterpolator(0.55f, 13f)
            addUpdateListener { check = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setThumbnail(b: Bitmap) {
        thumb = b
        alpha = 0.4f
        animate().alpha(1f).setDuration(250).start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (t.kind == Transfer.Kind.LINK) {
            drawLink(c, w, h)
            return
        }
        val dw = w * 0.62f; val dh = h * 0.80f
        val left = (w - dw) / 2; val top = (h - dh) / 2
        if (t.files.size > 1) {
            c.save(); c.rotate(-9f, w / 2, h / 2); c.translate(-w * 0.05f, 0f)
            drawDoc(c, left, top, dw, dh, blend(color, 0.55f), false)
            c.restore()
        }
        drawDoc(c, left, top, dw, dh, color, true)
        if (check > 0f) drawBadge(c, left + dw, top + dh)
    }

    private fun drawDoc(c: Canvas, l: Float, t0: Float, w: Float, h: Float, col: Int, front: Boolean) {
        val r = w * 0.1f; val fold = w * 0.3f
        val p = Path().apply {
            moveTo(l + r, t0); lineTo(l + w - fold, t0); lineTo(l + w, t0 + fold); lineTo(l + w, t0 + h - r)
            quadTo(l + w, t0 + h, l + w - r, t0 + h); lineTo(l + r, t0 + h); quadTo(l, t0 + h, l, t0 + h - r)
            lineTo(l, t0 + r); quadTo(l, t0, l + r, t0); close()
        }
        paint.style = Paint.Style.FILL
        paint.setShadowLayer(w * 0.06f, 0f, w * 0.03f, 0x33000000)
        val th = thumb
        if (front && th != null) {
            c.save(); c.clipPath(p)
            val scale = max(w / th.width, h / th.height)
            val bw = th.width * scale; val bh = th.height * scale
            c.drawBitmap(th, null, RectF(l + (w - bw) / 2, t0 + (h - bh) / 2, l + (w + bw) / 2, t0 + (h + bh) / 2), paint)
            c.restore()
            paint.clearShadowLayer()
            return
        }
        paint.color = col
        c.drawPath(p, paint)
        paint.clearShadowLayer()
        paint.color = blend(col, 0.7f, Color.WHITE)
        c.drawPath(Path().apply { moveTo(l + w - fold, t0); lineTo(l + w - fold, t0 + fold - r * 0.4f)
            quadTo(l + w - fold, t0 + fold, l + w - fold + r * 0.4f, t0 + fold); lineTo(l + w, t0 + fold); close() }, paint)
        if (front) {
            paint.color = 0x55FFFFFF
            for (i in 0..2) c.drawRoundRect(RectF(l + w * 0.18f, t0 + h * (0.36f + i * 0.1f), l + w * (if (i == 2) 0.6f else 0.82f), t0 + h * (0.36f + i * 0.1f) + h * 0.035f), 4f, 4f, paint)
            text.textSize = w * (if (ext.length > 3) 0.2f else 0.24f)
            c.drawText(ext.uppercase().take(4).ifBlank { "FILE" }, l + w / 2, t0 + h * 0.86f, text)
        }
    }

    private fun drawBadge(c: Canvas, cx0: Float, cy0: Float) {
        val r = width * 0.15f * min(1.15f, check)
        val cx = cx0 - r * 0.3f; val cy = cy0 - r * 0.3f
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawCircle(cx, cy, r + width * 0.025f, paint)
        paint.color = 0xFF34C759.toInt()
        c.drawCircle(cx, cy, r, paint)
        val p = Path().apply { moveTo(cx - r * 0.42f, cy + r * 0.02f); lineTo(cx - r * 0.1f, cy + r * 0.34f); lineTo(cx + r * 0.46f, cy - r * 0.3f) }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.22f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.WHITE
        c.drawPath(p, paint)
    }

    private fun drawLink(c: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF007AFF.toInt()
        c.drawCircle(w / 2, h / 2, min(w, h) * 0.38f, paint)
        text.textSize = w * 0.32f
        c.drawText("↗", w / 2, h / 2 + w * 0.11f, text)
    }

    private fun blend(a: Int, f: Float, b: Int = Color.BLACK): Int {
        val r = (Color.red(a) * f + Color.red(b) * (1 - f)).toInt()
        val g = (Color.green(a) * f + Color.green(b) * (1 - f)).toInt()
        val bl = (Color.blue(a) * f + Color.blue(b) * (1 - f)).toInt()
        return Color.rgb(r, g, bl)
    }
}

class ProgressLine(context: Context, private val tint: Int, private val track: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0f
    private var anim: ValueAnimator? = null

    fun setProgress(p: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, p.coerceIn(0f, 1f)).apply {
            duration = 180
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val r = h / 2
        paint.color = track
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, paint)
        paint.color = tint
        c.drawRoundRect(RectF(0f, 0f, max(h, width * shown), h), r, r, paint)
    }
}

class CloseButton(context: Context, private val fill: Int, private val glyph: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; contentDescription = "Close" }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.style = Paint.Style.FILL; paint.color = fill
        c.drawCircle(w / 2, h / 2, min(w, h) / 2, paint)
        paint.style = Paint.Style.STROKE; paint.color = glyph
        paint.strokeWidth = w * 0.08f; paint.strokeCap = Paint.Cap.ROUND
        val d = w * 0.17f
        c.drawLine(w / 2 - d, h / 2 - d, w / 2 + d, h / 2 + d, paint)
        c.drawLine(w / 2 + d, h / 2 - d, w / 2 - d, h / 2 + d, paint)
    }
}
