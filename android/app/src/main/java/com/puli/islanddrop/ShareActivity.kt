package com.puli.islanddrop

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max
import kotlin.math.min

/** "Send to Mac" share target – the AirDrop direction (phone → Mac). */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val out = Outgoing.from(this, intent)
        if (out == null) { finish(); return }
        val directMac = Shortcuts.macName(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        setContentView(SendSheetView(this, out, directMac) {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        })
    }
}

/** What the user is sending. */
sealed class Outgoing {
    class Files(val uris: List<Uri>, val infos: List<FileInfo>) : Outgoing()
    class Text(val text: String) : Outgoing()

    companion object {
        @Suppress("DEPRECATION")
        fun from(c: Context, i: Intent): Outgoing? {
            val uris = mutableListOf<Uri>()
            when (i.action) {
                Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                       else i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { uris += it }
                Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { uris += it }
            }
            if (uris.isEmpty()) {
                val text = i.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                return if (text.isNullOrEmpty()) null else Text(text)
            }
            return Files(uris, uris.map { describe(c, it) })
        }

        fun describe(c: Context, u: Uri): FileInfo {
            var name = u.lastPathSegment ?: "file"
            var size = -1L
            runCatching {
                c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur: Cursor ->
                    if (cur.moveToFirst()) {
                        cur.getString(0)?.let { name = it }
                        if (!cur.isNull(1)) size = cur.getLong(1)
                    }
                }
            }
            if (u.scheme == "file") u.path?.let { File(it) }?.let { if (it.exists()) size = it.length() }
            return FileInfo(name, size, c.contentResolver.getType(u) ?: "application/octet-stream")
        }
    }
}

/** Finds Macs running Island Drop (`_islanddrop-mac._tcp`). */
class MacFinder(private val c: Context, private val onChange: (List<NsdServiceInfo>) -> Unit) {
    private val nsd = c.getSystemService(NsdManager::class.java)
    private val found = LinkedHashMap<String, NsdServiceInfo>()
    private var lock: WifiManager.MulticastLock? = null
    private var active = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(s: NsdServiceInfo) { Transfer.main.post { found[s.serviceName] = s; onChange(found.values.toList()) } }
        override fun onServiceLost(s: NsdServiceInfo) { Transfer.main.post { found.remove(s.serviceName); onChange(found.values.toList()) } }
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, code: Int) {}
        override fun onStopDiscoveryFailed(t: String, code: Int) {}
    }

    fun start() {
        if (active) return
        active = true
        lock = c.applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("islanddrop-find").apply { setReferenceCounted(false); acquire() }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { nsd.stopServiceDiscovery(listener) }
        runCatching { lock?.release() }
    }

    @Suppress("DEPRECATION")
    fun resolve(s: NsdServiceInfo, done: (InetAddress?, Int) -> Unit) {
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onServiceResolved(r: NsdServiceInfo) { Transfer.main.post { done(r.host, r.port) } }
            override fun onResolveFailed(r: NsdServiceInfo, code: Int) { Transfer.main.post { done(null, 0) } }
        })
    }

    companion object { const val SERVICE_TYPE = "_islanddrop-mac._tcp" }
}

/** Phone → Mac, same wire protocol as the Mac → phone direction. */
object MacSender {
    sealed class Event {
        object Waiting : Event()
        class Progress(val p: Float) : Event()
        object Done : Event()
        object Declined : Event()
        class Failed(val msg: String) : Event()
    }

    private class Prepared(val info: FileInfo, val open: () -> InputStream)

