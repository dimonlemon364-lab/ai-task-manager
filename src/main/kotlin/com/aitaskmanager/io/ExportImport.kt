package com.aitaskmanager.io

import com.aitaskmanager.log.LogLine
import com.aitaskmanager.log.LogService
import com.aitaskmanager.task.AiTask
import com.aitaskmanager.task.TaskRegistry
import com.aitaskmanager.task.TaskState
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ExportImport {

    /** Pack current task list + per-task logs + global log into a ZIP with maximum compression. */
    fun export(target: File) {
        val tasks = TaskRegistry.getInstance().snapshot()
        val allLogs = LogService.getInstance().snapshot()

        FileOutputStream(target).use { fos ->
            ZipOutputStream(fos).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)

                writeEntry(zip, "tasks.json", buildTasksJson(tasks))
                writeEntry(zip, "global.log", buildLogText(allLogs))

                val byTask = allLogs.groupBy { it.taskId }
                for (task in tasks) {
                    val perTask = byTask[task.id].orEmpty()
                    if (perTask.isNotEmpty()) {
                        val safe = task.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        writeEntry(zip, "logs/$safe.log", buildLogText(perTask))
                    }
                }
            }
        }
    }

    /**
     * Restore tasks AND log lines from a ZIP. Task file paths matching any of
     * [excludedGlobs] (newline-separated) are filtered out. Log lines are restored
     * regardless — the global log is the user's record of past activity.
     */
    fun import(source: File, excludedGlobs: String): List<AiTask> {
        val tasksJson = readEntry(source, "tasks.json") ?: return emptyList()
        val tasks = parseTasksJson(tasksJson)
        val matchers = parseGlobs(excludedGlobs)
        val filteredTasks = tasks.filter { task -> matchers.none { m -> m.matches(task.file) } }

        // Restore the global log (per-task `logs/<id>.log` files are subsets of global.log,
        // so we only need to replay one of them).
        readEntry(source, "global.log")?.let { text ->
            val restored = parseLogText(text)
            if (restored.isNotEmpty()) LogService.getInstance().restoreLines(restored)
        }
        return filteredTasks
    }

    /**
     * Parse the line-by-line text produced by [buildLogText] back into [LogLine]s.
     * The exporter wrote each line as `HH:mm:ss [taskId] message` (`[taskId] ` is omitted
     * for general messages with no task). Since only the wall-clock time was preserved
     * (no date), reconstruct the timestamp as today's date + the parsed time.
     */
    private fun parseLogText(text: String): List<LogLine> {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val out = mutableListOf<LogLine>()
        for (raw in text.lineSequence()) {
            if (raw.isEmpty()) continue
            val m = LOG_LINE_PATTERN.matchEntire(raw) ?: continue
            val (timeStr, taskId, message) = m.destructured
            val timestamp = try {
                val time = java.time.LocalTime.parse(timeStr)
                today.atTime(time).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
            out.add(LogLine(timestamp, taskId.ifEmpty { null }, message))
        }
        return out
    }

    /** Matches `HH:mm:ss [taskId] message` or `HH:mm:ss message`. */
    private val LOG_LINE_PATTERN = Regex("^(\\d{2}:\\d{2}:\\d{2}) (?:\\[([^\\]]+)\\] )?(.*)$")

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readEntry(file: File, name: String): String? {
        ZipInputStream(file.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == name) return zis.readAllBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return null
    }

    private fun buildLogText(lines: List<LogLine>): String =
        lines.joinToString("\n") { it.format() }

    private fun buildTasksJson(tasks: List<AiTask>): String {
        val sb = StringBuilder()
        sb.append('[')
        tasks.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append('{')
                .append(field("id", t.id)).append(',')
                .append(field("file", t.file)).append(',')
                .append(field("prompt", t.prompt)).append(',')
                .append(field("state", t.state.name)).append(',')
                .append(numField("startedAt", t.startedAt)).append(',')
                .append(numField("finishedAt", t.finishedAt))
                .append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun parseTasksJson(json: String): List<AiTask> {
        val out = mutableListOf<AiTask>()
        val objects = OBJ_PATTERN.findAll(json)
        for (m in objects) {
            val body = m.value
            val id = extractStr(body, "id") ?: continue
            val file = extractStr(body, "file") ?: continue
            val prompt = extractStr(body, "prompt") ?: ""
            val stateName = extractStr(body, "state") ?: "PENDING"
            val rawState = runCatching { TaskState.valueOf(stateName) }.getOrDefault(TaskState.PENDING)
            // Normalize on import: only COMPLETED is preserved so "Run all" can skip those.
            // Anything else (RUNNING/STOPPED/FAILED/PENDING) becomes PENDING — the source
            // process that produced the original state is long gone, and the row's play
            // button must be usable immediately after import.
            val state = if (rawState == TaskState.COMPLETED) TaskState.COMPLETED else TaskState.PENDING
            val task = AiTask(id = id, file = file, prompt = prompt, state = state)
            // Keep timestamps only for completed tasks (so durations stay meaningful);
            // drop them for normalized-to-PENDING tasks to avoid misleading ETA math.
            if (state == TaskState.COMPLETED) {
                extractNum(body, "startedAt")?.let { task.startedAt = it }
                extractNum(body, "finishedAt")?.let { task.finishedAt = it }
            }
            out.add(task)
        }
        return out
    }

    private fun field(k: String, v: String): String = "\"$k\":\"${jsonEscape(v)}\""
    private fun numField(k: String, v: Long): String = "\"$k\":$v"

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    private fun extractStr(body: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(body) ?: return null
        return m.groupValues[1]
            .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun extractNum(body: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()

    private fun parseGlobs(globs: String): List<GlobMatcher> =
        globs.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { GlobMatcher(it) }
            .toList()

    private val OBJ_PATTERN = Regex("\\{[^{}]*\\}")
}

class GlobMatcher(pattern: String) {
    private val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern")
    fun matches(path: String): Boolean =
        runCatching { matcher.matches(java.nio.file.Paths.get(path)) }.getOrDefault(false)
}
