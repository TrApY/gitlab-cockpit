<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cockpit for GitLab Changelog

## [Unreleased]

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
