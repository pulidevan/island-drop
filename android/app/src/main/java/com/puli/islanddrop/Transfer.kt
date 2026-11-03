package com.puli.islanddrop

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class FileInfo(val name: String, val size: Long, val mime: String) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

/** One incoming request from the Mac – shared between the socket thread and the UI. */
class Transfer(
    val id: Int,
    val sender: String,
    val kind: Kind,
    val files: List<FileInfo> = emptyList(),
    val total: Long = 0,
    val url: String? = null,
    val title: String? = null,
    val demo: Boolean = false,
) {
    enum class Kind { FILES, LINK }
    enum class State { ASKING, RECEIVING, DONE, FAILED, DECLINED }

    @Volatile var state = if (kind == Kind.LINK) State.DONE else State.ASKING
    @Volatile var progress = 0f
    @Volatile var error: String? = null
    val saved = CopyOnWriteArrayList<Uri>()

    private val decision = LinkedBlockingQueue<Boolean>(1)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun decide(accept: Boolean) {
        if (state == State.ASKING) decision.offer(accept)
    }

    fun awaitDecision(timeoutSec: Long): Boolean = decision.poll(timeoutSec, TimeUnit.SECONDS) ?: false

    fun observe(l: () -> Unit) { listeners += l }
    fun unobserve(l: () -> Unit) { listeners -= l }
    fun changed() { main.post { listeners.forEach { it() } } }

    val primaryMime: String get() = files.firstOrNull()?.mime?.ifBlank { null } ?: "*/*"

    val summary: String
        get() = when {
            kind == Kind.LINK -> title?.ifBlank { null } ?: "Link"
            files.size == 1 -> files[0].name
            else -> "${files.size} files"
        }

    companion object {
        val main = Handler(Looper.getMainLooper())
        private val ids = AtomicInteger(100)
        fun nextId() = ids.incrementAndGet()
        val active = ConcurrentHashMap<Int, Transfer>()
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun autoAccept(c: Context) = p(c).getBoolean("auto_accept", false)
    fun setAutoAccept(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_accept", v).apply()
}

fun deviceName(c: Context): String {
    val n = Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME)
        ?: Settings.Secure.getString(c.contentResolver, "bluetooth_name")
        ?: Build.MODEL
    return n.take(60)
}
