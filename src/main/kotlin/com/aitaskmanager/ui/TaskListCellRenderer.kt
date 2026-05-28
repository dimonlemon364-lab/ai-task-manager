package com.aitaskmanager.ui

import com.aitaskmanager.icons.AiTaskManagerIcons
import com.aitaskmanager.task.AiTask
import com.aitaskmanager.task.TaskState
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class TaskListCellRenderer : JPanel(BorderLayout()), ListCellRenderer<AiTask> {

    private val stateLabel = JBLabel()
    private val pathLabel = JBLabel()
    private val reviewLogLabel = JBLabel().apply {
        toolTipText = "Review log (open Log tab filtered by this task)"
    }
    private val actionLabel = JBLabel()

    // All icons pinned LEFT so they stay visible even when the path is long.
    private val leftStack = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
        isOpaque = false
        add(actionLabel)
        add(reviewLogLabel)
        add(stateLabel)
    }

    init {
        border = JBUI.Borders.empty(4, 8)
        add(leftStack, BorderLayout.WEST)
        add(pathLabel, BorderLayout.CENTER)
        isOpaque = true
    }

    override fun getListCellRendererComponent(
        list: JList<out AiTask>,
        value: AiTask,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        pathLabel.text = value.file
        stateLabel.icon = stateIcon(value.state)
        stateLabel.toolTipText = value.state.name
        actionLabel.icon = actionIcon(value.state)
        actionLabel.toolTipText = actionTooltip(value.state)
        reviewLogLabel.icon = AllIcons.Actions.Show

        if (isSelected) {
            background = list.selectionBackground
            pathLabel.foreground = list.selectionForeground
        } else {
            background = list.background
            pathLabel.foreground = list.foreground
        }
        return this
    }

    private fun stateIcon(state: TaskState): Icon = when (state) {
        TaskState.PENDING -> AllIcons.RunConfigurations.TestNotRan
        TaskState.RUNNING -> AllIcons.RunConfigurations.TestState.Run
        TaskState.COMPLETED -> AllIcons.RunConfigurations.TestPassed
        TaskState.STOPPED -> AllIcons.RunConfigurations.TestPaused
        TaskState.FAILED -> AllIcons.RunConfigurations.TestFailed
    }

    private fun actionIcon(state: TaskState): Icon = when (state) {
        TaskState.RUNNING -> AiTaskManagerIcons.STOP
        TaskState.COMPLETED -> AiTaskManagerIcons.RECYCLE
        else -> AiTaskManagerIcons.PLAY
    }

    private fun actionTooltip(state: TaskState): String = when (state) {
        TaskState.RUNNING -> "Stop task"
        TaskState.COMPLETED -> "Re-run task (skipped by 'Run all')"
        else -> "Run task"
    }

    companion object {
        /** Width (px) of the rightmost icon (play/stop/recycle). Used by the row mouse handler. */
        const val ACTION_AREA_WIDTH = 28

        /** Width (px) of the second icon to the left of the action icon (review log). */
        const val REVIEW_LOG_AREA_WIDTH = 28
    }
}
