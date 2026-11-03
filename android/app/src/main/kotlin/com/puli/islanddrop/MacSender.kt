package com.puli.islanddrop

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Sends files/links from Android to the Mac app, speaking the same line-JSON + raw-bytes protocol
 *  that [ReceiverService] speaks in reverse. */
object MacSender {

    sealed class Event {
        object Waiting : Event()
        data class Progress(val p: Float) : Event()
        object Done : Event()
        object Declined : Event()
        data class Failed(val msg: String) : Event()
    }

    private class Prepared(val info: FileInfo, val open: () -> InputStream)

    fun send(c: Context, host: InetAddress, port: Int, out: Outgoing, on: (Event) -> Unit) {
        fun emit(e: Event) = Transfer.main.post { on(e) }

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 6000)
                socket.soTimeout = 130_000
                socket.tcpNoDelay = true
                val output = BufferedOutputStream(socket.getOutputStream(), 262144)
                val input = BufferedInputStream(socket.getInputStream())
                val deviceName = deviceName(c)

                when (out) {
                    is Outgoing.Text -> {
                        val header = JSONObject()
                            .put("v", 1)
                            .put("type", "link")
                            .put("url", out.text)
                            .put("title", "")
                            .put("sender", deviceName)
                        output.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
                        output.flush()
                        readLine(input)
                        emit(Event.Done)
                    }

                    is Outgoing.Files -> {
                        val prepared = out.uris.mapIndexed { i, uri -> prepare(c, uri, out.infos[i]) }
                        val total = prepared.sumOf { it.info.size }
                        val filesJson = JSONArray()
                        prepared.forEach {
                            filesJson.put(
                                JSONObject().put("name", it.info.name).put("size", it.info.size).put("mime", it.info.mime)
                            )
                        }
                        val header = JSONObject()
                            .put("v", 1)
                            .put("type", "files")
                            .put("sender", deviceName)
                            .put("files", filesJson)
                            .put("total", total)
                        output.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
                        output.flush()
                        emit(Event.Waiting)

                        if (readLine(input) != "ACCEPT") {
                            emit(Event.Declined)
                            return
                        }

                        val buf = ByteArray(262144)
                        var sent = 0L
                        var lastTick = 0L
                        for (p in prepared) {
                            p.open().use { stream ->
                                var remaining = p.info.size
                                while (remaining > 0) {
                                    val n = stream.read(buf, 0, minOf(262144L, remaining).toInt())
                                    if (n < 0) throw IOException("${p.info.name} ended early")
                                    output.write(buf, 0, n)
                                    remaining -= n
                                    sent += n
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastTick > 50) {
                                        emit(Event.Progress(sent / maxOf(total, 1L).toFloat()))
                                        lastTick = now
                                    }
                                }
                            }
                        }
                        output.flush()
                        emit(Event.Progress(1f))
                        emit(if (readLine(input) == "DONE") Event.Done else Event.Failed("The Mac couldn't save it"))
                    }
                }
            }
        } catch (e: Exception) {
            emit(Event.Failed(e.message ?: "Couldn't reach the Mac"))
        }
    }

    private fun prepare(c: Context, u: Uri, info: FileInfo): Prepared {
        if (info.size >= 0) {
            return Prepared(info) {
                c.contentResolver.openInputStream(u) ?: throw IOException("Can't read ${info.name}")
            }
        }
        // Unknown size (e.g. some content providers): spool to cache first so we know the length.
        val dir = java.io.File(c.cacheDir, "share").apply { mkdirs() }
        val file = java.io.File(dir, "${System.nanoTime()}-${info.name}")
        (c.contentResolver.openInputStream(u) ?: throw IOException("Can't read ${info.name}")).use { input ->
            java.io.FileOutputStream(file).use { out -> input.copyTo(out) }
        }
        return Prepared(info.copy(size = file.length())) { java.io.FileInputStream(file) }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) return buf.toString("UTF-8").trim()
            buf.write(b)
        }
    }
}
