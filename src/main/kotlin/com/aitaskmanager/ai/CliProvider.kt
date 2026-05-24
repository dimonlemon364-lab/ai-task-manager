package com.aitaskmanager.ai

import com.aitaskmanager.settings.AiTaskManagerSettings
import com.aitaskmanager.settings.ProviderConfig
import com.aitaskmanager.task.AiTask
import com.intellij.openapi.project.ProjectManager
import java.io.File

abstract class CliProvider(private val config: ProviderConfig, private val displayName: String) : AiProvider {

    /**
     * Returns the base directory of the currently open project so the binary
     * runs there instead of in the JVM default (which can be a Gradle cache).
     */
    private fun resolveProjectDir(): File? =
        ProjectManager.getInstance().openProjects
            .firstOrNull()
            ?.basePath
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

    override fun searchFiles(prompt: String, onLog: (String) -> Unit, isCancelled: () -> Boolean): List<String> {
        val projectDir = resolveProjectDir()
        val cmd = ProcessRunner.buildCommand(
            config.searchCommandTemplate,
            mapOf(
                "binary" to config.binaryPath,
                "prompt" to prompt,
                "projectDir" to (projectDir?.absolutePath ?: ""),
            )
        )
        onLog("[$displayName] cwd: ${projectDir ?: "default"}")
        onLog("[$displayName] $ ${ProcessRunner.quoteForDisplay(cmd)}")
        val result = ProcessRunner.run(cmd, onLog, isCancelled, workDir = projectDir)
        if (result.exitCode != 0) {
            onLog("[$displayName] exit code ${result.exitCode}")
            return emptyList()
        }
        val regex = Regex(AiTaskManagerSettings.getInstance().state.filePathRegex, RegexOption.MULTILINE)
        return regex.findAll(result.stdout)
            .map { it.groupValues.getOrNull(1) ?: it.value }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    override fun executeTask(task: AiTask, onLog: (String) -> Unit, isCancelled: () -> Boolean): TaskResult {
        val projectDir = resolveProjectDir()
        val cmd = ProcessRunner.buildCommand(
            config.taskCommandTemplate,
            mapOf(
                "binary" to config.binaryPath,
                "prompt" to task.prompt,
                "file" to task.file,
                "projectDir" to (projectDir?.absolutePath ?: ""),
            )
        )
        onLog("[$displayName] cwd: ${projectDir ?: "default"}")
        onLog("[$displayName] $ ${ProcessRunner.quoteForDisplay(cmd)}")
        val result = ProcessRunner.run(cmd, onLog, isCancelled, workDir = projectDir)
        return TaskResult(
            ok = result.exitCode == 0,
            output = result.stdout,
            error = if (result.exitCode == 0) null else result.stderr,
        )
    }

    override fun test(onLog: (String) -> Unit): TestResult {
        if (config.binaryPath.isBlank()) {
            return TestResult(false, "Binary path is empty. Configure it in the Configuration tab.")
        }
        return try {
            val probe = ProcessBuilder(config.binaryPath, "--version").redirectErrorStream(true).start()
            val out = probe.inputStream.bufferedReader().readText()
            val finished = probe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                probe.destroyForcibly()
                TestResult(false, "Binary did not respond to --version within 5s.")
            } else if (probe.exitValue() != 0) {
                TestResult(false, "`${config.binaryPath} --version` exited with ${probe.exitValue()}.\nOutput:\n$out")
            } else {
                onLog("[$displayName] $out")
                TestResult(true, "OK. Detected:\n${out.trim()}")
            }
        } catch (e: Throwable) {
            TestResult(false, "Failed to launch ${config.binaryPath}: ${e.message}")
        }
    }
}

class CopilotCliProvider(config: ProviderConfig) : CliProvider(config, "copilot")
class ClaudeCliProvider(config: ProviderConfig) : CliProvider(config, "claude")
