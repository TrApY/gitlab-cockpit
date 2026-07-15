<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cockpit for GitLab Changelog

## [Unreleased]

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