    fun send(c: Context, host: InetAddress, port: Int, out: Outgoing, on: (Event) -> Unit) {
        val emit = { e: Event -> Transfer.main.post { on(e) }; Unit }
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 6000)
                s.soTimeout = 130_000
                s.tcpNoDelay = true
                val os = BufferedOutputStream(s.getOutputStream(), 256 * 1024)
                val input = BufferedInputStream(s.getInputStream())
                val sender = deviceName(c)
                when (out) {
                    is Outgoing.Text -> {
                        val h = JSONObject().put("v", 1).put("type", "link").put("url", out.text).put("title", "").put("sender", sender)
                        os.write((h.toString() + "\n").toByteArray()); os.flush()
                        readLine(input)
                        emit(Event.Done)
                    }
                    is Outgoing.Files -> {
                        val items = out.uris.mapIndexed { i, u -> prepare(c, u, out.infos[i]) }
                        val total = items.sumOf { it.info.size }
                        val files = JSONArray()
                        items.forEach { files.put(JSONObject().put("name", it.info.name).put("size", it.info.size).put("mime", it.info.mime)) }
                        val h = JSONObject().put("v", 1).put("type", "files").put("sender", sender).put("files", files).put("total", total)
                        os.write((h.toString() + "\n").toByteArray()); os.flush()
                        emit(Event.Waiting)
                        if (readLine(input) != "ACCEPT") { emit(Event.Declined); return }
                        val buf = ByteArray(256 * 1024)
                        var sent = 0L
                        var last = 0L
                        for (p in items) {
                            p.open().use { ins ->
                                var remaining = p.info.size
                                while (remaining > 0) {
                                    val n = ins.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
                                    if (n < 0) throw IOException("${p.info.name} ended early")
                                    os.write(buf, 0, n)
                                    remaining -= n
                                    sent += n
                                    val now = SystemClock.uptimeMillis()
                                    if (now - last > 50) { last = now; emit(Event.Progress(sent.toFloat() / max(total, 1L))) }
                                }
                            }
                        }
                        os.flush()
                        emit(Event.Progress(1f))
                        if (readLine(input) == "DONE") emit(Event.Done) else emit(Event.Failed("The Mac couldn't save it"))
                    }
                }
            }
        } catch (e: Exception) {
            emit(Event.Failed(e.message ?: "Couldn't reach the Mac"))
        }
    }

    /** Some providers don't report a size up front – copy those to cache first so the header is exact. */
    private fun prepare(c: Context, u: Uri, info: FileInfo): Prepared {
        if (info.size >= 0) return Prepared(info) { c.contentResolver.openInputStream(u) ?: throw IOException("Can't read ${info.name}") }
        val dir = File(c.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "${System.nanoTime()}-${info.name}")
        c.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: throw IOException("Can't read ${info.name}")
        return Prepared(info.copy(size = f.length())) { FileInputStream(f) }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = input.read()
            if (x < 0) return null
            if (x == '\n'.code) return b.toString("UTF-8").trim()
            b.write(x)
        }
    }
}

