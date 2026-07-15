package dev.jota.gitlabcockpit.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings

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
 * Configurable IDE notifier for merge-request events (GLC-27). After each merge-request list load
 * ([onReady]) it raises one IDE balloon per enabled event type: pipeline finished, a new MR in the
 * user's scope, an MR state change, a new push and new comments. Which events fire is driven by the
 * checkboxes in [GitLabCockpitSettings] — the master [GitLabCockpitSettings.notificationsEnabled]
 * flag gates everything, and each event type has its own flag.
 *
 * All network work happens inside the service (off the EDT); the notification bus itself is safe to
 * call from any thread.
 */
class MrNotificationsWatcher(
    private val project: Project,
    private val service: CockpitProjectService,
) {

    /** Runs one watcher pass for [ready] and posts an IDE balloon per enabled, detected event. */
    suspend fun onReady(ready: CockpitState.Ready) {
        val settings = GitLabCockpitSettings.getInstance()
        // Master switch off → no-op: skip both the network pipeline check and the local MR diff.
        if (!settings.notificationsEnabled) return

        if (settings.notifyPipeline) {
            for (change in service.detectPipelineStatusChanges(ready)) notifyPipeline(change)
        }

        // Always advance the MR snapshot (even when every event flag is off) so toggling a flag on
        // later never replays historical changes; then post only the enabled ones.
        for (event in service.detectScopeMrEvents(ready)) {
            val enabled = when (event) {
                is MrEvent.NewMr -> settings.notifyNewMr
                is MrEvent.StateChanged -> settings.notifyMrState
                is MrEvent.NewPush -> settings.notifyPush
                is MrEvent.NewComments -> settings.notifyComments
            }
            if (enabled) notifyMrEvent(event)
        }
    }

    private fun notifyPipeline(change: PipelineStatusChange) {
        val success = change.status == "success"
        val title = CockpitBundle.message("notification.pipeline.title", change.mr.iid, change.mr.title)
        val content = CockpitBundle.message(
            if (success) "notification.pipeline.success" else "notification.pipeline.failed",
        )
        val type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
        post(title, content, type)
    }

    private fun notifyMrEvent(event: MrEvent) {
        val mr = event.mr
        val line = CockpitBundle.message("notification.mr.line", mr.iid, mr.title)
        val (title, content) = when (event) {
            is MrEvent.NewMr ->
                CockpitBundle.message("notification.newMr.title") to line
            is MrEvent.StateChanged ->
                CockpitBundle.message("notification.mrState.title", event.new) to line
            is MrEvent.NewPush ->
                CockpitBundle.message("notification.push.title") to line
            is MrEvent.NewComments ->
                CockpitBundle.message("notification.comments.title") to
                    CockpitBundle.message("notification.comments.content", mr.iid, mr.title, event.count)
        }
        post(title, content, NotificationType.INFORMATION)
    }

    private fun post(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(COCKPIT_NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
