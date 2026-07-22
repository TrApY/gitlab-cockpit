<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cockpit for GitLab Changelog

## [Unreleased]

## [0.14.0] - 2026-07-22

### Added

- Pipeline and merge-request notifications now carry two actions — "Open in Cockpit" (activates the tool window and opens the merge request's tab, even when the window was closed) and "Open in GitLab" (the merge request's web page) — and each balloon shows a per-event icon (GLC-54)

### Changed

- Pipeline notifications now read like the other events: the outcome ("Pipeline succeeded" / "Pipeline failed") is the title and the `!iid — title` line is the body (GLC-54)

### Fixed

- Merge request titles containing `<`, `>` or `&` no longer break or drop text in notification balloons: every title is HTML-escaped before it is shown (GLC-54)

## [0.13.2] - 2026-07-22

### Fixed

- Clicking a discussion's `[file:line]` jump link in Events & Discussions now scrolls the diff to the commented line — the jump used to land on the first change because the platform's initial scroll overwrote the manual one

## [0.13.1] - 2026-07-22

### Fixed

- Switching the list filter (e.g. the state to ALL) no longer floods "new merge request" notifications for every merge request the new filter exposes: a filter change now rebaselines the watcher silently, and "new merge request" only ever fires for open ones

### Added

- Every setting in Settings → Tools → Cockpit for GitLab now carries an explanation line

## [0.13.0] - 2026-07-22

### Added

- Complete native-style redesign (modeled on the JetBrains GitLab plugin): two-line MR list with avatars, branch chips, comment/pipeline badges and per-avatar tooltips
- Per-MR editor-style tabs with a single review view — changes tree beside Info | Events & Discussions — and Pipelines as a drill-in from the pipeline status
- Vertical MR action toolbar: approve/revoke, request changes, refresh, checkout of the source branch, close, copy link, open in browser and watch
- Unified Edit Merge Request dialog: title, description, assignees, reviewers, labels and mark-as-draft
- Events & Discussions timeline as rounded cards: system events, emoji reactions, hover actions (edit/delete/copy comment link), search, filters and a floating markdown composer with review drafts (threaded reply drafts included)
- Job logs open as editor tabs with live streaming; the pipeline view refreshes in real time while visible
- Changes-by-version selector, reviewed-files tracking with auto-mark from the diff, real file-type icons and change-type colors

### Changed

- Every status surface uses the platform's circled New-UI icons; people, comment and branch glyphs match the JetBrains collaboration icon set

### Fixed

- The "Events & Discussions" tab losing its ampersand (bundle mnemonic processing), duplicated avatars in list rows, the frozen tree/info splitter after opening Pipelines, MR tabs that could not be closed, and the changes tree now follows diff navigation

## [0.12.1] - 2026-07-21

### Added

- Optional sticky notifications: keep event balloons on screen until dismissed (Settings → Tools → Cockpit for GitLab)

## [0.12.0] - 2026-07-16

### Added

- Keyboard-first diff review: MR files open as a diff chain (Alt+Shift+Left/Right to move between files), Ctrl+Shift+X comments on the caret line, and Ctrl+Alt+Up/Down jumps between review threads; all actions are remappable and available in the diff context menu

## [0.11.1] - 2026-07-16

### Fixed

- Address Marketplace verification warnings: stop bundling the IDE's markdown library and drop usages of an API scheduled for removal

## [0.11.0] - 2026-07-15

### Added

- Notifications keep working with the tool window closed (configurable background poll interval), a per-MR Watch toggle adds any merge request to the notification scope, and an option widens the scope to every merge request of the current filter

## [0.10.0] - 2026-07-15

### Added

- Configurable notifications: choose which events raise a balloon (pipeline result, new MRs in your scope, state changes, new pushes, new comments) from Settings

## [0.9.0] - 2026-07-15

### Added

- Commented lines are marked in the diff (gutter icon with the comment count, plus an amber line highlight for threads that still need attention), comment anchors in the Comments tab jump to the thread in the diff (select the file, open the diff and scroll to it), and inline thread blocks are visually set off from the code with a colored left accent bar (amber while unresolved, green once resolved)

