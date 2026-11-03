package com.puli.islanddrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * iPhone ↔ Android without an app on the iPhone: a tiny HTTP server on the LAN.
 * The iPhone scans a QR code (camera) → Safari page that uploads to the tablet and downloads
 * whatever the tablet is sharing ("outbox"). Every request needs the secret key from the QR code.
 */
object WebBridge {
    class OutItem(val id: Int, val name: String, val size: Long, val mime: String, val file: File?, val text: String?) {
        @Volatile var ready = file == null
    }

    val outbox = CopyOnWriteArrayList<OutItem>()
    private val ids = AtomicInteger(1)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var port = 0; private set
    @Volatile var lastVisit = 0L; private set
    val token: String = ByteArray(9).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun observe(l: () -> Unit) { listeners += l }
    fun unobserve(l: () -> Unit) { listeners -= l }
    private fun changed() { Transfer.main.post { listeners.forEach { it() } } }

    fun url(c: Context): String? {
        val ip = wifiIp(c) ?: return null
        if (port == 0) return null
        return "http://$ip:$port/?k=$token"
    }

    fun start(c: Context) {
        if (server != null) return
        val app = c.applicationContext
        pool.execute {
            val ss = runCatching { ServerSocket(8765) }.getOrElse { ServerSocket(0) }
            server = ss
            port = ss.localPort
            changed()
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(app, s) } }
            }
        }
    }

    fun stop() { runCatching { server?.close() }; server = null; port = 0 }

    // region outbox (tablet → iPhone)

    fun addUris(c: Context, uris: List<Uri>) {
        val app = c.applicationContext
        val dir = File(app.filesDir, "outbox").apply { mkdirs() }
        val items = uris.map { u ->
            val info = Outgoing.describe(app, u)
            OutItem(ids.getAndIncrement(), info.name, maxOf(info.size, 0), info.mime, File(dir, "${System.nanoTime()}-${info.name}"), null)
                .also { outbox.add(0, it) } to u
        }
        changed()
        pool.execute {
            for ((item, u) in items) {
                runCatching {
                    app.contentResolver.openInputStream(u)?.use { i -> item.file!!.outputStream().use { i.copyTo(it) } }
                }
                item.ready = true
                changed()
            }
            trim()
        }
    }

    fun addText(text: String) {
        outbox.add(0, OutItem(ids.getAndIncrement(), text.take(80), text.length.toLong(), "text/plain", null, text))
        changed()
        trim()
    }

    fun clearOutbox() {
        outbox.forEach { it.file?.delete() }
        outbox.clear()
        changed()
    }

    private fun trim() {
        while (outbox.size > 30) outbox.removeAt(outbox.size - 1).file?.delete()
    }

    // endregion

    // region HTTP

    private fun handle(c: Context, s: Socket) = s.use {
        s.soTimeout = 120_000
        val input = BufferedInputStream(s.getInputStream(), 256 * 1024)
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        val request = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val parts = request.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val uri = Uri.parse("http://x" + parts[1])
        val path = uri.path ?: "/"
        if (uri.getQueryParameter("k") != token) {
            respond(out, 403, "text/html", page403().toByteArray())
            return
        }
        if (headers["expect"]?.lowercase() == "100-continue") {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
        }
        when {
            method == "GET" && path == "/" -> {
                lastVisit = System.currentTimeMillis()
                changed()
                respond(out, 200, "text/html; charset=utf-8", WebPage.html(deviceName(c)).toByteArray())
            }
            method == "GET" && path == "/files" -> respond(out, 200, "application/json", filesJson().toByteArray())
            method == "GET" && path == "/file" -> serveFile(out, uri.getQueryParameter("id")?.toIntOrNull())
            method == "POST" && path == "/upload" -> receive(c, input, out, uri.getQueryParameter("name") ?: "file",
                headers["content-length"]?.toLongOrNull() ?: -1, headers["content-type"] ?: "", headers["user-agent"] ?: "")
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun filesJson(): String {
        val a = JSONArray()
        outbox.filter { it.ready }.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("mime", it.mime)
                .apply { it.text?.let { t -> put("text", t) } })
        }
        return JSONObject().put("device", "").put("items", a).toString()
    }

    private fun serveFile(out: OutputStream, id: Int?) {
        val item = outbox.firstOrNull { it.id == id && it.file != null && it.ready }
        val f = item?.file
        if (item == null || f == null || !f.exists()) { respond(out, 404, "text/plain", "Gone".toByteArray()); return }
        val enc = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
        val head = "HTTP/1.1 200 OK\r\nContent-Type: ${item.mime}\r\nContent-Length: ${f.length()}\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$enc\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        f.inputStream().use { it.copyTo(out, 256 * 1024) }
        out.flush()
    }

    private fun receive(c: Context, input: InputStream, out: OutputStream, rawName: String, length: Long, type: String, ua: String) {
        if (length < 0) { respond(out, 411, "text/plain", "Length required".toByteArray()); return }
        val name = rawName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifBlank { "file" }.take(200)
        val sender = when {
            "iPhone" in ua -> "iPhone"
            "iPad" in ua -> "iPad"
            "Macintosh" in ua -> "Mac"
            else -> "Browser"
        }
        val info = FileInfo(name, length, type.ifBlank { "application/octet-stream" })
        val t = Transfer(Transfer.nextId(), sender, Transfer.Kind.FILES, listOf(info), length)
        t.state = Transfer.State.RECEIVING
        Transfer.active[t.id] = t
        Presenter.present(c, t)
        val uri = Saver.create(c, info)
        try {
            c.contentResolver.openOutputStream(uri)!!.use { os ->
                val buf = ByteArray(256 * 1024)
                var remaining = length
                var last = 0L
                while (remaining > 0) {
                    val n = input.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Upload interrupted")
                    os.write(buf, 0, n)
                    remaining -= n
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 60) { last = now; t.progress = 1f - remaining.toFloat() / maxOf(length, 1L); t.changed() }
                }
            }
            Saver.finish(c, uri)
            t.saved += uri
            t.progress = 1f
            t.state = Transfer.State.DONE
            t.changed()
            Notifs.done(c, t)
            respond(out, 200, "application/json", "{\"ok\":true}".toByteArray())
        } catch (e: Exception) {
            runCatching { c.contentResolver.delete(uri, null, null) }
            t.error = e.message
            t.state = Transfer.State.FAILED
            t.changed()
            runCatching { respond(out, 500, "text/plain", (e.message ?: "error").toByteArray()) }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found", 411 to "Length Required", 500 to "Error")[code] ?: "OK"
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (b.size() < 16_384) {
            val x = input.read()
            if (x < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (x == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(x)
        }
        return null
    }

    private fun page403() = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
        <body style="font:17px -apple-system;text-align:center;padding:60px 24px;color:#888">
        <h2 style="color:#111">Scan the code again</h2>This link has expired. Open Island Drop on the tablet and scan its QR code.</body>"""

    @Suppress("DEPRECATION")
    private fun wifiIp(c: Context): String? {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    // endregion
}
