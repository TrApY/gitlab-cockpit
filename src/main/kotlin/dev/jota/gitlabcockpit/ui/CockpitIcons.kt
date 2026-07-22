package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import javax.swing.Icon

/**
 * Single source of the plugin's status icons and custom assets, the icon counterpart to
 * [CockpitTheme]: one status→icon mapping shared by the pipelines tree and any other status-aware
 * surface.
 */
object CockpitIcons {

    /**
     * Maps a GitLab job/stage status to its icon. A failed job that is [allowFailure] shows the
     * warning icon instead of the error one (GitLab's "allowed to fail"). A `running` status returns
     * a fresh [AnimatedIcon.Default] on every call so each spinner animates independently.
     *
     * Success and failure use the new-UI "circled" status set (GLC-38 / iter3 G19), matching the
     * reference plugin: [AllIcons.Status.Success] (green check in a circle) and [AllIcons.General.Error]
     * (red exclamation in a circle — the platform has no `AllIcons.Status.Error` in 2025.2, so the
     * circled `General.Error` is used). This is the single mapping every status-aware surface shares
     * (list rows, the pipelines strip/tree and the Overview pipeline line).
     */
    fun status(status: String, allowFailure: Boolean = false): Icon =
        if (status == "failed" && allowFailure) {
            AllIcons.General.Warning
        } else {
            when (status) {
                "success" -> AllIcons.Status.Success
                "failed" -> AllIcons.General.Error
                "running" -> AnimatedIcon.Default()
                "warning" -> AllIcons.General.Warning
                "manual" -> AllIcons.Actions.Pause
                "canceled" -> AllIcons.Actions.Suspend
                else -> AllIcons.RunConfigurations.TestNotRan // pending / created / skipped / unknown
            }
        }

    /** Tool window icon: a monochrome cockpit dial, loaded with its light/dark SVG pair. */
    val toolWindow: Icon = IconLoader.getIcon("/icons/toolwindow.svg", CockpitIcons::class.java)

    // --- Collaboration-tools icons (GLC-38 / iter3 A1-A3) -------------------------------------
    // Copied from the IntelliJ Platform's intellij.platform.collaborationTools module (© JetBrains,
    // Apache 2.0; see resources/icons/collab/README.md) and loaded from our own resources so the plugin
    // never depends on that v2 module being on the runtime classpath. Each val loads its light/dark SVG
    // pair through IconLoader (the `_dark` sibling is picked automatically on a dark theme).

    /** Placeholder avatar (Review.DefaultAvatar): a neutral head-and-shoulders silhouette. */
    val defaultAvatar: Icon = IconLoader.getIcon("/icons/collab/defaultAvatar.svg", CockpitIcons::class.java)

    /** Comments badge (CollaborationToolsIcons.Comment): a speech balloon, shown with a count. */
    val commentBadge: Icon = IconLoader.getIcon("/icons/collab/comment.svg", CockpitIcons::class.java)

    /** Branch chip marker (Review.BranchCurrent): the small branch glyph in front of the source chip. */
    val branchChip: Icon = IconLoader.getIcon("/icons/collab/branchCurrent.svg", CockpitIcons::class.java)

    /** Conflicts / non-mergeable marker (Review.NonMergeable): a circled cross. */
    val nonMergeable: Icon = IconLoader.getIcon("/icons/collab/nonMergeable.svg", CockpitIcons::class.java)

    /**
     * "Add reaction" affordance (CollaborationToolsIcons.AddEmoji): a smiley with a small `+`, the
     * resting state of a discussion card's hover reaction button (GLC-40 / iter4a).
     */
    val addEmoji: Icon = IconLoader.getIcon("/icons/collab/addEmoji.svg", CockpitIcons::class.java)

    /** Hovered state of [addEmoji] (CollaborationToolsIcons.AddEmojiHovered): the same glyph, less faded. */
    val addEmojiHovered: Icon = IconLoader.getIcon("/icons/collab/addEmojiHovered.svg", CockpitIcons::class.java)

    /**
     * The chain-link "copy link" glyph (GLC-43 C13): a real interlocking-links icon, unlike the
     * platform's `AllIcons.Ide.Link` (a double-chevron `»`, not a chain). Vendored from the bundled
     * Markdown plugin's New-UI `link.svg` so every copy-link / insert-link affordance — the MR toolbar,
     * a discussion card's hover row and the composer's link button — shares one consistent chain icon.
     */
    val copyLink: Icon = IconLoader.getIcon("/icons/collab/copyLink.svg", CockpitIcons::class.java)

    /**
     * Markdown format-bar icons (GLC-53): bold/italic/strike/code from the IntelliJ Markdown plugin's
     * New-UI editor-action set (vendored, Apache-2.0 — see icons/markdown/README.md); quote and
     * codeBlock are original companions drawn in the same style.
     */
    val formatBold: Icon = IconLoader.getIcon("/icons/markdown/bold.svg", CockpitIcons::class.java)
    val formatItalic: Icon = IconLoader.getIcon("/icons/markdown/italic.svg", CockpitIcons::class.java)
    val formatStrike: Icon = IconLoader.getIcon("/icons/markdown/strikeThrough.svg", CockpitIcons::class.java)
    val formatCode: Icon = IconLoader.getIcon("/icons/markdown/codeSpan.svg", CockpitIcons::class.java)
    val formatCodeBlock: Icon = IconLoader.getIcon("/icons/markdown/codeBlock.svg", CockpitIcons::class.java)
    val formatQuote: Icon = IconLoader.getIcon("/icons/markdown/quote.svg", CockpitIcons::class.java)

    /**
     * Maps a timeline [eventIconKey] to the concrete [Icon] a native event card paints (GLC-38 / iter3
     * B). The counterpart of the old HTML `<icon src>` mapping, now that the timeline is built from Swing
     * components instead of one HTML document. Every icon is verified to exist in the 2025.2 platform.
     */
    fun event(key: String): Icon = when (key) {
        "commit" -> AllIcons.Vcs.CommitNode
        "assign" -> AllIcons.General.User
        "review" -> AllIcons.General.User
        "approve" -> AllIcons.Status.Success
        "merge" -> AllIcons.Vcs.Merge
        else -> AllIcons.General.Note // state / generic
    }
}
