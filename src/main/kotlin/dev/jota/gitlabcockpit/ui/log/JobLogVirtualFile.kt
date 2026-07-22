package dev.jota.gitlabcockpit.ui.log

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile
import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.core.MrRef

/**
 * A read-only, in-memory [LightVirtualFile] that stands in for one CI job's streaming log so it can be
 * opened as an **editor tab** (GLC-43 A) instead of a modal dialog. The tab's content is produced by
 * [JobLogFileEditor], which wraps the existing streaming
 * [dev.jota.gitlabcockpit.ui.JobLogConsole]; this file only carries the identity and the metadata that
 * editor needs.
 *
 * Its name is `<jobName> #<jobId>.gitlab-log`; the `.gitlab-log` extension is what
 * [JobLogTabTitleProvider] strips for a clean tab title and what keeps the file out of the plain-text
 * editors' way (the provider also declares [com.intellij.openapi.fileEditor.FileEditorPolicy.HIDE_DEFAULT_EDITOR]).
 *
 * **Identity is by job**, not by instance: [equals] / [hashCode] compare [projectId] + [jobId], so
 * asking [com.intellij.openapi.fileEditor.FileEditorManager.openFile] to open the same job twice reuses
 * the already-open tab (the manager keys its open composites by `VirtualFile` equality) instead of
 * stacking duplicates.
 *
 * [status] is mutable: it starts at the job's status when the tab opens and is advanced to the final
 * status by [JobLogFileEditor] when streaming ends, so [JobLogIconProvider] can paint the tab icon for
 * the current state (a [com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.refreshIcons] call then
 * re-queries it).
 */
class JobLogVirtualFile(
    val projectId: Long,
    val job: GitLabJob,
    val mrRef: MrRef?,
) : LightVirtualFile("${job.name} #${job.id}.gitlab-log", PlainTextFileType.INSTANCE, "") {

    /** The job id this tab is bound to; the stable half of the file's identity. */
    val jobId: Long get() = job.id

    /** The tab's presentable title without the `.gitlab-log` extension: `<jobName> #<jobId>`. */
    val displayName: String get() = "${job.name} #${job.id}"

    /**
     * The job's *current* status: the open-time status, advanced to the terminal one when streaming
     * ends. Read by [JobLogIconProvider] to pick the tab icon; volatile because it is written from the
     * console's EDT status callback and read during icon refreshes.
     */
    @Volatile
    var status: String = job.status

    init {
        isWritable = false
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is JobLogVirtualFile && other.projectId == projectId && other.jobId == jobId)

    override fun hashCode(): Int = 31 * projectId.hashCode() + jobId.hashCode()
}
