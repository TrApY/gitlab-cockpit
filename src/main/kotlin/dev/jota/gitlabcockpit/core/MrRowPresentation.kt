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
 * rounded chips (GLC-37) rather than inline text.
 *
 * All the composition rules live here — the draft/conflict decoration, the author display fallback,
 * the optional `group/project` prefix, the `iid`/date tail, and the avatar people — so they are
 * unit-testable without Swing. See [mrRowPresentation].
 *
 * [participants] are the row's right-column people: the MR's **deduplicated** participants (author,
 * assignees, reviewers — via [mrParticipants], the same dedup the Info header uses), in that order and
 * **uncapped** — the renderer shows the first `maxAvatars` as avatars (each with its own
 * name-and-roles tooltip, GLC-44) and folds the rest into the `+N` badge, whose tooltip lists the
 * hidden people; [avatarOverflow] is that `+N` count (0 when none). This replaces the old "author +
 * up to 2 reviewers, no dedup, no assignees" row so a user who is e.g. both author and reviewer no
 * longer shows twice and assignees are no longer dropped (GLC-43 C10).
 */
data class MrRowPresentation(
    val line1: List<MrRowSegment>,
    val line2: String,
    val sourceBranch: String,
    val targetBranch: String,
    val participants: List<MrParticipant>,
    val avatarOverflow: Int,
)

/** Separator between the metadata parts of [MrRowPresentation.line2]. */
private const val META_SEPARATOR = " · " // " · "

/**
 * Builds the [MrRowPresentation] for [mr]. [showProject] mirrors the "All projects" mode: when set,
 * line 2 is prefixed with the MR's `group/project` label (via [projectLabelOf]) so identically-titled
 * MRs in different projects can be told apart. [relativeUpdatedAt] is the already-formatted relative
 * timestamp the renderer supplies (kept out of here so the composition is deterministic and
 * time-independent). [maxAvatars] is how many participant avatars the row shows; participants beyond
 * that feed [MrRowPresentation.avatarOverflow].
 *
 * [draftPrefix] and [conflictsSuffix] are injected (defaulting to their English forms) so the
 * function needs no message bundle to be tested; the renderer passes the localized variants.
 * [maxAvatars] is how many participant avatars the row shows before the `+N` badge takes over.
 */
fun mrRowPresentation(
    mr: GitLabMergeRequest,
    showProject: Boolean,
    relativeUpdatedAt: String,
    maxAvatars: Int = 3,
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

    val participants = mrParticipants(mr.author, mr.assignees, mr.reviewers)

    return MrRowPresentation(
        line1 = line1,
        line2 = parts.joinToString(META_SEPARATOR),
        sourceBranch = mr.sourceBranch,
        targetBranch = mr.targetBranch,
        participants = participants,
        avatarOverflow = (participants.size - maxAvatars).coerceAtLeast(0),
    )
}

/**
 * Composes one participant's tooltip — `Alex Marin (Author, Reviewer)` — for the row avatar (or
 * hidden-overflow listing) that shows them (GLC-44: each avatar carries its own person, instead of the
 * old whole-row tooltip). The name falls back to the username when blank; the role words are injected
 * (defaulting to their English forms) so the function needs no message bundle to be tested. Pure and
 * platform-free.
 */
fun mrParticipantTooltip(
    participant: MrParticipant,
    authorLabel: String = "Author",
    assigneeLabel: String = "Assignee",
    reviewerLabel: String = "Reviewer",
): String {
    fun roleLabel(role: MrRole): String = when (role) {
        MrRole.AUTHOR -> authorLabel
        MrRole.ASSIGNEE -> assigneeLabel
        MrRole.REVIEWER -> reviewerLabel
    }
    val name = participant.user.name.ifBlank { participant.user.username }
    return "$name (${participant.roles.joinToString(", ") { roleLabel(it) }})"
}
