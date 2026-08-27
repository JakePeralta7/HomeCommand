package net.elad.homecommand.data

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-app log ring buffer. Every [AppLog] call appends to the deque, forwards to
 * [Log], and posts to [flow] so the Logs screen can render entries in real time.
 * Cleared on process death — intentional (lightweight diagnostics, not audit trail).
 */
object AppLog {
    private const val MAX_ENTRIES = 500

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    private val _flow = MutableSharedFlow<Entry>(extraBufferCapacity = 64)
    val flow: SharedFlow<Entry> = _flow.asSharedFlow()

    fun d(
        tag: String,
        message: String,
    ) = append(Level.DEBUG, tag, message)

    fun i(
        tag: String,
        message: String,
    ) = append(Level.INFO, tag, message)

    fun w(
        tag: String,
        message: String,
    ) = append(Level.WARN, tag, message)

    fun e(
        tag: String,
        message: String,
        t: Throwable? = null,
    ) {
        append(Level.ERROR, tag, if (t != null) "$message: ${t.message}" else message)
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun append(
        level: Level,
        tag: String,
        message: String,
    ) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(entry)
        }
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }
        _flow.tryEmit(entry)
    }
}
