package com.aitaskmanager.action

import com.aitaskmanager.icons.AiTaskManagerIcons
import com.aitaskmanager.io.GlobMatcher
import com.aitaskmanager.log.LogService
import com.aitaskmanager.settings.AiTaskManagerSettings
import com.aitaskmanager.task.AiTask
import com.aitaskmanager.task.TaskRegistry
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.usages.UsageView
import com.intellij.usages.UsageViewManager

class SendFindResultsAction : AnAction(
    "Send to AI Task Manager",
    "Send all files from the current Find Results panel to AI Task Manager",
    AiTaskManagerIcons.TOOL_WINDOW,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // Enable when there is an open UsageView with results OR selected files in context
        val usageView = UsageViewManager.getInstance(project).getSelectedUsageView()
        val hasContent = usageView?.usages?.isNotEmpty() == true ||
                e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.isNotEmpty() == true ||
                e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = hasContent
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val paths = collectPaths(e, project)
        if (paths.isEmpty()) {
            LogService.getInstance().append("Send-to-AITM: no files found in Find Results")
            return
        }

        val excluded = AiTaskManagerSettings.getInstance().state.excludedGlobs
        val matchers = excluded.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { GlobMatcher(it) }
            .toList()

        val filtered = paths.filterNot { path -> matchers.any { it.matches(path) } }
        if (filtered.isEmpty()) {
            LogService.getInstance().append("Send-to-AITM: no files after exclusion filter")
            return
        }

        TaskRegistry.getInstance().addAll(filtered.map { AiTask(file = it) })
        val skipped = paths.size - filtered.size
        val msg = buildString {
            append("Send-to-AITM: imported ${filtered.size} file(s)")
            if (skipped > 0) append(" ($skipped excluded by glob filter)")
        }
        LogService.getInstance().append(msg)
        ToolWindowManager.getInstance(project).getToolWindow("AI Task Manager")?.activate(null)
    }

    /**
     * Prefers the active [UsageView] so we can honour per-node exclusion (checkbox state).
     * Falls back to the data-context file list only when no UsageView is reachable.
     */
    private fun collectPaths(e: AnActionEvent, project: Project): List<String> {
        val usageView = e.getData(UsageView.USAGE_VIEW_KEY)
            ?: UsageViewManager.getInstance(project).getSelectedUsageView()
        if (usageView != null) {
            val paths = FindResultsCollector.collectNonExcludedPaths(usageView)
            if (paths.isNotEmpty()) return paths
        }
        return collectFromContext(e)
    }

    private fun collectFromContext(e: AnActionEvent): List<String> {
        val arr = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!arr.isNullOrEmpty()) return arr.map { it.path }.distinct()
        val single = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (single != null) return listOf(single.path)
        val psiArr = e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)
        if (!psiArr.isNullOrEmpty()) return psiArr.mapNotNull { it.containingFile?.virtualFile?.path }.distinct()
        return emptyList()
    }
}
