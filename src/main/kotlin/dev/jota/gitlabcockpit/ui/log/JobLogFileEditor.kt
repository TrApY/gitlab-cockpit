package dev.jota.gitlabcockpit.ui.log

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.ui.JobLogConsole
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/**
 * The editor behind a [JobLogVirtualFile] tab (GLC-43 A). It **reuses** the existing streaming
 * [JobLogConsole] untouched — the console is registered on this editor as its parent disposable and
 * [JobLogConsole.start] is called once at construction, so the job's trace loads and streams exactly as
 * it did inside the old modal dialog; closing the tab disposes this editor, which cancels the console's
 * streaming job.
 *
 * The tab's icon tracks the job status: it starts at [JobLogVirtualFile.status] (rendered by
 * [JobLogIconProvider]) and, when the console reports the final status through its `onStatusChange`
 * callback, this editor advances [JobLogVirtualFile.status] and asks [FileEditorManagerEx.refreshIcons]
 * to re-query it, so the spinner/queued glyph flips to the terminal success/failed one. The tab title
 * ([JobLogTabTitleProvider]) stays `<jobName> #<jobId>` throughout — the status lives in the icon.
 *
 * Soft-wrap is intentionally **not** forced on: [JobLogConsole] fully encapsulates its
 * [com.intellij.execution.ui.ConsoleView] (no handle is exposed and its streaming logic must not be
 * touched), so there is no trivial way to flip the editor setting from here; the console's own
 * horizontal scrollbar covers long lines.
 */
class JobLogFileEditor(
    private val project: Project,
    private val file: JobLogVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val changeSupport = PropertyChangeSupport(this)

    private val console = JobLogConsole(
        project,
        CockpitProjectService.getInstance(project),
        file.projectId,
        file.job,
        this,
    ) { status -> onJobFinished(status) }

    init {
        console.start()
    }

    /**
     * EDT (the console marshals its status callback there). Records the terminal [status] on the file
     * and refreshes every open tab's icon so this tab's [JobLogIconProvider] glyph flips to the final
     * state.
     */
    private fun onJobFinished(status: String) {
        file.status = status
        FileEditorManagerEx.getInstanceEx(project).refreshIcons()
    }

    override fun getComponent(): JComponent = console.component

    override fun getPreferredFocusedComponent(): JComponent = console.component

    override fun getName(): String = CockpitBundle.message("log.editor.name")

    override fun getFile(): VirtualFile = file

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.removePropertyChangeListener(listener)

    /** Console (and its streaming job) are disposed as this editor's registered child. */
    override fun dispose() {}
}
