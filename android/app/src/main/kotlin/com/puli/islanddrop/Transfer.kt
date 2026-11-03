package com.puli.islanddrop

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Transfer(
    val id: Int,
    val sender: String,
    val kind: Kind,
    val files: List<FileInfo> = emptyList(),
    val total: Long = 0L,
    val url: String? = null,
    val title: String? = null,
    val demo: Boolean = false
) {
    enum class Kind { FILES, LINK }
    enum class State { ASKING, RECEIVING, DONE, FAILED, DECLINED }

    @Volatile var state: State = if (kind == Kind.LINK) State.DONE else State.ASKING
    @Volatile var progress: Float = 0f
    @Volatile var error: String? = null
    val saved = CopyOnWriteArrayList<Uri>()

    private val decision = LinkedBlockingQueue<Boolean>(1)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun decide(accept: Boolean) {
        if (state == State.ASKING) decision.offer(accept)
    }

    fun awaitDecision(timeoutSec: Long): Boolean =
        decision.poll(timeoutSec, TimeUnit.SECONDS) ?: false

    fun observe(l: () -> Unit) {
        listeners.add(l)
    }

    fun unobserve(l: () -> Unit) {
        listeners.remove(l)
    }

    fun changed() {
        main.post { listeners.forEach { it() } }
    }

    val primaryMime: String
        get() = files.firstOrNull()?.mime?.takeIf { it.isNotBlank() } ?: "*/*"

    val summary: String
        get() = if (kind != Kind.LINK) {
            if (files.size == 1) files[0].name else "${files.size} files"
        } else {
            title?.takeIf { it.isNotBlank() } ?: "Link"
        }

    companion object {
        val main = Handler(Looper.getMainLooper())
        private val ids = AtomicInteger(100)
        val active = ConcurrentHashMap<Int, Transfer>()
        fun nextId(): Int = ids.incrementAndGet()
    }
}
