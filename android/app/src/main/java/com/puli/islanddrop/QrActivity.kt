package com.puli.islanddrop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Bottom card with a QR code for the iPhone camera. Also the target of "Share → Send to iPhone". */
class QrActivity : Activity() {
    private lateinit var qr: ImageView
    private lateinit var status: TextView
    private lateinit var outboxText: TextView
    private lateinit var clear: TextView
    private lateinit var card: LinearLayout
    private lateinit var scrim: View
    private var shownUrl: String? = null
    private var visitAtOpen = 0L
    private var closing = false
    private val listener: () -> Unit = { refresh() }

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ReceiverService.start(this)
        WebBridge.start(this)

        // Shared into us from another app → put it in the outbox.
        when (val out = Outgoing.from(this, intent)) {
            is Outgoing.Files -> WebBridge.addUris(this, out.uris)
            is Outgoing.Text -> WebBridge.addText(out.text)
            null -> {}
        }
        visitAtOpen = WebBridge.lastVisit
        setContentView(build())
    }

    override fun onResume() { super.onResume(); WebBridge.observe(listener); refresh() }
    override fun onPause() { WebBridge.unobserve(listener); super.onPause() }

    private fun build(): View {
        val label = if (dark) Color.WHITE else Color.BLACK
        val secondary = if (dark) 0xFF98989F.toInt() else 0xFF8A8A8E.toInt()
        val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
        val fill = if (dark) 0xFF2C2C2E.toInt() else 0xFFF2F2F7.toInt()

        val root = FrameLayout(this)
        scrim = View(this).apply { setBackgroundColor(0x66000000); alpha = 0f; setOnClickListener { close() } }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(if (dark) 0xFF1C1C1E.toInt() else Color.WHITE); cornerRadius = dp(38).toFloat() }
            setPadding(dp(24), dp(22), dp(24), dp(14))
            elevation = dp(24).toFloat()
            isClickable = true
        }
        val header = FrameLayout(this)
        header.addView(text("Connect iPhone", 22f, label, true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        header.addView(CloseButton(this, fill, secondary).apply { setOnClickListener { close() } },
            FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.CENTER_VERTICAL))
        card.addView(header, LinearLayout.LayoutParams(-1, -2))
        card.addView(text("Open the Camera on your iPhone and point it at this code", 15f, secondary).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), 0)
        })

        qr = ImageView(this).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(22).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        card.addView(qr, LinearLayout.LayoutParams(dp(236), dp(236)).apply { topMargin = dp(18); bottomMargin = dp(14) })

        status = text("Starting…", 15f, label, true).apply { gravity = Gravity.CENTER }
        card.addView(status, LinearLayout.LayoutParams(-1, -2))
        outboxText = text("", 13f, secondary).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), dp(4), dp(8), 0)
        }
        card.addView(outboxText, LinearLayout.LayoutParams(-1, -2))

        val done = text("Done", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(blue); cornerRadius = dp(15).toFloat() }
            setOnClickListener { close() }
        }
        card.addView(done, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
        clear = text("Stop Sharing Files", 16f, blue, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { WebBridge.clearOutbox() }
        }
        card.addView(clear, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(2) })

        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(10) })
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
            (card.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(10) + b
            card.requestLayout()
            insets
        }
        card.visibility = View.INVISIBLE
        card.post {
            card.translationY = (card.height + dp(80)).toFloat()
            card.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(260).start()
            card.animate().translationY(0f).setDuration(760).setInterpolator(SpringInterpolator()).start()
        }
        return root
    }

    private fun refresh() {
        val url = WebBridge.url(this)
        if (url == null) {
            status.text = "Connect this tablet to Wi-Fi"
        } else if (url != shownUrl) {
            shownUrl = url
            qr.setImageBitmap(qrBitmap(url, dp(208)))
            qr.alpha = 0f; qr.scaleX = 0.9f; qr.scaleY = 0.9f
            qr.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(SpringInterpolator(0.65f)).start()
        }
        if (url != null) {
            val connected = WebBridge.lastVisit > visitAtOpen
            status.text = if (connected) "✓ iPhone connected" else "iPhone must be on the same Wi-Fi"
            status.setTextColor(if (connected) 0xFF34C759.toInt() else if (dark) Color.WHITE else Color.BLACK)
            if (connected && status.tag != true) { status.tag = true; status.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
        }
        val items = WebBridge.outbox
        val preparing = items.count { !it.ready }
        outboxText.text = when {
            items.isEmpty() -> "Send photos & files from the iPhone, or Share → Send to iPhone here"
            preparing > 0 -> "Preparing ${items.size} item(s)…"
            else -> "${items.size} item(s) ready on iPhone: " + items.joinToString(", ") { it.name } +
                " · " + Formatter.formatShortFileSize(this, items.sumOf { it.size })
        }
        clear.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun close() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(220).start()
        card.animate().translationY(card.height + dp(80).toFloat()).setDuration(260)
            .setInterpolator(AccelerateInterpolator(1.5f)).withEndAction {
                finish()
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }.start()
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun open(c: Context) = c.startActivity(Intent(c, QrActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
