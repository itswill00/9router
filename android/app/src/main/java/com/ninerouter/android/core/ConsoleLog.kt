package com.ninerouter.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app-side equivalent of the desktop console log capture.
 *
 * Every backend subsystem writes here: HTTP ingress, routing, auth, media,
 * probes, updates, storage and lifecycle. Lines are capped like the upstream
 * 200-line buffer and rendered in the Console Log screen as a terminal.
 */
object ConsoleLog {
    const val MAX_LINES = 200

    enum class Level { LOG, INFO, WARN, ERROR, DEBUG }

    data class Line(
        val seq: Long,
        val timestamp: Long,
        val clock: String,
        val level: Level,
        val message: String,
    ) {
        val text: String
            get() = "$clock [${level.name}] $message"
    }

    private val lock = Any()
    private val buffer = ArrayDeque<Line>()
    private var sequence = 0L
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    fun log(level: Level, message: String) {
        val clean = message
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(600)
        if (clean.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
            buffer.addLast(Line(sequence++, now, clock, level, clean))
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
    }

    fun log(message: String) = log(Level.LOG, message)
    fun info(message: String) = log(Level.INFO, message)
    fun warn(message: String) = log(Level.WARN, message)
    fun error(message: String) = log(Level.ERROR, message)
    fun debug(message: String) = log(Level.DEBUG, message)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    fun snapshot(): List<Line> = synchronized(lock) { buffer.toList() }
}
