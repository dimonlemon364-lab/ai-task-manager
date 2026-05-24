package com.aitaskmanager.ai

import com.aitaskmanager.settings.AiProviderKind
import com.aitaskmanager.settings.AiTaskManagerSettings

object AiProviderFactory {
    fun get(kind: AiProviderKind = AiTaskManagerSettings.getInstance().state.activeProvider): AiProvider {
        val settings = AiTaskManagerSettings.getInstance()
        val cfg = settings.providerConfig(kind)
        return when (kind) {
            AiProviderKind.COPILOT -> CopilotCliProvider(cfg)
            AiProviderKind.CLAUDE_CLI -> ClaudeCliProvider(cfg)
            AiProviderKind.CLAUDE_API -> ClaudeApiProvider(cfg)
        }
    }
}
