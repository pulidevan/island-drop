package com.puli.islanddrop

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Foreground service that:
 *  - advertises this device over Bonjour/mDNS as `_islanddrop._tcp` (the Mac browses for this)
 *  - runs a raw TCP server behind that advertised port
 *  - speaks a tiny line-JSON header + raw-bytes protocol to receive files or links
 *  - also starts [WebBridge], the embedded HTTP server used by the iPhone web page
 */
class ReceiverService : Service() {

    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var macFinder: MacFinder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        val notification = Notifs.ready(this, "Starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        running = true
        pool.execute { serve() }
        WebBridge.start(this)

        val finder = MacFinder(this) { list ->
            Shortcuts.publish(this, list.map { it.serviceName })
        }
        runCatching { finder.start() }
        macFinder = finder
    }

    override fun onDestroy() {
        running = false
        status = "Stopped"
        macFinder?.stop()
        WebBridge.stop()
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { server?.close() }
        runCatching { multicast?.release() }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun serve() {
        while (running) {
            try {
                ServerSocket(0).use { s ->
                    server = s
                    register(s.localPort)
                    while (running && !s.isClosed) {
                        val socket = s.accept()
                        pool.execute { handle(socket) }
                    }
                }
            } catch (_: IOException) {
                if (!running) return
                status = "Restarting…"
                SystemClock.sleep(2000)
            }
        }
    }

    private fun register(port: Int) {
        val lock = (applicationContext.getSystemService(WifiManager::class.java))
            .createMulticastLock("islanddrop")
        lock.setReferenceCounted(false)
        lock.acquire()
        multicast = lock

        val name = deviceName(this)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                visibleAs = info.serviceName
                status = "Ready to receive"
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                status = "Couldn't advertise ($errorCode)"
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        runCatching { registration?.let { nsd?.unregisterService(it) } }
        val manager = getSystemService(NsdManager::class.java)
        nsd = manager
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registration = listener
    }

    private fun handle(sock: Socket) {
        sock.use { socket ->
            socket.soTimeout = 130_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 262144)
            val output = socket.getOutputStream()

            val line = readLine(input) ?: return
            val header = runCatching { JSONObject(line) }.getOrNull() ?: return

            when (header.optString("type")) {
                "files" -> receiveFiles(header, input, output)
                "link" -> receiveLink(header, output)
            }
        }
    }

    private fun receiveLink(h: JSONObject, out: OutputStream) {
        val transfer = Transfer(
            Transfer.nextId(), h.optString("sender", "Mac"), Transfer.Kind.LINK,
            url = h.optString("url"), title = h.optString("title")
        )
        Transfer.active[transfer.id] = transfer
        Presenter.present(this, transfer)
        out.write("OK\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun receiveFiles(h: JSONObject, input: InputStream, out: OutputStream) {
        val filesJson: JSONArray = h.getJSONArray("files")
        val files = (0 until filesJson.length()).map { i ->
            val o = filesJson.getJSONObject(i)
            FileInfo(
                safeName(o.optString("name", "file")),
                o.optLong("size"),
                o.optString("mime", "application/octet-stream")
            )
        }

        val transfer = Transfer(Transfer.nextId(), h.optString("sender", "Mac"), Transfer.Kind.FILES, files, h.optLong("total"))
        Transfer.active[transfer.id] = transfer

        if (Prefs.autoAccept(this)) transfer.decide(true)
        Presenter.present(this, transfer)

        if (!transfer.awaitDecision(110)) {
            transfer.state = Transfer.State.DECLINED
            transfer.changed()
            Notifs.cancel(this, transfer.id)
            out.write("DECLINE\n".toByteArray(Charsets.UTF_8))
            out.flush()
            return
        }

        transfer.state = Transfer.State.RECEIVING
        transfer.changed()
        out.write("ACCEPT\n".toByteArray(Charsets.UTF_8))
        out.flush()

        val buf = ByteArray(262144)
        var received = 0L
        var lastProgressTick = 0L
        var lastNotifTick = 0L

        try {
            for (info in files) {
                val uri: Uri = Saver.create(this, info)
                contentResolver.openOutputStream(uri)!!.use { fileOut ->
                    var remaining = info.size
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(262144L, remaining).toInt())
                        if (n < 0) throw IOException("Connection lost")
                        fileOut.write(buf, 0, n)
                        remaining -= n
                        received += n
                        val now = SystemClock.uptimeMillis()
                        if (now - lastProgressTick > 50) {
                            transfer.progress = received / maxOf(transfer.total, 1L).toFloat()
                            transfer.changed()
                            lastProgressTick = now
                        }
                        if (now - lastNotifTick > 700 && !Presenter.isVisible(transfer.id)) {
                            Notifs.progress(this, transfer)
                            lastNotifTick = now
                        }
                    }
                }
                Saver.finish(this, uri)
                transfer.saved.add(uri)
            }
            out.write("DONE\n".toByteArray(Charsets.UTF_8))
            out.flush()
            transfer.progress = 1f
            transfer.state = Transfer.State.DONE
            transfer.changed()
            Notifs.done(this, transfer)
        } catch (e: Exception) {
            transfer.saved.forEach { runCatching { contentResolver.delete(it, null, null) } }
            transfer.error = e.message ?: "Transfer failed"
            transfer.state = Transfer.State.FAILED
            transfer.changed()
            Notifs.cancel(this, transfer.id)
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < 1_000_000) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) return buf.toString("UTF-8")
            buf.write(b)
        }
        return null
    }

    private fun safeName(n: String): String {
        var s = Regex("[/\\\\:*?\"<>|\u0000]").replace(n, "_").trim()
        if (s.isBlank()) s = "file"
        return s.take(200)
    }

    companion object {
        const val SERVICE_TYPE = "_islanddrop._tcp"
        @Volatile var status: String = "Starting…"
        @Volatile var visibleAs: String? = null

        fun start(c: android.content.Context) {
            c.startForegroundService(Intent(c, ReceiverService::class.java))
        }
    }
}
