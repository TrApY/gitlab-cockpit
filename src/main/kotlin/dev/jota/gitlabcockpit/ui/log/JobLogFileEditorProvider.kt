package dev.jota.gitlabcockpit.ui.log

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Registers [JobLogFileEditor] as the editor for [JobLogVirtualFile] (GLC-43 A), so a CI job's
 * streaming log opens as a regular editor tab. It accepts **only** our synthetic file and hides the
 * default editor for it ([FileEditorPolicy.HIDE_DEFAULT_EDITOR]), so no empty plain-text tab is offered
 * alongside. [DumbAware] so a log can be opened while the IDE is indexing.
 */
class JobLogFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is JobLogVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        JobLogFileEditor(project, file as JobLogVirtualFile)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        private const val EDITOR_TYPE_ID = "gitlab-cockpit-job-log"
    }
}
