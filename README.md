# AI Task Manager

An IntelliJ Platform plugin that orchestrates AI-driven, file-by-file investigations across your project. Run a single AI search to find candidate files ("which JS files still use lodash?"), then queue per-file follow-up tasks that execute one-at-a-time or in parallel — with full logging, state tracking, and ZIP export/import.

Built against **IntelliJ Platform 2025.3.5** (Kotlin, JBR 21).

---

## What it does

```
┌──────────────────────────────────────────────────────────────┐
│  1. Type a prompt in the Search tab                          │
│     "find JS files importing lodash"                         │
│                                                              │
│  2. The plugin runs the configured AI CLI/API to discover    │
│     candidate files; each file becomes a task row.           │
│                                                              │
│  3. Click "Run all" — every task runs the same per-file      │
│     prompt against the AI, respecting your thread cap.       │
│                                                              │
│  4. Watch progress, stop individual tasks, review logs,      │
│     export the whole session to a ZIP for later.             │
└──────────────────────────────────────────────────────────────┘
```

Supported AI providers (configurable per workspace):
- **GitHub Copilot CLI** (`gh copilot`)
- **Claude CLI** (`@anthropic-ai/claude-code`, recommended)
- **Claude API** (direct HTTP to `api.anthropic.com`)

---

## The tool window

The plugin contributes a single tool window (right-side anchor) with **three tabs**:

### Search tab

- **Search field** (`JBTextField` with placeholder text). Hit Enter to fire the search.
- **Action toolbar** — Search · Stop · Run all · Remove selected · Clean all · Export… · Import… · Import from Find Results.
- **Progress bar + ETA** — `done / total`, ETA computed as `avg_task_duration × remaining / running`.
- **Task list** — each row shows:
  - State icon on the left (▶ pending, ⏵ running, ✓ completed, ⏸ stopped, ✗ failed)
  - File path
  - **Two inline icons on the right**:
    - 👁 **Review log** — opens the Log tab pre-filtered to this task
    - ▶ / ■ / ♻ **Action** — play / stop / re-run, morphs by state
- **Key bindings on the list** — `Delete` removes selected rows, `Space` toggles run/stop.

### Log tab

- Monospaced text area showing the full log history.
- **Toolbar** — Clear · Auto-scroll-to-bottom toggle.
- **Filter dropdown** — `All` (default) or per-task (`basename(file) [first 8 chars of id]`). The dropdown auto-refreshes when tasks are added/removed.
- Live entries stream in honouring the active filter; the filter is also triggered programmatically when you click the per-row 👁 icon in the Search tab.

### Configuration tab

| Section | What it controls |
|---|---|
| Maximum parallel threads | `JSpinner` (1..64). **Default 1** — strictly one task at a time out of the box. |
| Active AI provider | `ComboBox<Copilot, ClaudeCli, ClaudeApi>`. The sub-panel below swaps via `CardLayout`. |
| Per-provider settings | Binary path (with browse button) + **search command template** + **task command template** for CLI providers; API URL / model / token (`PasswordSafe`) for the API provider. |
| Excluded globs | Newline-separated patterns (Java NIO glob syntax) applied on Search-tab Import and AI-search imports. |
| Search-output regex | How `searchFiles()` parses file paths out of CLI stdout (default `^.*$`). |
| **Test active provider** | Invokes `provider.test()` and shows ✔/✗ plus the binary's output. |
| **Save** | Writes settings to `AiTaskManager.xml` (the API token goes to `PasswordSafe`, not XML). |
| **Reset Configuration** | Destructive — confirmation dialog, then `AiTaskManagerSettings.reset()` blanks every field and clears the stored Claude API token. |

#### Command-template placeholders

Templates are tokenised first, then placeholder substitution happens per token, so quoted multi-word arguments survive intact.

| Placeholder | Replaced with |
|---|---|
| `{binary}` | The configured binary path |
| `{prompt}` | The current search prompt (or task-specific prompt) |
| `{file}` | The target file path (task templates only) |
| `{projectDir}` | The currently open project's base directory (resolved at runtime from `ProjectManager.getInstance().openProjects.firstOrNull()?.basePath`) |

**Default Claude CLI templates** (after Reset Configuration):

