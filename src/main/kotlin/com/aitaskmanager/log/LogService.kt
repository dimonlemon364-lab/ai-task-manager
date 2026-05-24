package com.aitaskmanager.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

data class LogLine(
    val timestamp: Long,
    val taskId: String?,
    val message: String,
) {
    fun format(): String {
        val ts = TS_FORMAT.get().format(Date(timestamp))
        val owner = taskId?.let { "[$it] " } ?: ""
        return "$ts $owner$message"
    }

    companion object {
        private val TS_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss") }
    }
}

interface LogListener {
    fun onLogLine(line: LogLine)
    fun onCleared()
}

@Service(Service.Level.APP)
class LogService {
    private val lines = ArrayDeque<LogLine>()
    private val listeners = CopyOnWriteArrayList<LogListener>()
    private val lock = Any()

    fun append(message: String, taskId: String? = null) {
        val line = LogLine(System.currentTimeMillis(), taskId, message)
        synchronized(lock) {
            lines.add(line)
            while (lines.size > MAX_LINES) lines.pollFirst()
        }
        ApplicationManager.getApplication().invokeLater {
            for (l in listeners) {
                try { l.onLogLine(line) } catch (_: Throwable) { /* ignore */ }
            }
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        ApplicationManager.getApplication().invokeLater {
            for (l in listeners) {
                try { l.onCleared() } catch (_: Throwable) { /* ignore */ }
            }
        }
    }

    fun snapshot(): List<LogLine> = synchronized(lock) { lines.toList() }

    fun snapshotForTask(taskId: String): List<LogLine> =
        synchronized(lock) { lines.filter { it.taskId == taskId }.toList() }

    /**
     * Bulk-insert lines preserving their original [LogLine.timestamp] and [LogLine.taskId].
     * Used by [com.aitaskmanager.io.ExportImport.import] to restore the saved log buffer.
     * Listeners receive an `onLogLine` callback for each restored line on the EDT, so
     * `LogTab` repaints them through the active filter just like live entries.
     */
    fun restoreLines(restored: List<LogLine>) {
        if (restored.isEmpty()) return
        synchronized(lock) {
            for (l in restored) {
                lines.add(l)
                while (lines.size > MAX_LINES) lines.pollFirst()
            }
        }
        ApplicationManager.getApplication().invokeLater {
            for (l in restored) {
                for (listener in listeners) {
                    try { listener.onLogLine(l) } catch (_: Throwable) { /* ignore */ }
                }
            }
        }
    }

    fun addListener(l: LogListener) { listeners.add(l) }
    fun removeListener(l: LogListener) { listeners.remove(l) }

    companion object {
        const val MAX_LINES = 10_000

        @JvmStatic
        fun getInstance(): LogService =
            ApplicationManager.getApplication().getService(LogService::class.java)
    }
}
