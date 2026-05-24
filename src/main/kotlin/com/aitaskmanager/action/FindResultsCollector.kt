package com.aitaskmanager.action

import com.aitaskmanager.log.LogService
import com.intellij.openapi.application.runReadAction
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageView
import com.intellij.usages.impl.UsageNode
import com.intellij.usages.impl.UsageViewImpl
import com.intellij.usages.rules.UsageInFile
import javax.swing.tree.TreeNode

/**
 * Collects file paths from an IntelliJ [UsageView], skipping any entry the user has
 * excluded (unticked / struck-through) in the Find Results panel.
 *
 * Exclusion check combines BOTH signals so we catch the state whichever the IDE updates:
 *   1. Tree traversal over [UsageViewImpl.getRoot] checking [UsageNode.isExcluded]
 *      (same path IntelliJ's own "Replace in Files" uses).
 *   2. The [UsageView.getExcludedUsages] set as a second filter.
 *
 * Path extraction is similarly defensive: tries [UsageInfo2UsageAdapter]'s PSI path first,
 * then falls back to [UsageInFile.getFile] for usage types that don't go through PSI.
 */
object FindResultsCollector {

    fun collectNonExcludedPaths(usageView: UsageView): List<String> {
        val log = LogService.getInstance()
        val paths = runReadAction {
            val tickedUsages = collectTickedUsages(usageView)
            log.append("FindResultsCollector: view=${usageView.javaClass.simpleName} total=${usageView.usages.size} excludedSet=${usageView.excludedUsages.size} ticked=${tickedUsages.size}")
            tickedUsages.mapNotNull(::extractPath).distinct()
        }
        log.append("FindResultsCollector: extracted ${paths.size} unique path(s) from ticked usages")
        return paths
    }

    private fun collectTickedUsages(usageView: UsageView): List<Usage> {
        val excludedSet = usageView.excludedUsages
        val excludedFromTree = HashSet<Usage>()
        val allFromTree = mutableListOf<Usage>()
        if (usageView is UsageViewImpl) {
            walkTree(usageView.root, allFromTree, excludedFromTree)
        }
        val candidates = allFromTree.ifEmpty { usageView.usages.toList() }
        return candidates.filterNot { it in excludedSet || it in excludedFromTree }
    }

    private fun walkTree(node: TreeNode, all: MutableList<Usage>, excluded: MutableSet<Usage>) {
        if (node is UsageNode) {
            val u = node.usage
            all.add(u)
            if (node.isExcluded()) excluded.add(u)
        }
        for (i in 0 until node.childCount) {
            walkTree(node.getChildAt(i), all, excluded)
        }
    }

    private fun extractPath(usage: Usage): String? {
        if (usage is UsageInfo2UsageAdapter) {
            val viaPsi = usage.element?.containingFile?.virtualFile?.path
            if (viaPsi != null) return viaPsi
        }
        return (usage as? UsageInFile)?.file?.path
    }
}
