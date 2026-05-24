package com.aitaskmanager.ui

import com.aitaskmanager.ai.AiProviderFactory
import com.aitaskmanager.log.LogService
import com.aitaskmanager.settings.AiProviderKind
import com.aitaskmanager.settings.AiTaskManagerSettings
import com.aitaskmanager.settings.CredentialStore
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants

class ConfigurationTab : JPanel(BorderLayout()) {

    private val settings = AiTaskManagerSettings.getInstance()

    private val threadsSpinner = JSpinner(SpinnerNumberModel(settings.state.maxThreads, 1, 64, 1))
    private val providerCombo = ComboBox(AiProviderKind.values()).apply {
        selectedItem = settings.state.activeProvider
    }
    private val excludedGlobsArea = JBTextArea(settings.state.excludedGlobs, 5, 40)
    private val filePathRegexField = JBTextField(settings.state.filePathRegex)

    private val cards = JPanel(CardLayout())
    private val testOutput = JBTextArea(6, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    // copilot
    private val copilotBinary = TextFieldWithBrowseButton().apply { text = settings.state.copilot.binaryPath }
    private val copilotSearchCommand = JBTextArea(settings.state.copilot.searchCommandTemplate, 3, 60)
    private val copilotTaskCommand = JBTextArea(settings.state.copilot.taskCommandTemplate, 3, 60)

    // claude cli
    private val claudeCliBinary = TextFieldWithBrowseButton().apply { text = settings.state.claudeCli.binaryPath }
    private val claudeCliSearchCommand = JBTextArea(settings.state.claudeCli.searchCommandTemplate, 3, 60)
    private val claudeCliTaskCommand = JBTextArea(settings.state.claudeCli.taskCommandTemplate, 3, 60)

    // claude api
    private val claudeApiUrl = JBTextField(settings.state.claudeApi.apiUrl)
    private val claudeApiModel = JBTextField(settings.state.claudeApi.model)
    private val claudeApiToken = JBPasswordField().apply {
        text = CredentialStore.getSecret(settings.state.claudeApi.credentialKey) ?: ""
    }

    init {
        copilotBinary.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Select Copilot Binary")
        )
        claudeCliBinary.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Select Claude CLI Binary")
        )

        cards.add(buildCopilotCard(), AiProviderKind.COPILOT.name)
        cards.add(buildClaudeCliCard(), AiProviderKind.CLAUDE_CLI.name)
        //@todo uncomment after testing
//        cards.add(buildClaudeApiCard(), AiProviderKind.CLAUDE_API.name)
        (cards.layout as CardLayout).show(cards, (providerCombo.selectedItem as AiProviderKind).name)

        providerCombo.addActionListener {
            val sel = providerCombo.selectedItem as AiProviderKind
            (cards.layout as CardLayout).show(cards, sel.name)
        }

        val saveButton = JButton("Save").apply { addActionListener { save() } }
        val testButton = JButton("Test active provider").apply { addActionListener { runTest() } }
        val resetButton = JButton("Reset Configuration").apply { addActionListener { resetAll() } }

