# Cockpit for GitLab

[![Version](https://img.shields.io/jetbrains/plugin/v/32942.svg)](https://plugins.jetbrains.com/plugin/32942)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32942.svg)](https://plugins.jetbrains.com/plugin/32942)
[![Build](https://github.com/TrApY/gitlab-cockpit/actions/workflows/build.yml/badge.svg)](https://github.com/TrApY/gitlab-cockpit/actions/workflows/build.yml)

<!-- Plugin description -->
<p>Run the full GitLab merge request review cycle without leaving your IDE. Cockpit for GitLab adds a dedicated tool window where you browse, review and merge your merge requests, and follow their pipelines from first job to green.</p>
<ul>
  <li><b>Browse and filter</b> merge requests by role (author, reviewer, reviewer not yet approved) or by user, across a single project, every project of the instance, and multi-root repositories.</li>
  <li><b>Review in depth</b>: discussion threads with replies, draft reviews with bulk submit, and inline comments anchored to the diff, right inside the editor — with keyboard-first navigation between files and review threads.</li>
  <li><b>Merge</b> with a status-aware button that knows when the request is mergeable, offers "merge when the pipeline succeeds", and shows the blocking reason otherwise.</li>
  <li><b>Pipelines</b> for each merge request: a compact attention-first view of stages and jobs, retry, cancel and play actions, live logs with ANSI colors streamed as the job runs — including downstream pipelines triggered by bridges and the post-merge pipeline on the target branch.</li>
  <li><b>Images and attachments</b> from descriptions and comments are fetched with your token and rendered inline, and you can attach files from the comment composer.</li>
  <li><b>Configurable notifications</b>, including background polling with the tool window closed, a per-merge-request watch toggle, and "Open in Cockpit" actions that jump straight to the event's section.</li>
</ul>
<p>Setup: open <b>Settings &gt; Tools &gt; Cockpit for GitLab</b>, add a personal access token with the <b>api</b> scope, and point it at your instance. Works with self-managed GitLab 17+ and gitlab.com.</p>
<!-- Plugin description end -->

## Installation

Install it from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32942) — in your IDE: **Settings → Plugins → Marketplace**, search for **"Cockpit for GitLab"**.

Requirements:

- Any IntelliJ-based IDE **2025.2 or newer** (IntelliJ IDEA, PyCharm, WebStorm, GoLand…), with the bundled Git plugin enabled.
- **GitLab 17+** (self-managed) or **gitlab.com**, and a personal access token with the `api` scope.

## Setup

1. Open **Settings → Tools → Cockpit for GitLab**.
2. Set your GitLab instance URL and paste a personal access token with the **api** scope (stored in the IDE's PasswordSafe, never in plain text).
3. Press **Test connection**, apply, and open the **Cockpit for GitLab** tool window. The project is resolved automatically from your git remotes; repositories with several roots get a selector in the toolbar.

## What you get

### Merge request list

Two-line rows with avatars, branch chips and comment/pipeline badges. Filter by role (author, reviewer, reviewer not yet approved), by any user, by state, or widen the scope to **all projects** of the instance. Auto-refreshes while open.

### Review

Each merge request opens as an editor-style tab: changes tree beside **Info | Events & Discussions**. Diffs open straight from the API — no checkout needed — with review threads rendered inline below their anchored line, reviewed-files tracking, a changes-by-version selector, and real file-type icons. Draft notes accumulate into a pending review you submit in one go. The **Events & Discussions** timeline shows system events, emoji reactions, search and filters, and a floating markdown composer with file attachments (up to 10 MB).

Keyboard-first diff review (all remappable, also in the diff context menu):

| Shortcut | Action |
|---|---|
| `Alt+Shift+←` / `Alt+Shift+→` | Previous / next file in the diff chain |
| `Ctrl+Shift+X` | Comment on the caret line |
| `Ctrl+Alt+↑` / `Ctrl+Alt+↓` | Jump between review threads |

### Merge

A merge-status-aware button: enabled when mergeable, **"Merge when pipeline succeeds"** while CI runs, disabled with the blocking reason as a tooltip otherwise. Squash and delete-source-branch choices are remembered. Approve/revoke, request changes, checkout of the source branch, close, copy link and watch live in the vertical action toolbar, and the unified **Edit** dialog covers title, description, assignees, reviewers, labels and draft state — with destination-branch autocompletion.

### Pipelines

A compact, attention-first view: fully passed stages fold into a single row and only stages needing attention keep their own, with a persisted "Show all stages" toggle. Retry, cancel and play jobs, run a pipeline on a branch, and open **live job logs with ANSI colors** as editor tabs, streamed while the job runs. Downstream pipelines triggered by bridge jobs appear as `→ bridge · status` rows (cross-project included), and once a merge request is merged, the **post-merge pipeline** on the target branch shows up too — so a red `master` after merging never goes unnoticed.

### Notifications

Choose which events raise a balloon: pipeline results (including downstream failures), new merge requests in your scope, state changes, new pushes, new comments and approvals. They keep working with the tool window closed via a configurable background poll, any merge request can be added to the scope with its **Watch** toggle, and balloons can be made sticky. **"Open in Cockpit"** jumps to the section that matches the event — pipeline balloons open the Pipelines view, comment balloons open Events & Discussions.

## Development

```bash
./gradlew test           # Unit test suite (JUnit4)
./gradlew check          # What CI runs (tests + verifications)
./gradlew runIde         # Sandbox IDE with the plugin installed
./gradlew buildPlugin    # Installable ZIP in build/distributions/
./gradlew verifyPlugin   # Plugin Verifier (same as Marketplace)
```

Requires JDK 21+. Architecture, conventions and testing patterns are documented in [CLAUDE.md](CLAUDE.md); tickets are referenced as `GLC-NN` in commits and KDoc.

> **Known gotcha (build cache):** after changing a data class signature (e.g. adding a field with a default to an API model), the Kotlin build cache may serve stale test bytecode and fail with `NoSuchMethodError`. Clean it up with `./gradlew clean build --rerun-tasks`.

### Releasing

Push to `main` → CI builds, verifies and drafts a GitHub release with the `[Unreleased]` changelog notes. Publishing that release triggers the signed `publishPlugin` upload to the Marketplace. Version lives in `gradle.properties`; change notes in `CHANGELOG.md` (Keep a Changelog). The Marketplace listing description is injected from the `<!-- Plugin description -->` block of this README at build time.

## License

[Apache 2.0](LICENSE)
