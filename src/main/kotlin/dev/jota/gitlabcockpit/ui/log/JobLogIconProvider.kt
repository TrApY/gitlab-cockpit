package dev.jota.gitlabcockpit.ui.log

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.jota.gitlabcockpit.ui.CockpitIcons
import javax.swing.Icon

/**
 * Paints a [JobLogVirtualFile] tab's icon from the job's current status (GLC-43 A), reusing the one
 * status→icon mapping every cockpit surface shares ([CockpitIcons.status]). Returning null for any
 * other file leaves its icon to the platform. The icon is re-queried whenever
 * [com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.refreshIcons] runs, which
 * [JobLogFileEditor] triggers once the job reaches its terminal status — so the tab's spinner/queued
 * glyph flips to the final success/failed one.
 */
class JobLogIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        (file as? JobLogVirtualFile)?.let { CockpitIcons.status(it.status) }
}
