package com.aitaskmanager.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane

class AiTaskManagerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabs = JBTabbedPane()
        val logTab = LogTab()
        val searchTab = SearchTab(project) { task ->
            // Click on the row's "review log" icon → switch to Log tab + filter by task
            tabs.selectedIndex = LOG_TAB_INDEX
            logTab.filterByTask(task)
        }
        val configTab = ConfigurationTab()
        tabs.addTab("Search", searchTab)
        tabs.addTab("Log", logTab)
        tabs.addTab("Configuration", configTab)

        val contentFactory = com.intellij.ui.content.ContentFactory.getInstance()
        val content = contentFactory.createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private companion object {
        const val LOG_TAB_INDEX = 1
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
