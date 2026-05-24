package com.aitaskmanager.task

import com.aitaskmanager.ai.AiProviderFactory
import com.aitaskmanager.log.LogService
import com.aitaskmanager.settings.AiTaskManagerSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class TaskExecutorService {
    private val log: LogService get() = LogService.getInstance()
    private val registry: TaskRegistry get() = TaskRegistry.getInstance()

    private var executor: ExecutorService = newExecutor()
    private var poolSize: Int = currentMaxThreads()

    private val futures = ConcurrentHashMap<String, Future<*>>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    @Synchronized
    private fun ensureCapacity() {
        val want = currentMaxThreads()
        if (want != poolSize) {
            executor.shutdown()
            executor = Executors.newFixedThreadPool(want.coerceAtLeast(1)) { r ->
                Thread(r, "aitm-worker").apply { isDaemon = true }
            }
            poolSize = want
        }
    }

    private fun currentMaxThreads(): Int =
        AiTaskManagerSettings.getInstance().state.maxThreads.coerceIn(1, 64)

    private fun newExecutor(): ExecutorService =
        Executors.newFixedThreadPool(currentMaxThreads()) { r ->
            Thread(r, "aitm-worker").apply { isDaemon = true }
        }

    fun isRunning(task: AiTask): Boolean = futures[task.id]?.let { !it.isDone } ?: false

    fun anyRunning(): Boolean = futures.values.any { !it.isDone }

    fun runSingle(task: AiTask) {
        ensureCapacity()
        submit(task)
    }

    fun runAll(tasks: List<AiTask>) {
        ensureCapacity()
        tasks.filter { it.state != TaskState.COMPLETED && !isRunning(it) }
            .forEach { submit(it) }
    }

    fun stop(task: AiTask) {
        cancelFlags[task.id]?.set(true)
        futures[task.id]?.cancel(true)
    }

    fun stopAll() {
        cancelFlags.values.forEach { it.set(true) }
        futures.values.forEach { it.cancel(true) }
    }

    private fun submit(task: AiTask) {
        val cancel = AtomicBoolean(false)
        cancelFlags[task.id] = cancel
        task.state = TaskState.PENDING
        registry.notifyUpdated(task)

        val future = executor.submit {
            task.state = TaskState.RUNNING
            task.startedAt = System.currentTimeMillis()
            task.lastError = null
            registry.notifyUpdated(task)
            log.append("Started task for ${task.file}", task.id)

            try {
                val provider = AiProviderFactory.get()
                val effectivePrompt = if (task.prompt.isBlank())
                    "Investigate this file in depth and propose precise changes." else task.prompt
                val taskRun = AiTask(
                    id = task.id, file = task.file, prompt = "$effectivePrompt\n\nTarget file: ${task.file}",
                )
                val result = provider.executeTask(taskRun, { line -> log.append(line, task.id) }, { cancel.get() })

                task.finishedAt = System.currentTimeMillis()
                when {
                    cancel.get() -> {
                        task.state = TaskState.STOPPED
                        log.append("Stopped by user", task.id)
                    }
                    result.ok -> {
                        task.state = TaskState.COMPLETED
                        log.append("Completed in ${task.durationMs} ms", task.id)
                    }
                    else -> {
                        task.state = TaskState.FAILED
                        task.lastError = result.error
                        log.append("Failed: ${result.error}", task.id)
                    }
                }
            } catch (e: InterruptedException) {
                task.state = TaskState.STOPPED
                task.finishedAt = System.currentTimeMillis()
                log.append("Interrupted", task.id)
            } catch (e: Throwable) {
                task.state = TaskState.FAILED
                task.finishedAt = System.currentTimeMillis()
                task.lastError = e.message
                log.append("Error: ${e.message}", task.id)
            } finally {
                futures.remove(task.id)
                cancelFlags.remove(task.id)
                registry.notifyUpdated(task)
            }
        }
        futures[task.id] = future
    }

    companion object {
        @JvmStatic
        fun getInstance(): TaskExecutorService =
            ApplicationManager.getApplication().getService(TaskExecutorService::class.java)
    }
}
