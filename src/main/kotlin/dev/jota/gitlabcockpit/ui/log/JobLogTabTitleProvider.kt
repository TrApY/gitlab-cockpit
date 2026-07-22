package dev.jota.gitlabcockpit.ui.log

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Presents a [JobLogVirtualFile] tab as `<jobName> #<jobId>` (GLC-43 A), dropping the synthetic
 * `.gitlab-log` extension that the file name carries for identity/typing. Returns null for any other
 * file so the platform's default title is used everywhere else.
 */
class JobLogTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? JobLogVirtualFile)?.displayName
}