        excludedGlobsArea.lineWrap = false
        copilotSearchCommand.lineWrap = false
        copilotTaskCommand.lineWrap = false
        claudeCliSearchCommand.lineWrap = false
        claudeCliTaskCommand.lineWrap = false

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Maximum parallel threads:"), threadsSpinner)
            .addLabeledComponent(JBLabel("Active AI provider:"), providerCombo)
            .addComponent(cards)
            .addSeparator()
            .addComponent(JBLabel("Excluded file globs (one per line, applied on Import & Send-to-AITM):"))
            .addComponent(JBScrollPane(excludedGlobsArea).apply { preferredSize = Dimension(600, 100) })
            .addLabeledComponent(JBLabel("Search-output file-path regex:"), filePathRegexField)
            .addSeparator()
            .addComponent(buildButtonBar(testButton, saveButton, resetButton))
            .addComponent(JBLabel("Test output:"))
            .addComponent(JBScrollPane(testOutput).apply { preferredSize = Dimension(600, 140) })
            .addSeparator()
            .addComponent(buildInstructionsPanel())
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty(8)
            verticalScrollBar.unitIncrement = 16
        }
        add(scroll, BorderLayout.CENTER)
    }

    private fun buildButtonBar(vararg buttons: JButton): JPanel {
        val p = JPanel()
        buttons.forEach { p.add(it) }
        return p
    }

    private fun buildCopilotCard(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Binary path:"), copilotBinary)
        .addComponent(JBLabel("Search command template (use {binary}, {prompt}):"))
        .addComponent(JBScrollPane(copilotSearchCommand).apply { preferredSize = Dimension(600, 60) })
        .addComponent(JBLabel("Task command template (use {binary}, {prompt}, {file}):"))
        .addComponent(JBScrollPane(copilotTaskCommand).apply { preferredSize = Dimension(600, 60) })
        .panel

    private fun buildClaudeCliCard(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Binary path:"), claudeCliBinary)
        .addComponent(JBLabel("Search command template (use {binary}, {prompt}):"))
        .addComponent(JBScrollPane(claudeCliSearchCommand).apply { preferredSize = Dimension(600, 60) })
        .addComponent(JBLabel("Task command template (use {binary}, {prompt}, {file}):"))
        .addComponent(JBScrollPane(claudeCliTaskCommand).apply { preferredSize = Dimension(600, 60) })
        .panel

    private fun buildClaudeApiCard(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("API URL:"), claudeApiUrl)
        .addLabeledComponent(JBLabel("Model:"), claudeApiModel)
        .addLabeledComponent(JBLabel("API token:"), claudeApiToken)
        .panel

    private fun buildInstructionsPanel(): JPanel {
        val area = JBTextArea(
            """
            How to configure:

            * Claude API
              1. Sign in at https://console.anthropic.com.
              2. Create an API key under Settings -> API Keys.
              3. Paste it into the "API token" field above.
              4. Default model is claude-opus-4-7 (latest); change if needed.

            * Claude CLI
              1. Install: npm install -g @anthropic-ai/claude-code
              2. Authenticate: claude /login
              3. Binary path is typically `claude` (resolved via PATH).
              4. Command template: {binary} -p "{prompt}"     (for search)
                                    {binary} -p "{prompt}" --add-dir "{file}"  (for tasks)

            * GitHub Copilot CLI
              1. Install GitHub CLI: https://cli.github.com/
              2. gh auth login
              3. gh extension install github/gh-copilot
              4. Test: gh copilot suggest "list js files using lodash"

            Tips:
            - Use the "Test" button to verify the binary/API is reachable.
            - Excluded globs use Java NIO glob syntax (e.g. **/node_modules/**, **/*.min.js).
            """.trimIndent(),
            14, 80
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel("Instructions:", SwingConstants.LEFT), BorderLayout.NORTH)
        panel.add(JBScrollPane(area).apply { preferredSize = Dimension(600, 240) }, BorderLayout.CENTER)
        return panel
    }

    private fun save() {
        val s = settings.state
        s.maxThreads = (threadsSpinner.value as Int).coerceIn(1, 64)
        s.activeProvider = providerCombo.selectedItem as AiProviderKind
        s.excludedGlobs = excludedGlobsArea.text
        s.filePathRegex = filePathRegexField.text.ifBlank { "^.*$" }

        s.copilot.binaryPath = copilotBinary.text
        s.copilot.searchCommandTemplate = copilotSearchCommand.text
        s.copilot.taskCommandTemplate = copilotTaskCommand.text

        s.claudeCli.binaryPath = claudeCliBinary.text
        s.claudeCli.searchCommandTemplate = claudeCliSearchCommand.text
        s.claudeCli.taskCommandTemplate = claudeCliTaskCommand.text

        s.claudeApi.apiUrl = claudeApiUrl.text
        s.claudeApi.model = claudeApiModel.text
        CredentialStore.setSecret(s.claudeApi.credentialKey, String(claudeApiToken.password))

        LogService.getInstance().append("Configuration saved")
        testOutput.text = "Saved."
    }

    private fun resetAll() {
        val answer = Messages.showYesNoDialog(
            this,
            "Reset all AI Task Manager settings to their defaults?\n\n" +
                "This will:\n" +
                "  • Replace every value on this tab (provider, templates, globs, regex, thread cap) with the built-in defaults.\n" +
                "  • Delete the stored Claude API token from the IDE credential store.\n\n" +
                "This cannot be undone.",
            "Reset Configuration",
            Messages.getWarningIcon()
        )
        if (answer != Messages.YES) return

        settings.reset()
        loadFromSettings()
        testOutput.text = "Configuration reset to defaults."
        LogService.getInstance().append("Configuration reset to defaults")
    }

    private fun loadFromSettings() {
        val s = settings.state
        threadsSpinner.value = s.maxThreads
        providerCombo.selectedItem = s.activeProvider
        excludedGlobsArea.text = s.excludedGlobs
        filePathRegexField.text = s.filePathRegex

        copilotBinary.text = s.copilot.binaryPath
        copilotSearchCommand.text = s.copilot.searchCommandTemplate
        copilotTaskCommand.text = s.copilot.taskCommandTemplate

        claudeCliBinary.text = s.claudeCli.binaryPath
        claudeCliSearchCommand.text = s.claudeCli.searchCommandTemplate
        claudeCliTaskCommand.text = s.claudeCli.taskCommandTemplate

        claudeApiUrl.text = s.claudeApi.apiUrl
        claudeApiModel.text = s.claudeApi.model
        claudeApiToken.text = CredentialStore.getSecret(s.claudeApi.credentialKey) ?: ""

        (cards.layout as CardLayout).show(cards, s.activeProvider.name)
    }

    private fun runTest() {
        save()
        testOutput.text = "Testing..."
        val provider = AiProviderFactory.get()
        val builder = StringBuilder()
        val result = provider.test { line -> builder.append(line).append('\n') }
        testOutput.text = (if (result.ok) "OK\n" else "FAILED\n") + result.explanation +
            if (builder.isNotEmpty()) "\n\n--- output ---\n$builder" else ""
        LogService.getInstance().append("Test ${if (result.ok) "succeeded" else "failed"}: ${result.explanation.lineSequence().firstOrNull()}")
    }
}
