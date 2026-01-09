package com.tinydictation.android.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Singleton debug log that stores recent log entries for debugging purposes.
 * Stores the last 100 log entries and can export them as a copyable string.
 */
object DebugLog {

    private const val MAX_ENTRIES = 500
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // Thread-safe queue for log entries
    private val entries = ConcurrentLinkedQueue<LogEntry>()

    /**
     * Log levels for categorizing entries.
     */
    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * Represents a single log entry.
     */
    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val message: String
    ) {
        fun format(): String {
            val time = dateFormat.format(Date(timestamp))
            val levelStr = level.name.padEnd(5)
            return "$time [$levelStr] $message"
        }
    }

    /**
     * Adds a DEBUG level log entry.
     */
    fun d(message: String) {
        addEntry(Level.DEBUG, message)
    }

    /**
     * Adds an INFO level log entry.
     */
    fun i(message: String) {
        addEntry(Level.INFO, message)
    }

    /**
     * Adds a WARN level log entry.
     */
    fun w(message: String) {
        addEntry(Level.WARN, message)
    }

    /**
     * Adds an ERROR level log entry.
     */
    fun e(message: String) {
        addEntry(Level.ERROR, message)
    }

    /**
     * Adds a log entry with the specified level.
     */
    private fun addEntry(level: Level, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message
        )

        entries.add(entry)

        // Keep only last MAX_ENTRIES
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
    }

    /**
     * Exports all log entries as a formatted string.
     * Entries are ordered from oldest to newest.
     */
    fun exportLog(): String {
        if (entries.isEmpty()) {
            return "No log entries"
        }

        val sb = StringBuilder()
        sb.appendLine("=== Tiny Dictation Debug Log ===")
        sb.appendLine("Total entries: ${entries.size}")
        sb.appendLine()

        entries.forEach { entry ->
            sb.appendLine(entry.format())
        }

        return sb.toString()
    }

    /**
     * Clears all log entries.
     */
    fun clear() {
        entries.clear()
    }

    /**
     * Returns the current number of log entries.
     */
    fun size(): Int = entries.size
}
