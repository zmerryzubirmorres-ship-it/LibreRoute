package io.github.libreroute.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private const val MAX_LOG_HISTORY = 1000

    data class LogEntry(
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val logHistory = ArrayDeque<LogEntry>(MAX_LOG_HISTORY)
    private val lock = Any()
    private var pendingJitsiIssue: AppEvent.JitsiModeratorRequired? = null
    private var pendingYandexAuthIssue: AppEvent.YandexAuthIssue? = null

    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    fun getLogHistory(): List<String> = synchronized(lock) {
        logHistory.map { it.message }
    }

    fun getLogEntries(): List<LogEntry> = synchronized(lock) {
        logHistory.toList()
    }

    fun clearLogHistory() = synchronized(lock) {
        logHistory.clear()
    }

    fun initFromSavedLogs(entries: List<String>) = synchronized(lock) {
        if (logHistory.isNotEmpty()) return@synchronized
        val now = System.currentTimeMillis()
        for (line in entries) {
            if (line.isNotBlank()) {
                if (logHistory.size >= MAX_LOG_HISTORY) {
                    logHistory.removeFirst()
                }
                logHistory.addLast(LogEntry(line, now))
            }
        }
    }

    fun pendingYandexAuthIssue(): AppEvent.YandexAuthIssue? = synchronized(lock) {
        pendingYandexAuthIssue
    }

    fun clearPendingYandexAuthIssue(documentUrl: String? = null) = synchronized(lock) {
        if (documentUrl == null || pendingYandexAuthIssue?.documentUrl == documentUrl) pendingYandexAuthIssue = null
    }

    fun pendingJitsiIssue(): AppEvent.JitsiModeratorRequired? = synchronized(lock) { pendingJitsiIssue }
    fun clearPendingJitsiIssue() = synchronized(lock) { pendingJitsiIssue = null }

    fun dispatch(event: AppEvent, writeToFile: Boolean = true) {
        if (event is AppEvent.JitsiModeratorRequired) synchronized(lock) { pendingJitsiIssue = event }
        val finalEvent = if (event is AppEvent.LogMessage) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                event.message.split('\n').forEach { line ->
                    if (line.isNotEmpty()) {
                        if (logHistory.size >= MAX_LOG_HISTORY) {
                            logHistory.removeFirst()
                        }
                        logHistory.addLast(LogEntry(line, now))
                    }
                }
            }
            if (writeToFile) {
                io.github.libreroute.util.FileLogger.append(event.message)
            }
            event
        } else {
            if (event is AppEvent.YandexAuthIssue) {
                synchronized(lock) { pendingYandexAuthIssue = event }
            }
            event
        }
        _events.tryEmit(finalEvent)
    }
}