```
Search:  {binary} -p "list files matching: {prompt}" --add-dir "{projectDir}"
Task:    {binary} -p "review file {file}; write result to file '.ai/`filename`.md'" --add-dir "{projectDir}"
```

> ⚠️ **Important** — Claude CLI's `--add-dir` expects a **directory**, not a file. Always pass `{projectDir}` there and embed `{file}` inside the prompt text. Passing `--add-dir "{file}"` fails with `is not a directory`.

---

## Integration with the IDE's Find in Files

The plugin contributes a **"Send to AI Task Manager"** action to the `UsageView.Popup` action group, so it appears in the right-click menu of any Find in Files / Find Usages result.

Selection logic (in `FindResultsCollector.collectNonExcludedPaths`):

1. Walks the UsageView tree (`UsageViewImpl.getRoot()`), checking `UsageNode.isExcluded()` on each leaf — the same source-of-truth IntelliJ's own "Replace in Files" uses. **Files the user has unchecked / struck-through are skipped.**
2. Also subtracts `usageView.getExcludedUsages()` as a second filter.
3. Falls back to `CommonDataKeys.VIRTUAL_FILE_ARRAY` only when no UsageView is reachable.
4. After path collection, the same `excludedGlobs` filter from settings is applied.

You can also click **"Import from Find Results"** in the plugin's own toolbar; it uses the same helper, so behaviour is identical.

