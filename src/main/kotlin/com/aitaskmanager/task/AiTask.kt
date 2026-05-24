package com.aitaskmanager.task

import java.util.UUID

enum class TaskState { PENDING, RUNNING, COMPLETED, STOPPED, FAILED }

class AiTask(
    val id: String = UUID.randomUUID().toString(),
    var file: String,
    var prompt: String = "",
    @Volatile var state: TaskState = TaskState.PENDING,
    @Volatile var startedAt: Long = 0L,
    @Volatile var finishedAt: Long = 0L,
    @Volatile var lastError: String? = null,
) {
    val durationMs: Long
        get() = if (finishedAt > startedAt) finishedAt - startedAt else 0L
}