/** AirDrop-style share sheet: preview on top, nearby Macs as bubbles with progress rings. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SendSheetView(
    context: Context,
    private val out: Outgoing,
    private val autoSendTo: String?,
    private val onClosed: () -> Unit,
) : FrameLayout(context) {
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cardColor = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
    private val labelColor = if (dark) Color.WHITE else Color.BLACK
    private val secondaryColor = if (dark) 0xFF98989F.toInt() else 0xFF8A8A8E.toInt()
    private val fillColor = if (dark) 0xFF2C2C2E.toInt() else 0xFFF2F2F7.toInt()
    private val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()

    private val scrim = View(context)
    private val card = LinearLayout(context)
    private val searching = LinearLayout(context)
    private val row = LinearLayout(context)
    private val scroller = HorizontalScrollView(context)
    private val status = label(13f, secondaryColor)
    private val bubbles = LinkedHashMap<String, MacBubble>()
    private val finder = MacFinder(context) { list -> update(list) }
    private var busy = false
    private var closing = false

    init {
        scrim.setBackgroundColor(0x66000000); scrim.alpha = 0f
        scrim.setOnClickListener { dismiss() }
        addView(scrim, LayoutParams(-1, -1))

        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dpf(38f) }
        card.setPadding(dp(22), dp(20), dp(22), dp(16))
        card.elevation = dpf(24f)
        card.isClickable = true

        // Header: preview + title + close
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val preview: View = when (out) {
            is Outgoing.Files -> FileIconView(context, Transfer(0, "", Transfer.Kind.FILES, out.infos, out.infos.sumOf { max(0, it.size) }))
            is Outgoing.Text -> FileIconView(context, Transfer(0, "", Transfer.Kind.LINK, url = out.text))
        }
        header.addView(preview, LinearLayout.LayoutParams(dp(56), dp(56)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        col.addView(label(17f, labelColor, true).apply {
            text = when (out) {
                is Outgoing.Files -> if (out.infos.size == 1) out.infos[0].name else "${out.infos.size} files"
                is Outgoing.Text -> out.text
            }
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        col.addView(label(13f, secondaryColor).apply {
            text = when (out) {
                is Outgoing.Files -> out.infos.sumOf { max(0, it.size) }.let { if (it > 0) "Send to Mac · " + Formatter.formatShortFileSize(context, it) else "Send to Mac" }
                is Outgoing.Text -> "Send link to Mac"
            }
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val close = CloseButton(context, fillColor, secondaryColor)
        close.setOnClickListener { dismiss() }
        header.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))
        card.addView(header)

        card.addView(View(context).apply { setBackgroundColor(if (dark) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()) },
            LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(16); bottomMargin = dp(12) })
        card.addView(label(13f, secondaryColor, true).apply { text = "AIRDROP TO MAC"; letterSpacing = 0.04f })

        // Device area
        val area = FrameLayout(context)
        searching.orientation = LinearLayout.VERTICAL
        searching.gravity = Gravity.CENTER
        searching.addView(RadarView(context, blue), LinearLayout.LayoutParams(dp(70), dp(70)))
        searching.addView(label(14f, labelColor, true).apply { text = "Looking for Macs…"; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        searching.addView(label(12f, secondaryColor).apply { text = "Open Island Drop on your Mac · same Wi-Fi"; gravity = Gravity.CENTER })
        area.addView(searching, FrameLayout.LayoutParams(-1, -1))
        row.orientation = LinearLayout.HORIZONTAL
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(row)
        area.addView(scroller, FrameLayout.LayoutParams(-2, -1, Gravity.START))
        card.addView(area, LinearLayout.LayoutParams(-1, dp(136)).apply { topMargin = dp(8) })

        status.gravity = Gravity.CENTER
        status.minHeight = dp(18)
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val cancel = label(17f, blue, true).apply {
            text = "Cancel"; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(fillColor); cornerRadius = dpf(15f) }
            setOnClickListener { dismiss() }
        }
        card.addView(cancel, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })

        addView(card, LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(10) })
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
            (card.layoutParams as LayoutParams).bottomMargin = dp(10) + b
            card.requestLayout()
            insets
        }
        setupSwipe()
        update(emptyList())
        if (autoSendTo != null) status.text = "Sending to $autoSendTo…"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        finder.start()
        card.visibility = INVISIBLE
        card.post {
            card.translationY = (card.height + dp(80)).toFloat()
            card.visibility = VISIBLE
            scrim.animate().alpha(1f).setDuration(260).start()
            card.animate().translationY(0f).setDuration(760).setInterpolator(SpringInterpolator()).start()
        }
    }

    override fun onDetachedFromWindow() { finder.stop(); super.onDetachedFromWindow() }

    private fun update(list: List<NsdServiceInfo>) {
        val names = list.map { it.serviceName }
        bubbles.keys.filter { it !in names && !busy }.forEach { row.removeView(bubbles.remove(it)) }
        for (s in list) {
            if (bubbles.containsKey(s.serviceName)) continue
            val b = MacBubble(context, s.serviceName, labelColor, blue)
            b.setOnClickListener { sendTo(s, b) }
            bubbles[s.serviceName] = b
            row.addView(b, LinearLayout.LayoutParams(dp(92), -1))
            b.scaleX = 0.5f; b.scaleY = 0.5f; b.alpha = 0f
            b.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600).setInterpolator(SpringInterpolator(0.6f)).start()
            if (s.serviceName == autoSendTo && !busy) postDelayed({ sendTo(s, b) }, 450)
        }
        val empty = bubbles.isEmpty()
        searching.visibility = if (empty) VISIBLE else GONE
        scroller.visibility = if (empty) GONE else VISIBLE
        if (!busy) status.text = if (empty) "" else "Tap a Mac to send"
    }

    private fun sendTo(s: NsdServiceInfo, b: MacBubble) {
        if (busy) return
        busy = true
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        bubbles.values.filter { it !== b }.forEach { it.animate().alpha(0.35f).setDuration(200).start() }
        b.setState(MacBubble.State.WAITING)
        status.text = "Connecting…"
        finder.resolve(s) { host, port ->
            if (host == null) { fail(b, "Couldn't find ${s.serviceName}"); return@resolve }
            Thread {
                MacSender.send(context, host, port, out) { e ->
                    when (e) {
                        MacSender.Event.Waiting -> status.text = "Waiting for ${s.serviceName} to accept…"
                        is MacSender.Event.Progress -> { b.setProgress(e.p); status.text = "Sending… ${(e.p * 100).toInt()}%" }
                        MacSender.Event.Done -> {
                            Shortcuts.used(context, s.serviceName)
                            b.setState(MacBubble.State.DONE)
                            status.text = "Sent"
                            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                            postDelayed({ dismiss() }, 1100)
                        }
                        MacSender.Event.Declined -> fail(b, "Declined")
                        is MacSender.Event.Failed -> fail(b, e.msg)
                    }
                }
            }.start()
        }
    }

    private fun fail(b: MacBubble, msg: String) {
        busy = false
        b.setState(MacBubble.State.FAILED)
        status.text = msg
        bubbles.values.forEach { it.animate().alpha(1f).setDuration(200).start() }
        postDelayed({ b.setState(MacBubble.State.IDLE) }, 1600)
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(220).start()
        card.animate().translationY(card.height + dpf(80f)).setDuration(260)
            .setInterpolator(AccelerateInterpolator(1.5f)).withEndAction { onClosed() }.start()
    }

    private fun setupSwipe() {
        var downY = 0f
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; card.animate().cancel(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    card.translationY = if (dy > 0) dy else dy * 0.12f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.rawY - downY > card.height * 0.28f) dismiss()
                    else card.animate().translationY(0f).setDuration(560).setInterpolator(SpringInterpolator()).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density
}

/** One Mac in the sheet: laptop avatar with an AirDrop-style progress ring, name underneath. */
class MacBubble(context: Context, name: String, labelColor: Int, private val tint: Int) : LinearLayout(context) {
    enum class State { IDLE, WAITING, PROGRESS, DONE, FAILED }

