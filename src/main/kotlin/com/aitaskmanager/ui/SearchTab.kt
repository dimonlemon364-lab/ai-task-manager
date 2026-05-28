package com.aitaskmanager.ui

import com.aitaskmanager.ai.AiProviderFactory
import com.aitaskmanager.io.ExportImport
import com.aitaskmanager.log.LogService
import com.aitaskmanager.settings.AiTaskManagerSettings
import com.aitaskmanager.task.AiTask
import com.aitaskmanager.task.TaskExecutorService
import com.aitaskmanager.task.TaskRegistry
import com.aitaskmanager.task.TaskRegistryListener
import com.aitaskmanager.task.TaskState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.usages.UsageViewManager
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

class SearchTab(
    private val project: Project,
    private val onReviewLog: (AiTask) -> Unit = {},
) : JPanel(BorderLayout()), TaskRegistryListener {

    private val registry: TaskRegistry get() = TaskRegistry.getInstance()
    private val executor: TaskExecutorService get() = TaskExecutorService.getInstance()
    private val log: LogService get() = LogService.getInstance()

    private val searchField = JBTextField(30).apply { emptyText.text = "Enter search prompt…" }
    private val spinner = AsyncProcessIcon("aitm-search").apply { isVisible = false }
    private val progressBar = JProgressBar(0, 100).apply { isStringPainted = true; string = "0 / 0" }
    private val etaLabel = JLabel("ETA: --")

    private val listModel = DefaultListModel<AiTask>()
    private val list = JBList(listModel).apply {
        cellRenderer = TaskListCellRenderer()
        fixedCellHeight = 24
        emptyText.text = "Drop a .txt file to import paths, or use Search above"
    }
    private val scrollPane = JBScrollPane(list)

    private val searchCancel = AtomicBoolean(false)
    @Volatile private var searchRunning = false

    init {
        border = JBUI.Borders.empty(4)
        add(buildTopBar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buildProgressBar(), BorderLayout.SOUTH)

        installSearchFieldBindings()
        installListMouseHandler()
        installListKeyHandler()
        installDropHandler()

        registry.addListener(this)
        refreshFromRegistry()
    }

    private fun buildTopBar(): JComponent {
        val top = JPanel(BorderLayout(JBUI.scale(4), 0))
        top.add(searchField, BorderLayout.CENTER)
        top.add(spinner, BorderLayout.EAST)

        val group = DefaultActionGroup()
        group.add(action("Search", "Start AI search", AllIcons.Actions.Find) { triggerSearch() })
        group.add(action("Stop", "Stop running search & tasks", AllIcons.Actions.Suspend, isEnabled = { searchRunning || executor.anyRunning() }) { stopEverything() })
        group.addSeparator()
        group.add(action("Run all", "Run all pending tasks (skips completed)", AllIcons.Actions.Execute) { runAll() })
        group.add(action("Remove selected", "Remove selected tasks", AllIcons.General.Remove) { removeSelected() })
        group.add(action("Clean all", "Remove every task and clear log", AllIcons.Actions.GC) { cleanAll() })
        group.addSeparator()
        group.add(action("Export…", "Export tasks + logs to ZIP", AllIcons.ToolbarDecorator.Export) { exportZip() })
        group.add(action("Import…", "Import tasks from ZIP", AllIcons.ToolbarDecorator.Import) { importZip() })
        group.add(action(
            "Import from Find Results",
            "Import all files from the current Find in Files / Find Usages results panel",
            AllIcons.Actions.Download,
            isEnabled = { UsageViewManager.getInstance(project).getSelectedUsageView()?.usages?.isNotEmpty() == true }
        ) { importFromFindResults() })

        val toolbar = ActionManager.getInstance().createActionToolbar("AiTaskManagerSearchToolbar", group, true)
        toolbar.targetComponent = this

        val wrapper = JPanel(BorderLayout())
        wrapper.add(toolbar.component, BorderLayout.NORTH)
        wrapper.add(top, BorderLayout.CENTER)
        return wrapper
    }

    private fun buildProgressBar(): JComponent {
        val p = JPanel(BorderLayout(JBUI.scale(8), 0))
        p.add(progressBar, BorderLayout.CENTER)
        p.add(etaLabel, BorderLayout.EAST)
        p.border = JBUI.Borders.empty(4, 0)
        return p
    }

    private fun installSearchFieldBindings() {
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    triggerSearch()
                }
            }
        })
    }

    private fun installListMouseHandler() {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx < 0) return
                val cellBounds = list.getCellBounds(idx, idx) ?: return
                val task = listModel.elementAt(idx)

                val xFromLeft = e.point.x - cellBounds.x
                val iconAreaWidth = TaskListCellRenderer.ACTION_AREA_WIDTH + TaskListCellRenderer.REVIEW_LOG_AREA_WIDTH

                // Double-click outside the icon area opens the file.
                if (e.clickCount == 2 && xFromLeft >= iconAreaWidth) {
                    openFile(task)
                    e.consume()
                    return
                }

                if (e.clickCount != 1) return

                // Both icons are pinned to the left.
                when {
                    xFromLeft < TaskListCellRenderer.ACTION_AREA_WIDTH -> {
                        handleRowAction(task)
                        e.consume()
                    }
                    xFromLeft < iconAreaWidth -> {
                        onReviewLog(task)
                        e.consume()
                    }
                }
            }
        })
    }

    private fun openFile(task: AiTask) {
        val lfs = LocalFileSystem.getInstance()
        val vf = lfs.findFileByPath(task.file)
            ?: project.basePath?.let { lfs.findFileByPath("$it/${task.file}") }
        if (vf != null && vf.isValid) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        } else {
            log.append("Cannot open file: ${task.file}")
        }
    }

    private fun installListKeyHandler() {
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
                        removeSelected()
                        e.consume()
                    }
                    KeyEvent.VK_SPACE -> {
                        val task = list.selectedValue ?: return
                        handleRowAction(task)
                        e.consume()
                    }
                }
            }
        })
    }

    private fun handleRowAction(task: AiTask) {
        when (task.state) {
            TaskState.RUNNING -> executor.stop(task)
            TaskState.COMPLETED -> {
                task.state = TaskState.PENDING
                registry.notifyUpdated(task)
                executor.runSingle(task)
            }
            else -> executor.runSingle(task)
        }
    }

    private fun triggerSearch() {
        val prompt = searchField.text?.trim().orEmpty()
        if (prompt.isEmpty()) return
        if (searchRunning) return

        searchRunning = true
        searchCancel.set(false)
        spinner.isVisible = true
        spinner.resume()
        log.append("Search: $prompt")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val provider = AiProviderFactory.get()
                val files = provider.searchFiles(prompt, { line -> log.append(line) }, { searchCancel.get() })
                val excluded = parseExcluded()
                val filtered = files.filter { p -> excluded.none { it.matches(p) } }

                ApplicationManager.getApplication().invokeLater({
                    registry.addAll(filtered.map { AiTask(file = it, prompt = prompt) })
                    log.append("Search produced ${filtered.size} file(s): ${filtered.joinToString(", ").take(500)}")
                }, ModalityState.any())
            } catch (e: Throwable) {
                log.append("Search failed: ${e.message}")
            } finally {
                ApplicationManager.getApplication().invokeLater({
                    spinner.suspend()
                    spinner.isVisible = false
                    searchRunning = false
                }, ModalityState.any())
            }
        }
    }

    private fun parseExcluded(): List<com.aitaskmanager.io.GlobMatcher> {
        val txt = AiTaskManagerSettings.getInstance().state.excludedGlobs
        return txt.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { com.aitaskmanager.io.GlobMatcher(it) }
            .toList()
    }

    private fun stopEverything() {
        searchCancel.set(true)
        executor.stopAll()
        log.append("Stop requested")
    }

    private fun runAll() {
        executor.runAll(registry.snapshot())
    }

    private fun removeSelected() {
        val sel = list.selectedValuesList ?: return
        if (sel.isEmpty()) return
        sel.forEach { if (executor.isRunning(it)) executor.stop(it) }
        registry.removeAll(sel)
    }

    private fun cleanAll() {
        executor.stopAll()
        registry.clear()
        log.clear()
        log.append("Cleaned all tasks")
    }

    private fun exportZip() {
        val descriptor = FileSaverDescriptor("Export AI Task Manager Data", "Saves tasks and logs to a ZIP", "zip")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val target: VirtualFileWrapper = dialog.save("aitaskmanager-export.zip") ?: return
        val file = target.file
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ExportImport.export(file)
                log.append("Exported to ${file.absolutePath}")
            } catch (e: Throwable) {
                log.append("Export failed: ${e.message}")
            }
        }
    }

    private fun importZip() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("zip")
            .withTitle("Import AI Task Manager Data")
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val files = chooser.choose(project)
        val vf = files.firstOrNull() ?: return
        val source = File(vf.path)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val tasks = ExportImport.import(source, AiTaskManagerSettings.getInstance().state.excludedGlobs)
                ApplicationManager.getApplication().invokeLater({
                    registry.addAll(tasks)
                    log.append("Imported ${tasks.size} task(s) from ${source.name}")
                }, ModalityState.any())
            } catch (e: Throwable) {
                log.append("Import failed: ${e.message}")
            }
        }
    }

    private fun refreshFromRegistry() {
        listModel.clear()
        for (t in registry.snapshot()) listModel.addElement(t)
        updateProgress()
    }

    private fun updateProgress() {
        val tasks = (0 until listModel.size()).map { listModel.elementAt(it) }
        val total = tasks.size
        val done = tasks.count { it.state == TaskState.COMPLETED }
        val running = tasks.count { it.state == TaskState.RUNNING }

        progressBar.maximum = if (total == 0) 1 else total
        progressBar.value = done
        progressBar.string = "$done / $total"

        val completed = tasks.filter { it.state == TaskState.COMPLETED && it.durationMs > 0 }
        val avg = if (completed.isNotEmpty()) completed.sumOf { it.durationMs } / completed.size else 0L
        val remaining = total - done
        val etaMs = if (running > 0 && avg > 0) avg * remaining / running else if (avg > 0) avg * remaining else 0
        etaLabel.text = "ETA: " + if (etaMs > 0) formatDuration(etaMs) else "--"
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    private fun action(text: String, description: String, icon: javax.swing.Icon, isEnabled: () -> Boolean = { true }, onPerform: () -> Unit): DumbAwareAction =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = onPerform()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = isEnabled() }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    // ----- TaskRegistryListener -----

    override fun onTasksReplaced(tasks: List<AiTask>) {
        SwingUtilities.invokeLater { refreshFromRegistry() }
    }

    override fun onTaskAdded(task: AiTask) {
        SwingUtilities.invokeLater {
            listModel.addElement(task)
            updateProgress()
        }
    }

    override fun onTaskRemoved(task: AiTask) {
        SwingUtilities.invokeLater {
            listModel.removeElement(task)
            updateProgress()
        }
    }

    override fun onTaskUpdated(task: AiTask) {
        SwingUtilities.invokeLater {
            val idx = (0 until listModel.size()).firstOrNull { listModel.elementAt(it).id == task.id }
            if (idx != null) {
                listModel.set(idx, task)
            }
            updateProgress()
        }
    }

    override fun onCleared() {
        SwingUtilities.invokeLater {
            listModel.clear()
            updateProgress()
        }
    }

    // ----- Find Results import -----

    /** Called from the toolbar button and from [com.aitaskmanager.action.SendFindResultsAction]. */
    fun appendImportedFiles(paths: List<String>) {
        if (paths.isEmpty()) {
            log.append("Import from Find Results: no files found")
            return
        }
        val excluded = parseExcluded()
        val filtered = paths.filterNot { p -> excluded.any { it.matches(p) } }
        registry.addAll(filtered.map { AiTask(file = it) })
        val skipped = paths.size - filtered.size
        val msg = buildString {
            append("Imported ${filtered.size} file(s) from Find Results")
            if (skipped > 0) append(" ($skipped excluded by glob filter)")
        }
        log.append(msg)
    }

    private fun importFromFindResults() {
        val usageView = UsageViewManager.getInstance(project).getSelectedUsageView() ?: run {
            log.append("Import from Find Results: no open Find Results panel")
            return
        }
        val paths = com.aitaskmanager.action.FindResultsCollector.collectNonExcludedPaths(usageView)
        appendImportedFiles(paths)
    }

    private fun installDropHandler() {
        val handler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                @Suppress("UNCHECKED_CAST")
                val files = try {
                    support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                } catch (_: Exception) {
                    return false
                }
                val txtFiles = files.filter { it.extension.lowercase() == "txt" }
                if (txtFiles.isEmpty()) return false
                val paths = txtFiles.flatMap { f ->
                    f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                }
                if (paths.isEmpty()) return false
                val prompt = searchField.text?.trim().orEmpty()
                val excluded = parseExcluded()
                val filtered = paths.filterNot { p -> excluded.any { it.matches(p) } }
                ApplicationManager.getApplication().invokeLater({
                    registry.addAll(filtered.map { AiTask(file = it, prompt = prompt) })
                    log.append("Dropped ${txtFiles.size} .txt file(s): imported ${filtered.size} path(s)")
                }, ModalityState.any())
                return true
            }
        }
        list.transferHandler = handler
        scrollPane.transferHandler = handler
    }

    fun dispose() {
        registry.removeListener(this)
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private fun unused(d: Dimension) {}
    }
}
