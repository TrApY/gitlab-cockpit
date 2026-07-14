package dev.jota.gitlabcockpit.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/** Id of the notification group declared in `plugin.xml` (`Cockpit for GitLab`, balloon display). */
const val COCKPIT_NOTIFICATION_GROUP = "Cockpit for GitLab"

/** Terminal pipeline statuses whose arrival is worth an IDE notification. */
private val NOTIFY_STATUSES = setOf("success", "failed")

/**
 * A detected pipeline transition worth notifying: the [mr] whose latest pipeline just reached a
 * terminal [status] (`success` or `failed`).
 */
data class PipelineStatusChange(val mr: GitLabMergeRequest, val status: String)

/**
 * Pure decision for the pipeline watcher. Notify only when [new] is a terminal status we care about
 * (`success` / `failed`), a previous status was already known ([prev] non-null) and it actually
 * changed. The first time an MR's pipeline is observed ([prev] == null) nothing is notified — that
 * pass only memorizes; an unchanged status or a non-terminal one never notifies either.
 */
fun shouldNotify(prev: String?, new: String): Boolean =
    new in NOTIFY_STATUSES && prev != null && prev != new

/**
 * Lightweight pipeline notifier. After each merge-request list load ([onReady]) it asks the service
 * for the pipeline status transitions of the current user's MRs and fires one IDE balloon per
 * change. All network work happens inside the service (off the EDT); the notification bus itself is
 * safe to call from any thread.
 */
class PipelineWatcher(
    private val project: Project,
    private val service: CockpitProjectService,
) {

    /** Runs one watcher pass for [ready] and posts an IDE notification per detected change. */
    suspend fun onReady(ready: CockpitState.Ready) {
        for (change in service.detectPipelineStatusChanges(ready)) notify(change)
    }

    private fun notify(change: PipelineStatusChange) {
        val success = change.status == "success"
        val title = CockpitBundle.message("notification.pipeline.title", change.mr.iid, change.mr.title)
        val content = CockpitBundle.message(
            if (success) "notification.pipeline.success" else "notification.pipeline.failed",
        )
        val type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
        NotificationGroupManager.getInstance()
            .getNotificationGroup(COCKPIT_NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
