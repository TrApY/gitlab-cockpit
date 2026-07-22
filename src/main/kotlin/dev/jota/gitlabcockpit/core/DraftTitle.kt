package dev.jota.gitlabcockpit.core

/**
 * Pure, platform-free helpers for the "Mark as draft" toggle (GLC-42).
 *
 * GitLab's REST **Update merge request** endpoint (`PUT /projects/:id/merge_requests/:iid`) exposes
 * **no** direct draft/WIP input: `draft` appears there only as a *filter* and as a *response* field
 * (the deprecated `work_in_progress` alias), never as a writable attribute. The canonical way to flip
 * an MR's draft state over the REST API is therefore the same one GitLab's own `/draft` and `/ready`
 * quick actions use — toggling the `Draft: ` prefix on the title. This file is that toggle, kept pure
 * so the UI stages a normalized title and the round-trip is unit-testable without the platform.
 *
 * [DRAFT_MARKER] recognizes the historical markers GitLab accepts at the start of a title
 * (`Draft:`, `[Draft]`, `(Draft)`, `[WIP]`, `WIP:`), case-insensitively, so [applyDraftState] is
 * idempotent and also *normalizes* a legacy `WIP:`/bracketed marker to the modern `Draft: ` prefix.
 */

/** The prefix GitLab writes (and detects) for a draft MR; what [applyDraftState] prepends. */
const val DRAFT_PREFIX: String = "Draft: "

/**
 * Matches a single leading draft/WIP marker (and the whitespace after it) at the start of a title.
 * Mirrors the markers GitLab's own draft detection accepts, anchored at the start and case-insensitive.
 */
private val DRAFT_MARKER = Regex(
    """^\s*(?:\[draft\]|\(draft\)|draft:|\[wip\]|\(wip\)|wip:)\s*""",
    RegexOption.IGNORE_CASE,
)

/** Whether [title] already begins with a recognized draft/WIP marker. */
fun isDraftTitle(title: String): Boolean = DRAFT_MARKER.containsMatchIn(title.trimStart())

/** [title] with any single leading draft/WIP marker stripped (and surrounding whitespace trimmed). */
fun stripDraftPrefix(title: String): String = title.replaceFirst(DRAFT_MARKER, "").trim()

/**
 * Returns [title] normalized to the requested [draft] state: with [DRAFT_PREFIX] when [draft] is true,
 * or with any existing draft/WIP marker removed when it is false. Idempotent — applying the same state
 * twice yields the same string — and it collapses a legacy `WIP:`/`[Draft]` marker into the modern
 * `Draft: ` prefix. An all-marker title (e.g. just `"Draft:"`) collapses to an empty base.
 */
fun applyDraftState(title: String, draft: Boolean): String {
    val base = stripDraftPrefix(title)
    return if (draft) DRAFT_PREFIX + base else base
}
