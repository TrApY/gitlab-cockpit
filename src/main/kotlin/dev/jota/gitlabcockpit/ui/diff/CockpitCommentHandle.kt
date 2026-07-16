package dev.jota.gitlabcockpit.ui.diff

import com.intellij.openapi.util.Key
import dev.jota.gitlabcockpit.core.ThreadSide

/**
 * Editor user-data that [CockpitDiffExtension] stamps on *each* side's editor of a Cockpit diff, so
 * the remappable "New comment at caret" action ([CockpitNewCommentAction]) can start a review thread
 * on the caret line — independently of whether the file already has threads.
 *
 * [side] is which diff side this editor is ([ThreadSide.OLD] = base/left, [ThreadSide.NEW] =
 * head/right); [openThread] opens the ChangesPanel new-thread dialog pre-filled with a side and a
 * **1-based** line. The action reads the handle from the focused editor, so it always knows the side
 * without inspecting the viewer.
 */
class CockpitCommentHandle(
    val side: ThreadSide,
    val openThread: (side: ThreadSide, line1Based: Int) -> Unit,
) {
    companion object {
        /** The editor user-data slot [CockpitNewCommentAction] looks for. */
        val KEY: Key<CockpitCommentHandle> = Key.create("dev.jota.gitlabcockpit.diff.commentHandle")
    }
}
