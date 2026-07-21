package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/**
 * How a first-line text run should be painted. The renderer maps each style to a concrete color; the
 * composition itself stays platform- and color-free so it can be unit tested directly.
 */
enum class MrSegmentStyle {
    /** The title itself — the list's normal foreground. */
    NORMAL,

    /** The `Draft:` prefix — [dev.jota.gitlabcockpit.ui.CockpitTheme.warning]. */
    WARNING,

    /** The ` · conflicts` suffix — [dev.jota.gitlabcockpit.ui.CockpitTheme.danger]. */
    DANGER,
}

/** One painted run of text on a row's first line: its [text] and the [style] the renderer applies. */
data class MrRowSegment(val text: String, val style: MrSegmentStyle)

/**
 * The text content of a two-line merge-request row, split so the renderer only paints it. [line1] is
 * a list of styled runs (an optional draft prefix, the title, an optional conflicts suffix); [line2]
 * is the fully-composed muted metadata line without the branches (all one color, hence a plain
 * string); [sourceBranch] / [targetBranch] are carried separately so the renderer can paint them as
 * rounded chips (GLC-37) rather than inline text; [reviewerOverflow] is how many reviewers exceed the
 * avatars actually shown, i.e. the `+N` badge count (0 when none).
 *
 * All the composition rules live here — the draft/conflict decoration, the author display fallback,
 * the optional `group/project` prefix, the `iid`/date tail, and the reviewer overflow — so they are
 * unit-testable without Swing. See [mrRowPresentation].
 */
data class MrRowPresentation(
    val line1: List<MrRowSegment>,
    val line2: String,
    val sourceBranch: String,
    val targetBranch: String,
    val reviewerOverflow: Int,
)

/** Separator between the metadata parts of [MrRowPresentation.line2]. */
private const val META_SEPARATOR = " · " // " · "

/**
 * Builds the [MrRowPresentation] for [mr]. [showProject] mirrors the "All projects" mode: when set,
 * line 2 is prefixed with the MR's `group/project` label (via [projectLabelOf]) so identically-titled
 * MRs in different projects can be told apart. [relativeUpdatedAt] is the already-formatted relative
 * timestamp the renderer supplies (kept out of here so the composition is deterministic and
 * time-independent). [maxReviewerAvatars] is how many reviewers the row shows as avatars; reviewers
 * beyond that feed [MrRowPresentation.reviewerOverflow].
 *
 * [draftPrefix] and [conflictsSuffix] are injected (defaulting to their English forms) so the
 * function needs no message bundle to be tested; the renderer passes the localized variants.
 */
fun mrRowPresentation(
    mr: GitLabMergeRequest,
    showProject: Boolean,
    relativeUpdatedAt: String,
    maxReviewerAvatars: Int = 2,
    draftPrefix: String = "Draft: ",
    conflictsSuffix: String = " · conflicts",
): MrRowPresentation {
    val line1 = buildList {
        if (mr.draft) add(MrRowSegment(draftPrefix, MrSegmentStyle.WARNING))
        add(MrRowSegment(mr.title, MrSegmentStyle.NORMAL))
        if (mr.hasConflicts) add(MrRowSegment(conflictsSuffix, MrSegmentStyle.DANGER))
    }

    val parts = buildList {
        if (showProject) projectLabelOf(mr)?.let { add(it) }
        add("!${mr.iid}")
        add(mr.author.name.ifBlank { mr.author.username })
        add(relativeUpdatedAt)
    }

    val overflow = (mr.reviewers.size - maxReviewerAvatars).coerceAtLeast(0)

    return MrRowPresentation(
        line1 = line1,
        line2 = parts.joinToString(META_SEPARATOR),
        sourceBranch = mr.sourceBranch,
        targetBranch = mr.targetBranch,
        reviewerOverflow = overflow,
    )
}