## [0.8.0] - 2026-07-15

### Added

- Merge from the Overview tab: a merge-status-aware button (enabled when the merge request is mergeable, offering "Merge when pipeline succeeds" while CI is running, disabled with the blocker reason as a tooltip otherwise), a confirmation dialog with Squash and Delete source branch options that are remembered (and editable in Settings), created/merged/closed dates in the header, and colored approvals (green when satisfied, amber with "N more required" when pending)

## [0.7.0] - 2026-07-15

### Added

- Comments tab shows real discussion threads (replies indented, resolved and diff-anchor badges) and lets you reply to a thread

## [0.6.0] - 2026-07-15

### Added

- Images attached to merge request descriptions and comments now render: each embedded `/uploads/…` image is downloaded authenticated with your token and cached on disk, so the pictures show up instead of broken-image icons. Non-image attachment links are absolutized and open in the browser

## [0.5.2] - 2026-07-15

### Fixed

- Job logs render clean on GitLab 17+ timestamped traces: the per-line timestamp prefixes are stripped, continuation lines are joined back into their logical line, `section_start`/`section_end` markers and non-color terminal escapes (line clears, cursor moves) are dropped, and real ANSI colors are shown instead of literal escape codes

## [0.5.1] - 2026-07-15

### Fixed

- Repo selector for projects with several git roots (submodules): a toolbar dropdown appears when more than one git root matches the configured GitLab instance, letting you choose which repository the tool window browses. The default is no longer arbitrary — the project's own root wins over nested submodules instead of whichever remote was found first

## [0.5.0] - 2026-07-15

### Added

- "All projects" filter: a toolbar checkbox that lists merge requests across the whole GitLab instance where you are author or reviewer (or the filtered user), not just the git-resolved project. Each row is prefixed with its `group/project`, and the full detail (comments, changes, pipelines, approve, edit) keeps working on merge requests from any project

## [0.4.1] - 2026-07-15

### Added

- Incremental search in the Edit Assignee and Edit Reviewers dialogs (search field over the member list; reviewer checks are preserved even while a search hides them), with the full project member list now fetched across all pages
- Autocompletion in the "By user" filter: type to complete project members (`Name (@username)`), with the reload debounced so typing, pasting or picking from the popup all coalesce
- Resolved GitLab project shown as a clickable link in the tool window toolbar (opens the project in the browser)

### Fixed

- Pipelines tab no longer stays empty for merge requests whose CI is reported externally (e.g. Jenkins): the MR's head pipeline is merged into the list and pipelines without a ref render correctly

## [0.4.0] - 2026-07-15

### Added

- Review threads rendered inline inside the diff editor (embedded components below their anchored line, correct side), with expand/collapse, reply and resolve/unresolve without leaving the diff

## [0.3.0] - 2026-07-15

### Added

- Changes tab: changed-files tree with per-file comment counters, editor diff (base/head, no checkout needed) and per-file review discussions with reply
- Create positioned review threads: "New thread" dialog (commentable lines only) and "Comment on line…" action inside the diff viewer (exact side + line)
- Draft notes: save comments as drafts, pending-review section, delete drafts, "Submit review" (bulk publish) with IDE notification
- Resolve / unresolve review discussions

## [0.2.0] - 2026-07-14

### Added

- MR list tool window with role filters (author / reviewer / reviewer not yet approved / by user), state filter and auto-refresh
- MR detail: markdown description, edit title/description, reviewers and assignee from the IDE
- Comments tab (user notes thread) and approve / revoke approval
- Pipelines tab: stage strip, stage→job tree with aggregated statuses, retry/cancel/play, simulated stage retry, run pipeline on branch
- Live job log viewer (ANSI colors, incremental streaming while the job runs)
- IDE notifications when a pipeline of your MRs finishes (success / failed)
- Settings: instance URL + personal access token stored in PasswordSafe, connection test

## [0.0.1] - 2026-07-14

### Added

- Initial scaffolding from IntelliJ Platform Plugin Template