> Note: `UsageView.Toolbar` is **not** a registered action group in IntelliJ 2025.3 (the panel's toolbar is built programmatically), so the action lives in the right-click menu only.

---

## Export / Import

Both task list and logs round-trip through a single ZIP (deflated with `BEST_COMPRESSION`):

```
session.zip
├── tasks.json           — task list with state + timestamps
├── global.log           — every log line ever appended
└── logs/
    ├── <task-id-1>.log  — per-task subset of global.log
    ├── <task-id-2>.log
    └── …
```

**On import:**
- **Tasks** are restored; `excludedGlobs` removes anything matching, and `state` is normalized — `COMPLETED` is preserved (so "Run all" can skip), everything else becomes `PENDING` so the play button works immediately.
- **Log lines** are replayed via `LogService.restoreLines(...)` preserving original task IDs (and reconstructing timestamps as today + parsed `HH:mm:ss`). The Log tab filter dropdown sees them like live entries. Only `global.log` is parsed; per-task files are subsets and would just duplicate lines.

---

## Setting up the providers

### Claude CLI (recommended)
```bash
npm install -g @anthropic-ai/claude-code
claude /login
# Binary path: `claude` (PATH-resolved)
```

### GitHub Copilot CLI
```bash
# Install GitHub CLI from https://cli.github.com/
gh auth login
gh extension install github/gh-copilot
# Binary path: `gh`
```

### Claude API
1. Sign in at <https://console.anthropic.com>
2. Create an API key under Settings → API Keys
3. Paste it into the Configuration tab; default model is `claude-opus-4-7`.

Use **Test active provider** in the Configuration tab to verify the binary/API is reachable before running real tasks.

---

## Working directory

Every `ProcessBuilder` launched by a CLI provider sets its working directory to the active project's `basePath` (via `ProjectManager.getInstance().openProjects.firstOrNull()?.basePath`). Without this the JVM default is a Gradle cache path and the CLI would look in the wrong place.

The cwd is also echoed to the log at the start of every CLI invocation:

```
[claude] cwd: /home/you/IdeaProjects/myproject
[claude] $ claude -p "list files matching: java files" --add-dir "/home/you/IdeaProjects/myproject"
```

Commands are rendered with `ProcessRunner.quoteForDisplay(...)` — tokens containing whitespace are re-wrapped in double quotes so the log line matches what you wrote in the template.

---

## Predefined Run/Debug configurations

In `.run/`:

| Configuration | Gradle task | Purpose |
|---|---|---|
| Run Plugin | `:runIde` | Launch the sandbox IDE with the plugin installed |
| Run Tests | `:test` | Execute unit tests |
| Run Verifications | `:verifyPlugin` | Plugin Verifier against 2025.3.5 |
| Build Plugin | `:buildPlugin` | Produce a distributable ZIP under `build/distributions/` |
| Publish Plugin | `:publishPlugin` | Push to JetBrains Marketplace (requires `PUBLISH_TOKEN` env) |

### Opening a real project in the sandbox IDE

`build.gradle.kts` configures the `RunIdeTask` to open a real project so the plugin has a live `Project` reference on first paint (without it, `openProjects` is empty and CLI cwd falls back to `user.home`):

```kotlin
tasks.withType<RunIdeTask> {
    val sandboxProject = providers.environmentVariable("SANDBOX_PROJECT")
        .orNull ?: "/home/user/IdeaProjects/untitled"
    val sandboxProjectName = sandboxProject.substringAfterLast('/')
    args = listOf(sandboxProject)
    jvmArgs = listOf(
        "-Didea.trust.all.projects=true",                                                 // skip the Trust dialog
        "-Didea.config.path=${System.getProperty("user.home")}/.idea-run-$sandboxProjectName",  // isolate sandbox config
    )
}
```

Set `SANDBOX_PROJECT=/path/to/your/project` in your shell profile so each developer's setup is local.

---

## Publishing to JetBrains Marketplace

### One-time setup

1. Sign in to <https://plugins.jetbrains.com> with the JetBrains account that will own the listing.
2. Go to **Profile → Permanent Tokens** and create one — that's your `PUBLISH_TOKEN`.
3. *(Optional but recommended)* generate a code-signing keypair following [Signing a Plugin](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) and keep the cert chain + private key files outside the repo.

### Per-release steps

```bash
# 1. Bump version in gradle.properties (semver). Keep the same `id` in plugin.xml.
#    Add a new section to CHANGELOG.md AND mirror the bullets into <change-notes> in plugin.xml.

# 2. Verify the plugin against the target IDE build range.
./gradlew verifyPlugin

# 3. Produce the signed/distributable ZIP locally to inspect.
./gradlew buildPlugin
ls build/distributions/   # → AiTaskManager-<version>.zip

# 4. Publish.
PUBLISH_TOKEN=perm-xxxxxxxx \
  CERTIFICATE_CHAIN_FILE=/abs/path/chain.crt \
  PRIVATE_KEY_FILE=/abs/path/private.pem \
  PRIVATE_KEY_PASSWORD='…' \
  ./gradlew publishPlugin
```

Want a side channel (EAP/beta) instead of the stable Marketplace listing? Set `PUBLISH_CHANNEL=beta` (or `eap`) before `publishPlugin`. With no env var the plugin goes to the `default` (stable) channel.

### What Marketplace checks on upload

- Unique plugin **id** that never changes between versions (`com.aitaskmanager` for us).
- Description ≥ 40 characters, no `yourcompany.com` / placeholder vendor URLs.
- `<change-notes>` is present for the version being uploaded.
- The supplied `<idea-version since-build … until-build …>` is valid (`253` … `253.*` here).
- The ZIP is signed if you set the signing env vars.

Marketplace guidelines: <https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html>
Quality guidelines: <https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html>

---

## Plugin source layout

```
src/main/kotlin/com/aitaskmanager/
├── action/         SendFindResultsAction, FindResultsCollector
├── ai/             AiProvider, CopilotCliProvider, ClaudeCliProvider, ClaudeApiProvider,
│                   AiProviderFactory, CliProvider, ProcessRunner
├── icons/          AiTaskManagerIcons
├── io/             ExportImport, GlobMatcher
├── log/            LogService (ApplicationService, LogLine, LogListener)
├── settings/       AiTaskManagerSettings, ProviderConfig, CredentialStore
├── task/           AiTask, TaskState, TaskExecutorService, TaskRegistry, TaskRegistryListener
└── ui/             AiTaskManagerToolWindowFactory, SearchTab, LogTab, ConfigurationTab,
                    TaskListCellRenderer
src/main/resources/
├── META-INF/plugin.xml
└── icons/          aitm.svg, play.svg, stop.svg, recycle.svg
```

Implementation notes and decision history live in [`.ai/plan.md`](./.ai/plan.md).

---

## Useful links

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [Plugin configuration file (plugin.xml)](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html)
- [Marketplace publishing](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [Claude Code CLI](https://github.com/anthropics/claude-code)
