<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# AI Task Manager Changelog

## [Unreleased]

## [0.1.1] — Marketplace compatibility

### Fixed

- Removed all `@ApiStatus.Internal` API references (`com.intellij.usages.impl.UsageNode`, `UsageViewImpl`, `UsageNode.isExcluded()`) that the JetBrains Marketplace verifier rejects on upload. `FindResultsCollector` now uses only public APIs (`UsageView.getExcludedUsages()` + `UsageInFile.getFile()`).
- "Send to AI Task Manager" prioritises the data-context selection so users can pick a subset of Find Results rows; with nothing selected, the action sends every non-excluded usage from the active `UsageView`.

## [0.1.0] — Initial public release

### Added

- **Tool window** "AI Task Manager" (right-side anchor) with three tabs: Search, Log, Configuration.
- **Two-step workflow** — initial AI search returns a file list, each file becomes a task; per-file follow-ups run via `Run all` (skips already-completed) or the per-row play button.
- **AI providers**:
  - Claude CLI (`@anthropic-ai/claude-code`, recommended).
  - Claude API (direct HTTP, token stored in `PasswordSafe`).
  - GitHub Copilot CLI (`gh copilot`).
- **Configurable command templates** with `{binary}`, `{prompt}`, `{file}`, `{projectDir}` placeholders. CLI cwd is the active project's base path.
- **Configurable parallelism** — `maxThreads` 1..64, default 1 (strictly serial).
- **Per-row icons** — play / stop / recycle action icon plus a "review log" eye icon that opens the Log tab pre-filtered to the clicked task.
- **Log tab filter dropdown** — `All` or per-task; auto-refreshes as tasks come and go.
- **"Send to AI Task Manager"** action contributed to the Find in Files / Find Usages right-click menu (`UsageView.Popup`). Honours the per-row checkbox / `UsageNode.isExcluded()` state via tree traversal — only the entries the user has left ticked are sent.
- **ZIP export/import** — round-trips both the task list and the full log (general + per-task). On import, task states are normalised (`COMPLETED` preserved, everything else → `PENDING`) so the play button is immediately usable.
- **Reset Configuration** button — wipes all settings (including the Claude API token in `PasswordSafe`) back to built-in defaults, with confirmation dialog.

### Compatibility

- IntelliJ Platform `2025.3.x` (build range `253` … `253.*`).
- JBR 21.

[Unreleased]: https://github.com/dimonlemon364-lab/ai-task-manager/compare/0.1.1...HEAD
[0.1.1]: https://github.com/dimonlemon364-lab/ai-task-manager/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/dimonlemon364-lab/ai-task-manager/commits/0.1.0
