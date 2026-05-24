package com.aitaskmanager.ui

import com.aitaskmanager.log.LogLine
import com.aitaskmanager.log.LogListener
import com.aitaskmanager.log.LogService
import com.aitaskmanager.task.AiTask
import com.aitaskmanager.task.TaskRegistry
import com.aitaskmanager.task.TaskRegistryListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class LogTab : JPanel(BorderLayout()), LogListener {

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
    }
    private val scroll = JBScrollPane(textArea)
    private var autoScroll = true

    private val filterModel = DefaultComboBoxModel<FilterItem>()
    private val filterCombo: ComboBox<FilterItem> = ComboBox(filterModel).apply {
        addActionListener {
            // Selecting a new filter rebuilds the text area from the filtered snapshot.
            redrawAll()
        }
    }

    // Declared before `init` so it's already constructed when we add it as a listener below.
    private val registryListener = object : TaskRegistryListener {
        override fun onTasksReplaced(tasks: List<AiTask>) { SwingUtilities.invokeLater { rebuildFilterItems() } }
        override fun onTaskAdded(task: AiTask) { SwingUtilities.invokeLater { rebuildFilterItems() } }
        override fun onTaskRemoved(task: AiTask) { SwingUtilities.invokeLater { rebuildFilterItems() } }
        override fun onTaskUpdated(task: AiTask) { /* state changes don't affect filter items */ }
        override fun onCleared() { SwingUtilities.invokeLater { rebuildFilterItems() } }
    }

    init {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Clear", "Clear log", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                LogService.getInstance().clear()
            }
        })
        group.add(object : ToggleAction("Auto-scroll to bottom", "Auto-scroll", AllIcons.RunConfigurations.Scroll_down) {
            override fun isSelected(e: AnActionEvent): Boolean = autoScroll
            override fun setSelected(e: AnActionEvent, state: Boolean) { autoScroll = state }
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("AiTaskManagerLogToolbar", group, true)
        toolbar.targetComponent = this
        val toolbarComponent: JComponent = toolbar.component

        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel("Filter:"))
            add(filterCombo)
        }
        val header = JPanel(BorderLayout()).apply {
            add(toolbarComponent, BorderLayout.NORTH)
            add(filterRow, BorderLayout.SOUTH)
        }

        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        rebuildFilterItems()
        redrawAll()

        LogService.getInstance().addListener(this)
        TaskRegistry.getInstance().addListener(registryListener)
    }

    // ----- External entry point used by the SearchTab "review log" icon -----

    /** Switch the dropdown selection to [task]; if [task] is null, select "All". */
    fun filterByTask(task: AiTask?) {
        SwingUtilities.invokeLater {
            val target = task?.id
            // Ensure the item exists (task may have just been added)
            rebuildFilterItems(preserve = target)
            val itemCount = filterModel.size
            for (i in 0 until itemCount) {
                val item = filterModel.getElementAt(i)
                if (item.taskId == target) {
                    filterCombo.selectedIndex = i
                    return@invokeLater
                }
            }
            // Fall back to "All" if the task wasn't found
            filterCombo.selectedIndex = 0
        }
    }

    // ----- LogListener -----

    override fun onLogLine(line: LogLine) {
        if (!matchesFilter(line)) return
        textArea.append(line.format() + "\n")
        if (autoScroll) textArea.caretPosition = textArea.document.length
    }

    override fun onCleared() {
        SwingUtilities.invokeLater { textArea.text = "" }
    }

    // ----- internals -----

    private fun matchesFilter(line: LogLine): Boolean {
        val sel = filterCombo.selectedItem as? FilterItem ?: return true
        return sel.taskId == null || sel.taskId == line.taskId
    }

    private fun redrawAll() {
        textArea.text = ""
        val snapshot = LogService.getInstance().snapshot()
        val filtered = snapshot.filter { matchesFilter(it) }
        for (line in filtered) {
            textArea.append(line.format() + "\n")
        }
        if (autoScroll) textArea.caretPosition = textArea.document.length
    }

    /**
     * Rebuild the filter dropdown items: ["All", task1, task2, …].
     * Tries to keep the current selection ([preserve] task id, or whatever the combo
     * already has). If the selected task no longer exists, falls back to "All".
     */
    private fun rebuildFilterItems(preserve: String? = null) {
        val current = preserve ?: (filterCombo.selectedItem as? FilterItem)?.taskId
        val tasks = TaskRegistry.getInstance().snapshot()
        filterModel.removeAllElements()
        filterModel.addElement(FilterItem(null, "All"))
        for (t in tasks) filterModel.addElement(FilterItem(t.id, displayName(t)))

        // Restore selection if possible
        val itemCount = filterModel.size
        var restored = false
        if (current != null) {
            for (i in 0 until itemCount) {
                if (filterModel.getElementAt(i).taskId == current) {
                    filterCombo.selectedIndex = i
                    restored = true
                    break
                }
            }
        }
        if (!restored) filterCombo.selectedIndex = 0
    }

    private fun displayName(t: AiTask): String {
        val fileName = t.file.substringAfterLast('/').ifEmpty { t.file }
        return "$fileName  [${t.id.take(8)}]"
    }

    fun dispose() {
        LogService.getInstance().removeListener(this)
        TaskRegistry.getInstance().removeListener(registryListener)
    }

    /** A row in the filter dropdown. `taskId == null` means "All". */
    private data class FilterItem(val taskId: String?, val display: String) {
        override fun toString(): String = display
    }
}
