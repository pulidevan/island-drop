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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny hand-rolled HTTP/1.1 server (no HTTP library — just enough to serve [WebPage] and
 * handle uploads/downloads). This is the iPhone path: since there's no iOS app, the iPhone opens
 * this page in Safari and sends/receives files through it.
 */
object WebBridge {

    class OutItem(val id: Int, val name: String, val size: Long, val mime: String, val file: File?, val text: String?) {
        @Volatile var ready: Boolean = file == null
    }

    private val outbox = CopyOnWriteArrayList<OutItem>()
    private val ids = AtomicInteger(1)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val pool = Executors.newCachedThreadPool()

    @Volatile var port: Int = 0
        private set
    @Volatile var lastVisit: Long = 0
        private set
    val token: String = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private var server: ServerSocket? = null

    fun getOutbox(): List<OutItem> = outbox

    fun observe(l: () -> Unit) = listeners.add(l)
    fun unobserve(l: () -> Unit) = listeners.remove(l)
    private fun changed() = Transfer.main.post { listeners.forEach { it() } }

    fun url(c: Context): String? {
        val ip = wifiIp(c) ?: return null
        if (port == 0) return null
        return "http://$ip:$port/?k=$token"
    }

    fun start(c: Context) {
        if (server != null) return
        val app = c.applicationContext
        pool.execute { runServer(app) }
    }

    private fun runServer(c: Context) {
        val s = runCatching { ServerSocket(8765) }.getOrElse { ServerSocket(0) }
        server = s
        port = s.localPort
        changed()
        while (!s.isClosed) {
            val socket = runCatching { s.accept() }.getOrNull() ?: return
            pool.execute {
                runCatching { handle(c, socket) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    fun addUris(c: Context, uris: List<Uri>) {
        val app = c.applicationContext
        val dir = File(app.filesDir, "outbox").apply { mkdirs() }
        val pending = uris.map { uri ->
            val info = Outgoing.describe(app, uri)
            val item = OutItem(ids.getAndIncrement(), info.name, maxOf(info.size, 0L), info.mime, File(dir, "${System.nanoTime()}-${info.name}"), null)
            outbox.add(0, item)
            item to uri
        }
        changed()
        pool.execute {
            for ((item, uri) in pending) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(item.file!!).use { out -> input.copyTo(out) }
                    }
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
        while (outbox.size > 30) {
            outbox.removeAt(outbox.size - 1).file?.delete()
        }
    }

    private fun handle(c: Context, s: Socket) {
        s.use { socket ->
            socket.soTimeout = 120_000
            val input = BufferedInputStream(socket.getInputStream(), 262144)
            val output = BufferedOutputStream(socket.getOutputStream(), 65536)

            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val uri = Uri.parse("http://x" + parts[1])
            val path = uri.path ?: "/"

            if (uri.getQueryParameter("k") != token) {
                respond(output, 403, "text/html", page403().toByteArray(Charsets.UTF_8))
                return
            }

            if (headers["expect"]?.lowercase() == "100-continue") {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }

            when {
                method == "GET" && path == "/" -> {
                    lastVisit = System.currentTimeMillis()
                    changed()
                    respond(output, 200, "text/html; charset=utf-8", WebPage.html(deviceName(c)).toByteArray(Charsets.UTF_8))
                }
                method == "GET" && path == "/files" -> {
                    respond(output, 200, "application/json", filesJson().toByteArray(Charsets.UTF_8))
                }
                method == "GET" && path == "/file" -> {
                    serveFile(output, uri.getQueryParameter("id")?.toIntOrNull())
                }
                method == "POST" && path == "/upload" -> {
                    val name = uri.getQueryParameter("name") ?: "file"
                    val length = headers["content-length"]?.toLongOrNull() ?: -1L
                    val type = headers["content-type"] ?: ""
                    val ua = headers["user-agent"] ?: ""
                    receive(c, input, output, name, length, type, ua)
                }
                else -> respond(output, 404, "text/plain", "Not found".toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun filesJson(): String {
        val arr = JSONArray()
        outbox.filter { it.ready }.forEach { item ->
            val o = JSONObject().put("id", item.id).put("name", item.name).put("size", item.size).put("mime", item.mime)
            item.text?.let { o.put("text", it) }
            arr.put(o)
        }
        return JSONObject().put("device", "").put("items", arr).toString()
    }

    private fun serveFile(out: OutputStream, id: Int?) {
        val item = outbox.firstOrNull { it.id == id && it.file != null && it.ready }
        val file = item?.file
        if (item == null || file == null || !file.exists()) {
            respond(out, 404, "text/plain", "Gone".toByteArray(Charsets.UTF_8))
            return
        }
        val encoded = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
        val head = "HTTP/1.1 200 OK\r\nContent-Type: ${item.mime}\r\nContent-Length: ${file.length()}\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$encoded\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        FileInputStream(file).use { it.copyTo(out, 262144) }
        out.flush()
    }

    private fun receive(c: Context, input: InputStream, out: OutputStream, rawName: String, length: Long, type: String, ua: String) {
        if (length < 0) {
            respond(out, 411, "text/plain", "Length required".toByteArray(Charsets.UTF_8))
            return
        }
        var name = Regex("[/\\\\:*?\"<>|]").replace(rawName, "_").trim()
        if (name.isBlank()) name = "file"
        name = name.take(200)

        val sender = when {
            ua.contains("iPhone") -> "iPhone"
            ua.contains("iPad") -> "iPad"
            ua.contains("Macintosh") -> "Mac"
            else -> "Browser"
        }
        val mime = type.ifBlank { "application/octet-stream" }
        val info = FileInfo(name, length, mime)

        val transfer = Transfer(Transfer.nextId(), sender, Transfer.Kind.FILES, listOf(info), length)
        transfer.state = Transfer.State.RECEIVING
        Transfer.active[transfer.id] = transfer
        Presenter.present(c, transfer)

        val uri = Saver.create(c, info)
        try {
            c.contentResolver.openOutputStream(uri)!!.use { output ->
                val buf = ByteArray(262144)
                var remaining = length
                var lastTick = 0L
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(262144L, remaining).toInt())
                    if (n < 0) throw IOException("Upload interrupted")
                    output.write(buf, 0, n)
                    remaining -= n
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTick > 60) {
                        transfer.progress = 1f - remaining / maxOf(length, 1L).toFloat()
                        transfer.changed()
                        lastTick = now
                    }
                }
            }
            Saver.finish(c, uri)
            transfer.saved.add(uri)
            transfer.progress = 1f
            transfer.state = Transfer.State.DONE
            transfer.changed()
            Notifs.done(c, transfer)
            respond(out, 200, "application/json", "{\"ok\":true}".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            runCatching { c.contentResolver.delete(uri, null, null) }
            transfer.error = e.message
            transfer.state = Transfer.State.FAILED
            transfer.changed()
            runCatching { respond(out, 500, "text/plain", (e.message ?: "error").toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found", 411 to "Length Required", 500 to "Error")[code] ?: "OK"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < 16384) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == 10) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
        return null
    }

    private fun page403(): String = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
        <body style="font:17px -apple-system;text-align:center;padding:60px 24px;color:#888">
        <h2 style="color:#111">Scan the code again</h2>This link has expired. Open Island Drop on the tablet and scan its QR code.</body>"""

    private fun wifiIp(c: Context): String? {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
