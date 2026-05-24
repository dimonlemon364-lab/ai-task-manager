package com.aitaskmanager.task

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.CopyOnWriteArrayList

interface TaskRegistryListener {
    fun onTasksReplaced(tasks: List<AiTask>) {}
    fun onTaskAdded(task: AiTask) {}
    fun onTaskRemoved(task: AiTask) {}
    fun onTaskUpdated(task: AiTask) {}
    fun onCleared() {}
}

@Service(Service.Level.APP)
class TaskRegistry {
    private val tasks = mutableListOf<AiTask>()
    private val listeners = CopyOnWriteArrayList<TaskRegistryListener>()
    private val lock = Any()

    fun snapshot(): List<AiTask> = synchronized(lock) { tasks.toList() }

    fun replaceAll(newTasks: List<AiTask>) {
        synchronized(lock) {
            tasks.clear()
            tasks.addAll(newTasks)
        }
        fireOnEdt { l -> l.onTasksReplaced(snapshot()) }
    }

    fun addAll(newTasks: List<AiTask>) {
        val added = mutableListOf<AiTask>()
        synchronized(lock) {
            for (t in newTasks) {
                if (tasks.none { it.file == t.file }) {
                    tasks.add(t); added.add(t)
                }
            }
        }
        for (t in added) fireOnEdt { l -> l.onTaskAdded(t) }
    }

    fun remove(task: AiTask) {
        val removed = synchronized(lock) { tasks.remove(task) }
        if (removed) fireOnEdt { l -> l.onTaskRemoved(task) }
    }

    fun removeAll(toRemove: Collection<AiTask>) {
        val removed = mutableListOf<AiTask>()
        synchronized(lock) {
            for (t in toRemove) if (tasks.remove(t)) removed.add(t)
        }
        for (t in removed) fireOnEdt { l -> l.onTaskRemoved(t) }
    }

    fun clear() {
        synchronized(lock) { tasks.clear() }
        fireOnEdt { l -> l.onCleared() }
    }

    fun notifyUpdated(task: AiTask) {
        fireOnEdt { l -> l.onTaskUpdated(task) }
    }

    fun addListener(l: TaskRegistryListener) { listeners.add(l) }
    fun removeListener(l: TaskRegistryListener) { listeners.remove(l) }

    private fun fireOnEdt(action: (TaskRegistryListener) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            for (l in listeners) {
                try { action(l) } catch (_: Throwable) { /* ignore */ }
            }
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): TaskRegistry =
            ApplicationManager.getApplication().getService(TaskRegistry::class.java)
    }
}
