package com.puli.islanddrop

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Always-on receiver: TCP server advertised over mDNS as `_islanddrop._tcp`, which the Mac discovers
 * with Bonjour. Runs as a connectedDevice foreground service and restarts on boot.
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

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        val n = Notifs.ready(this, "Starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.READY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifs.READY_ID, n)
        }
        running = true
        pool.execute { serve() }
        WebBridge.start(this)
        macFinder = MacFinder(this) { list -> Shortcuts.publish(this, list.map { it.serviceName }) }
            .also { runCatching { it.start() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
                ServerSocket(0).use { ss ->
                    server = ss
                    register(ss.localPort)
                    while (running && !ss.isClosed) {
                        val s = ss.accept()
                        pool.execute { handle(s) }
                    }
                }
            } catch (e: IOException) {
                if (!running) return
                status = "Restarting…"
                SystemClock.sleep(2000)
            }
        }
    }

    private fun register(port: Int) {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicast = wifi.createMulticastLock("islanddrop").apply { setReferenceCounted(false); acquire() }
        val name = deviceName(this)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                visibleAs = i.serviceName
                status = "Ready"
                Notifs.updateReady(this@ReceiverService, "Ready to receive · visible as ${i.serviceName}")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                status = "Network error ($code)"
                Transfer.main.postDelayed({ if (running) register(port) }, 3000)
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        nsd = getSystemService(NsdManager::class.java)
        nsd!!.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registration = listener
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 130_000
            s.tcpNoDelay = true
            val input = BufferedInputStream(s.getInputStream(), 256 * 1024)
            val out = s.getOutputStream()
            val line = readLine(input) ?: return
            val header = runCatching { JSONObject(line) }.getOrNull() ?: return
            when (header.optString("type")) {
                "files" -> receiveFiles(header, input, out)
                "link" -> receiveLink(header, out)
            }
        }
    }

    private fun receiveLink(h: JSONObject, out: OutputStream) {
        val t = Transfer(
            Transfer.nextId(), h.optString("sender", "Mac"), Transfer.Kind.LINK,
            url = h.optString("url"), title = h.optString("title"),
        )
        Transfer.active[t.id] = t
        Presenter.present(this, t)
        out.write("OK\n".toByteArray()); out.flush()
    }

    private fun receiveFiles(h: JSONObject, input: InputStream, out: OutputStream) {
        val arr = h.getJSONArray("files")
        val files = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            FileInfo(safeName(o.optString("name", "file")), o.optLong("size"), o.optString("mime", "application/octet-stream"))
        }
        val t = Transfer(Transfer.nextId(), h.optString("sender", "Mac"), Transfer.Kind.FILES, files, h.optLong("total"))
        Transfer.active[t.id] = t
        if (Prefs.autoAccept(this)) t.decide(true)
        Presenter.present(this, t)

        if (!t.awaitDecision(110)) {
            t.state = Transfer.State.DECLINED
            t.changed()
            Notifs.cancel(this, t.id)
            out.write("DECLINE\n".toByteArray()); out.flush()
            return
        }
        t.state = Transfer.State.RECEIVING
        t.changed()
        out.write("ACCEPT\n".toByteArray()); out.flush()

        val pending = mutableListOf<Uri>()
        try {
            val buf = ByteArray(256 * 1024)
            var received = 0L
            var lastUi = 0L
            var lastNotif = 0L
            for (f in files) {
                val uri = Saver.create(this, f)
                pending += uri
                contentResolver.openOutputStream(uri)!!.use { os ->
                    var remaining = f.size
                    while (remaining > 0) {
                        val n = input.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw IOException("Connection lost")
                        os.write(buf, 0, n)
                        remaining -= n
                        received += n
                        val now = SystemClock.uptimeMillis()
                        if (now - lastUi > 50) {
                            lastUi = now
                            t.progress = received.toFloat() / maxOf(t.total, 1L)
                            t.changed()
                        }
                        if (now - lastNotif > 700 && !Presenter.isVisible(t.id)) {
                            lastNotif = now
                            Notifs.progress(this, t)
                        }
                    }
                }
                Saver.finish(this, uri)
                t.saved += uri
            }
            out.write("DONE\n".toByteArray()); out.flush()
            t.progress = 1f
            t.state = Transfer.State.DONE
            t.changed()
            Notifs.done(this, t)
        } catch (e: Exception) {
            pending.filter { it !in t.saved }.forEach { runCatching { contentResolver.delete(it, null, null) } }
            t.error = e.message ?: "Transfer failed"
            t.state = Transfer.State.FAILED
            t.changed()
            Notifs.cancel(this, t.id)
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 1_000_000) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString("UTF-8")
            bytes.write(b)
        }
        return null
    }

    private fun safeName(n: String) =
        n.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").trim().ifBlank { "file" }.take(200)

    companion object {
        const val SERVICE_TYPE = "_islanddrop._tcp"
        @Volatile var status: String = "Starting…"
        @Volatile var visibleAs: String? = null

        fun start(c: Context) {
            c.startForegroundService(Intent(c, ReceiverService::class.java))
        }
    }
}
