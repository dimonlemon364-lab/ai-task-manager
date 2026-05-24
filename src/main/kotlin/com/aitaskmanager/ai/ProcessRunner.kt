package com.aitaskmanager.ai

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object ProcessRunner {
    /**
     * Build a command line by substituting {binary}, {prompt}, {file} into a template.
     * Splits respecting double-quoted segments. Substitutions happen AFTER splitting so quoted
     * arguments containing whitespace are preserved correctly.
     */
    fun buildCommand(template: String, substitutions: Map<String, String>): List<String> {
        val tokens = tokenize(template)
        return tokens.map { tok -> substitutions.entries.fold(tok) { acc, e -> acc.replace("{${e.key}}", e.value) } }
    }

    /**
     * Render a parsed command list back into a shell-ish line for logging.
     * Tokens containing whitespace (or empty tokens) are wrapped in double quotes;
     * embedded `"` is back-slash escaped. The result is for display only — it is NOT
     * re-parseable into the original argv by a shell, but it matches what the user
     * typed in the template.
     */
    fun quoteForDisplay(cmd: List<String>): String =
        cmd.joinToString(" ") { tok ->
            if (tok.isEmpty() || tok.any { it.isWhitespace() }) {
                "\"" + tok.replace("\"", "\\\"") + "\""
            } else tok
        }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> inQuote = !inQuote
                c == '\\' && i + 1 < s.length -> { sb.append(s[i + 1]); i++ }
                c.isWhitespace() && !inQuote -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Run a command, streaming stdout/stderr to onLog as they arrive.
     * Polls isCancelled - on cancel, kills the process tree.
     */
    fun run(
        command: List<String>,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
        timeoutSeconds: Long = 600,
        processHolder: ((Process) -> Unit)? = null,
        workDir: File? = null,
    ): RunResult {
        if (command.isEmpty()) throw IllegalArgumentException("Empty command")

        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(false)
        if (workDir != null && workDir.isDirectory) {
            pb.directory(workDir)
        }
        val process = pb.start()
        processHolder?.invoke(process)

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val stdoutThread = Thread({
            BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    stdoutBuf.append(line).append('\n')
                    onLog(line)
                    line = r.readLine()
                }
            }
        }, "aitm-stdout").apply { isDaemon = true; start() }

        val stderrThread = Thread({
            BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    stderrBuf.append(line).append('\n')
                    onLog("[stderr] $line")
                    line = r.readLine()
                }
            }
        }, "aitm-stderr").apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (process.isAlive) {
            if (isCancelled()) {
                killTree(process)
                stdoutThread.join(500)
                stderrThread.join(500)
                return RunResult(-1, stdoutBuf.toString(), stderrBuf.toString() + "\n[cancelled]")
            }
            if (System.currentTimeMillis() > deadline) {
                killTree(process)
                return RunResult(-1, stdoutBuf.toString(), stderrBuf.toString() + "\n[timeout]")
            }
            process.waitFor(200, TimeUnit.MILLISECONDS)
        }
        stdoutThread.join(1000)
        stderrThread.join(1000)
        return RunResult(process.exitValue(), stdoutBuf.toString(), stderrBuf.toString())
    }

    private fun killTree(p: Process) {
        try {
            p.descendants().forEach { runCatching { it.destroyForcibly() } }
            p.destroyForcibly()
        } catch (_: Throwable) { /* ignore */ }
    }
}
