package com.tinydictation.android.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object DebugLog {

    private const val MAX_ENTRIES = 500
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val entries = ConcurrentLinkedQueue<LogEntry>()

    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val message: String,
        val text: String? = null
    ) {
        fun format(): String {
            val time = dateFormat.format(Date(timestamp))
            val levelStr = level.name.padEnd(5)
            return "$time [$levelStr] $message${text?.let { " $it" } ?: ""}"
        }
    }

    fun d(message: String, text: String? = null) {
        addEntry(Level.DEBUG, message, text)
    }

    fun i(message: String, text: String? = null) {
        addEntry(Level.INFO, message, text)
    }

    fun w(message: String, text: String? = null) {
        addEntry(Level.WARN, message, text)
    }

    fun e(message: String, text: String? = null) {
        addEntry(Level.ERROR, message, text)
    }

    private fun addEntry(level: Level, message: String, text: String?) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            text = text
        )

        entries.add(entry)

        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
    }

    fun exportLog(): String {
        if (entries.isEmpty()) {
            return "No log entries"
        }

        val sb = StringBuilder()
        sb.appendLine("=== Tiny Dictation Debug Log ===")
        sb.appendLine("Total entries: ${entries.size}")
        sb.appendLine()

        entries.forEach { entry ->
            sb.appendLine(entry.copy(text = entry.text?.let { "(${it.length} chars)" }).format())
        }

        return sb.toString()
    }

    fun clear() {
        entries.clear()
    }

    fun size(): Int = entries.size
}
