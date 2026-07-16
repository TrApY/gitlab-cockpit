package dev.jota.gitlabcockpit.ui.diff

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor

/**
 * The keyboard-first diff-review actions (GLC-29). All three are registered in `plugin.xml`, so they
 * are remappable and appear in the diff editor's context menu; each borrows a platform gesture via
 * `use-shortcut-of` and drives an editor a [CockpitDiffExtension] has stamped, staying disabled
 * everywhere else.
 *
 * They all update on the EDT because they read editor state (user-data, caret) that must be touched
 * from the EDT.
 */

/**
 * "New comment at caret" (`Cockpit.Diff.NewComment`, gesture inherited from
 * `Code.Review.Editor.New.Comment` = Ctrl+Shift+X): opens the new-thread dialog pre-filled with the
 * caret's side and 1-based line. Enabled only inside a Cockpit diff editor — one carrying a
 * [CockpitCommentHandle].
 */
class CockpitNewCommentAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = handleOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = editorOf(e) ?: return
        val handle = editor.getUserData(CockpitCommentHandle.KEY) ?: return
        val line = editor.caretModel.logicalPosition.line + 1
        handle.openThread(handle.side, line)
    }

    private fun handleOf(e: AnActionEvent): CockpitCommentHandle? =
        editorOf(e)?.getUserData(CockpitCommentHandle.KEY)
}

/**
 * "Next review thread" (`Cockpit.Diff.NextThread`, gesture inherited from `NextOccurence` =
 * Ctrl+Alt+Down): jumps the caret to the next review thread, cycling. Enabled only when the focused
 * editor carries a [CockpitThreadNavigator] (a file with threads).
 */
class CockpitNextThreadAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = navigatorOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        navigatorOf(e)?.navigate?.invoke(true)
    }
}

/**
 * "Previous review thread" (`Cockpit.Diff.PreviousThread`, gesture inherited from `PreviousOccurence`
 * = Ctrl+Alt+Up): the [CockpitNextThreadAction] counterpart, jumping to the previous thread.
 */
class CockpitPreviousThreadAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = navigatorOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        navigatorOf(e)?.navigate?.invoke(false)
    }
}

/** The editor the action targets: the diff's current editor, falling back to the generic one. */
private fun editorOf(e: AnActionEvent): Editor? =
    e.getData(DiffDataKeys.CURRENT_EDITOR) ?: e.getData(CommonDataKeys.EDITOR)

/** The thread navigator stamped on the target editor, or null when there is nothing to navigate. */
private fun navigatorOf(e: AnActionEvent): CockpitThreadNavigator? =
    editorOf(e)?.getUserData(CockpitThreadNavigator.KEY)
