package com.aitaskmanager.ai

import com.aitaskmanager.task.AiTask

data class TestResult(val ok: Boolean, val explanation: String)

data class TaskResult(val ok: Boolean, val output: String, val error: String? = null)

interface AiProvider {
    fun searchFiles(prompt: String, onLog: (String) -> Unit, isCancelled: () -> Boolean): List<String>
    fun executeTask(task: AiTask, onLog: (String) -> Unit, isCancelled: () -> Boolean): TaskResult
    fun test(onLog: (String) -> Unit): TestResult
}