    private val avatar = Avatar()
    private var state = State.IDLE
    private var progress = 0f
    private var spin = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; avatar.invalidate() }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        val d = resources.displayMetrics.density
        addView(avatar, LayoutParams((76 * d).toInt(), (76 * d).toInt()).apply { topMargin = (6 * d).toInt() })
        addView(TextView(context).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(labelColor)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), 0)
        }, LayoutParams(-1, -2))
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SpringInterpolator(0.55f)).start()
            }
            false
        }
    }

    fun setState(s: State) {
        state = s
        if (s == State.WAITING) spinner.start() else spinner.cancel()
        if (s == State.IDLE) progress = 0f
        if (s == State.DONE) progress = 1f
        avatar.invalidate()
    }

    fun setProgress(p: Float) {
        if (state != State.PROGRESS) setState(State.PROGRESS)
        progress = p
        avatar.invalidate()
    }

    override fun onDetachedFromWindow() { spinner.cancel(); super.onDetachedFromWindow() }

    private inner class Avatar : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val cx = w / 2; val cy = height / 2f
            val ring = w * 0.045f
            val r = w / 2 - ring * 1.8f
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, cy - r, 0f, cy + r,
                if (state == State.DONE) 0xFF4CD964.toInt() else 0xFF8E9AAF.toInt(),
                if (state == State.DONE) 0xFF2DB84C.toInt() else 0xFF5B6475.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
            paint.shader = null

            if (state == State.DONE) {
                paint.style = Paint.Style.STROKE; paint.color = Color.WHITE
                paint.strokeWidth = r * 0.14f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                c.drawPath(Path().apply { moveTo(cx - r * 0.36f, cy + r * 0.02f); lineTo(cx - r * 0.08f, cy + r * 0.3f); lineTo(cx + r * 0.4f, cy - r * 0.26f) }, paint)
            } else {
                // laptop glyph
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                val sw = r * 0.95f; val sh = r * 0.6f
                c.drawRoundRect(RectF(cx - sw / 2, cy - sh * 0.75f, cx + sw / 2, cy + sh * 0.25f), r * 0.06f, r * 0.06f, paint)
                paint.color = 0xFF5B6475.toInt()
                val inset = r * 0.06f
                c.drawRect(cx - sw / 2 + inset, cy - sh * 0.75f + inset, cx + sw / 2 - inset, cy + sh * 0.25f - inset, paint)
                paint.color = Color.WHITE
                c.drawRoundRect(RectF(cx - sw * 0.62f, cy + sh * 0.3f, cx + sw * 0.62f, cy + sh * 0.42f), r * 0.05f, r * 0.05f, paint)
            }

            val box = RectF(cx - r - ring * 1.1f, cy - r - ring * 1.1f, cx + r + ring * 1.1f, cy + r + ring * 1.1f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = ring; paint.strokeCap = Paint.Cap.ROUND
            when (state) {
                State.WAITING -> { paint.color = tint; c.drawArc(box, spin - 90f, 70f, false, paint) }
                State.PROGRESS -> {
                    paint.color = 0x33888888; c.drawArc(box, 0f, 360f, false, paint)
                    paint.color = tint; c.drawArc(box, -90f, 360f * progress, false, paint)
                }
                State.FAILED -> { paint.color = 0xFFFF3B30.toInt(); c.drawArc(box, 0f, 360f, false, paint) }
                else -> {}
            }
        }
    }
}
