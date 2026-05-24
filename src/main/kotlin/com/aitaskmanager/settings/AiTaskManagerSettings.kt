package com.aitaskmanager.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

enum class AiProviderKind { COPILOT, CLAUDE_CLI, CLAUDE_API }

class ProviderConfig {
    var binaryPath: String = ""
    /** Template used by the initial file-search call. Supports {binary}, {prompt}, {projectDir}. */
    var searchCommandTemplate: String = ""
    /** Template used for per-file task execution. Supports {binary}, {prompt}, {file}, {projectDir}. */
    var taskCommandTemplate: String = ""
    var apiUrl: String = ""
    var model: String = ""
    // Tokens are stored via PasswordSafe, NOT here. This field is only the credential id.
    var credentialKey: String = ""

    /**
     * Fills in blank fields from [defaults]. Called after XML deserialization so that
     * fields absent from the saved XML (first run, or migration from an older schema)
     * still get sensible values.
     */
    fun applyMissingDefaults(defaults: ProviderConfig) {
        if (binaryPath.isBlank()) binaryPath = defaults.binaryPath
        if (searchCommandTemplate.isBlank()) searchCommandTemplate = defaults.searchCommandTemplate
        if (taskCommandTemplate.isBlank()) taskCommandTemplate = defaults.taskCommandTemplate
        if (apiUrl.isBlank()) apiUrl = defaults.apiUrl
        if (model.isBlank()) model = defaults.model
        if (credentialKey.isBlank()) credentialKey = defaults.credentialKey
    }
}

class AiTaskManagerState {
    var activeProvider: AiProviderKind = AiProviderKind.CLAUDE_CLI
    var maxThreads: Int = 1
    var excludedGlobs: String = ""
    var filePathRegex: String = "^.*$"

    var copilot: ProviderConfig = ProviderConfig().apply {
        binaryPath = "gh"
        searchCommandTemplate = "{binary} -s -p \"list files matching: {prompt}\""
        taskCommandTemplate = "{binary} -s -p \"review and refactor {file};write result to file '.ai/`filename`.md'\""
    }
    var claudeCli: ProviderConfig = ProviderConfig().apply {
        binaryPath = "claude"
        searchCommandTemplate = "{binary} -p \"list files matching: {prompt}\" --add-dir \"{projectDir}\""
        taskCommandTemplate = "{binary} -p \"review file {file}; write result to file '.ai/`filename`.md'\" --add-dir \"{projectDir}\""
    }
    var claudeApi: ProviderConfig = ProviderConfig().apply {
        apiUrl = "https://api.anthropic.com/v1/messages"
        model = "claude-opus-4-7"
        credentialKey = "aitaskmanager.claude.api.token"
    }
}

@State(
    name = "AiTaskManagerSettings",
    storages = [Storage("AiTaskManager.xml")]
)
@Service(Service.Level.APP)
class AiTaskManagerSettings : PersistentStateComponent<AiTaskManagerState> {
    private var state: AiTaskManagerState = AiTaskManagerState()

    override fun getState(): AiTaskManagerState = state

    override fun loadState(state: AiTaskManagerState) {
        XmlSerializerUtil.copyBean(state, this.state)
        // Re-apply built-in defaults for any fields that are blank after deserialization.
        // This handles both a first-run (loadState called with a default-constructed instance)
        // and migration from an older XML that used the single "commandTemplate" field.
        val defaults = AiTaskManagerState()
        this.state.copilot.applyMissingDefaults(defaults.copilot)
        this.state.claudeCli.applyMissingDefaults(defaults.claudeCli)
        this.state.claudeApi.applyMissingDefaults(defaults.claudeApi)
    }

    fun providerConfig(kind: AiProviderKind): ProviderConfig = when (kind) {
        AiProviderKind.COPILOT -> state.copilot
        AiProviderKind.CLAUDE_CLI -> state.claudeCli
        AiProviderKind.CLAUDE_API -> state.claudeApi
    }

    /**
     * Wipe everything the plugin stores and restore built-in defaults.
     * Also clears the Claude API token from the secure credential store.
     */
    fun reset() {
        val previousCredentialKey = state.claudeApi.credentialKey
        if (previousCredentialKey.isNotBlank()) {
            CredentialStore.setSecret(previousCredentialKey, "")
        }
        state = AiTaskManagerState()
    }

    companion object {
        @JvmStatic
        fun getInstance(): AiTaskManagerSettings =
            ApplicationManager.getApplication().getService(AiTaskManagerSettings::class.java)
    }
}