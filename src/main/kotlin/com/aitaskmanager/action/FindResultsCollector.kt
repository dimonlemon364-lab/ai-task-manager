package com.aitaskmanager.action

import com.aitaskmanager.log.LogService
import com.intellij.openapi.application.runReadAction
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageView
import com.intellij.usages.rules.UsageInFile

/**
 * Collects file paths from an IntelliJ [UsageView], skipping any entry the user has
 * excluded (right-click → Exclude / Delete) in the Find Results panel.
 *
 * **Public-API only.** A previous version walked the UsageView tree via `UsageViewImpl`
 * and `UsageNode.isExcluded()` — both are `@ApiStatus.Internal`, which the JetBrains
 * Marketplace verifier rejects on upload (see
 * https://plugins.jetbrains.com/docs/intellij/api-internal.html). The current
 * implementation uses [UsageView.getExcludedUsages] only. As a consequence,
 * checkbox-style "unchecked" state in Find-in-Files results may not always populate
 * `excludedUsages` in every IDE build; if you need to send a strict subset, **select
 * the desired rows in the Find Results panel and right-click → Send to AI Task
 * Manager** — the action then uses the selection (via the data context) instead of the
 * full result list. See [SendFindResultsAction.collectPaths].
 *
 * Path extraction is defensive: tries [UsageInfo2UsageAdapter]'s PSI path first, then
 * falls back to [UsageInFile.getFile] for usage types that don't go through PSI.
 */
object FindResultsCollector {

    fun collectNonExcludedPaths(usageView: UsageView): List<String> {
        val log = LogService.getInstance()
        val paths = runReadAction {
            val excluded = usageView.excludedUsages
            val ticked = usageView.usages.filterNot { it in excluded }
            log.append(
                "FindResultsCollector: view=${usageView.javaClass.simpleName} " +
                    "total=${usageView.usages.size} excludedSet=${excluded.size} ticked=${ticked.size}"
            )
            ticked.mapNotNull(::extractPath).distinct()
        }
        log.append("FindResultsCollector: extracted ${paths.size} unique path(s)")
        return paths
    }

    private fun extractPath(usage: Usage): String? {
        if (usage is UsageInfo2UsageAdapter) {
            val viaPsi = usage.element?.containingFile?.virtualFile?.path
            if (viaPsi != null) return viaPsi
        }
        return (usage as? UsageInFile)?.file?.path
    }
}
