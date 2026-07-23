package dev.jota.gitlabcockpit.core

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.ApprovedBy
import dev.jota.gitlabcockpit.api.GitLabBridge
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import dev.jota.gitlabcockpit.ui.CockpitIcons
import dev.jota.gitlabcockpit.ui.CockpitNavigation
import javax.swing.Icon

/** Id of the notification group declared in `plugin.xml` (`Cockpit for GitLab`, balloon display). */
const val COCKPIT_NOTIFICATION_GROUP = "Cockpit for GitLab"

/**
 * Id of the sticky notification group declared in `plugin.xml` (`Cockpit for GitLab (Sticky)`,
 * STICKY_BALLOON display). Same visuals as [COCKPIT_NOTIFICATION_GROUP], but its balloons stay on
 * screen until the user dismisses them. Used when the opt-in sticky setting is on (GLC-30).
 */
const val COCKPIT_STICKY_NOTIFICATION_GROUP = "Cockpit for GitLab (Sticky)"

/** Terminal pipeline statuses whose arrival is worth an IDE notification. */
private val NOTIFY_STATUSES = setOf("success", "failed")

/**
 * Pure selection of which notification group an event balloon should use: the sticky group when
 * [sticky] is on, the auto-hiding one otherwise. There are two physical groups because a group's
 * display type (BALLOON vs STICKY_BALLOON) is fixed when it is registered in `plugin.xml` and is
 * not mutable at runtime — so honoring the toggle means routing each post to the right group id
 * rather than reconfiguring a single group (GLC-30).
 */
fun notificationGroupFor(sticky: Boolean): String =
    if (sticky) COCKPIT_STICKY_NOTIFICATION_GROUP else COCKPIT_NOTIFICATION_GROUP

/**
 * A detected pipeline transition worth notifying: the [mr] whose latest pipeline just reached a
 * terminal [status] (`success` or `failed`).
 */
data class PipelineStatusChange(val mr: GitLabMergeRequest, val status: String)

/**
 * A detected downstream (bridge-triggered) pipeline transition worth notifying (GLC-61): the [mr]
 * whose latest pipeline triggered a downstream pipeline via the bridge named [bridgeName] which just
 * reached a terminal [status] (`success` or `failed`). Covers the real case of an MR pipeline that
 * goes `success` while its `release-management` downstream fails *afterwards* — a transition the
 * upstream pipeline watcher alone never sees.
 */
data class DownstreamStatusChange(val mr: GitLabMergeRequest, val bridgeName: String, val status: String)

/**
 * The full outcome of one pipeline watcher pass (GLC-61): the upstream [pipelines] transitions (as
 * before) plus the [downstreams] — the terminal transitions of the downstream pipelines the watched
 * MRs' bridges triggered. Both are gathered in the single pass so no extra network round of MR
 * pipeline fetches is spent (the bridges are fetched off the pipeline id already resolved per MR).
 */
data class PipelineWatchResult(
    val pipelines: List<PipelineStatusChange>,
    val downstreams: List<DownstreamStatusChange>,
)

/**
 * Pure delta for the downstream watcher (GLC-61): given [prev] (the last known status of this
 * [bridge]'s downstream pipeline, `null` on its first observation) and the [bridge] itself, returns
 * the [DownstreamStatusChange] worth notifying for [mr], or `null`. Reuses [shouldNotify] so it obeys
 * exactly the same rules as the upstream watcher:
 * - a bridge that has not triggered a downstream yet ([GitLabBridge.downstream] == null) yields nothing;
 * - the first observation ([prev] == null) only memorizes (in the caller) and yields nothing;
 * - an unchanged status or a non-terminal one yields nothing;
 * - only a *changed* transition into `success` / `failed` with a known previous status produces a change.
 */
fun downstreamChange(prev: String?, bridge: GitLabBridge, mr: GitLabMergeRequest): DownstreamStatusChange? {
    val downstream = bridge.downstream ?: return null
    return if (shouldNotify(prev, downstream.status)) {
        DownstreamStatusChange(mr, bridge.name, downstream.status)
    } else {
        null
    }
}

/**
 * A detected approval gain worth notifying (GLC-55): the [mr] that just gained [newApprovers] — the
 * users whose approval appeared since the previous watcher pass. Only genuinely new approvers are ever
 * carried here; an unapprove (an approver dropping off) never produces an [ApprovalChange].
 */
data class ApprovalChange(val mr: GitLabMergeRequest, val newApprovers: List<GitLabUser>)

/**
 * Pure delta for the approval watcher (GLC-55): given the [prev] snapshot of approver ids (`null` on
 * the very first observation of an MR) and the MR's [current] approvers, returns the users whose ids
 * are NEW since the last pass — the ones worth an "Approved by X" balloon. Mirrors the spirit of
 * [shouldNotify] / [detectMrEvents]:
 * - `prev == null` (first observation) yields nothing: that pass only memorizes the baseline.
 * - Only approvers absent from [prev] are returned; an approver that disappeared (an unapprove) yields
 *   nothing — it simply drops out of the next snapshot.
 * - Order follows [current], so the balloon lists approvers in GitLab's own order.
 */
fun newApprovers(prev: Set<Long>?, current: List<ApprovedBy>): List<GitLabUser> {
    if (prev == null) return emptyList()
    return current.map { it.user }.filter { it.id !in prev }
}

/**
 * Pure decision for the pipeline watcher. Notify only when [new] is a terminal status we care about
 * (`success` / `failed`), a previous status was already known ([prev] non-null) and it actually
 * changed. The first time an MR's pipeline is observed ([prev] == null) nothing is notified — that
 * pass only memorizes; an unchanged status or a non-terminal one never notifies either.
 */
fun shouldNotify(prev: String?, new: String): Boolean =
    new in NOTIFY_STATUSES && prev != null && prev != new

/**
 * An event balloon's rendered text: [title] is the bold header, [content] the body line. Both are the
 * final display strings, with every dynamic merge-request datum (the MR title) already HTML-escaped —
 * the platform renders both as HTML, so an unescaped `<`, `>` or `&` in a title would corrupt the
 * balloon or silently drop text (GLC-54).
 */
data class NotificationText(val title: String, val content: String)

/**
 * Resolves a bundle key and its MessageFormat arguments to a display string. Injected into the pure
 * text builders below so they stay platform-free and unit-testable without an IDE `Application`:
 * production binds it to [CockpitBundle], a test binds it to a raw `.properties`-backed resolver.
 */
fun interface NotificationMessages {
    fun format(key: String, params: List<Any>): String
}

/**
 * Escapes a piece of dynamic, user-controlled data a notification interpolates — an MR title (GLC-54)
 * or an approver's display name (GLC-55) — so it is safe inside the HTML the balloon renders. The
 * platform renders both the title and body as HTML, so an unescaped `<`, `>` or `&` would corrupt the
 * balloon or silently drop text: `Fix <T> handling` becomes `Fix &lt;T&gt; handling` and `Q&A Bot`
 * becomes `Q&amp;A Bot`.
 */
private fun escapeHtml(text: String): String = StringUtil.escapeXmlEntities(text)

/**
 * Builds a pipeline event's balloon text (GLC-54). Unified hierarchy: the title is the outcome
 * ("Pipeline succeeded" / "Pipeline failed") and the body is the MR line — the same line the other MR
 * events use as their body — so a pipeline balloon reads like every other event. Pure; escapes the MR
 * title before interpolating it.
 */
fun pipelineNotificationText(change: PipelineStatusChange, messages: NotificationMessages): NotificationText {
    val titleKey =
        if (change.status == "success") "notification.pipeline.success" else "notification.pipeline.failed"
    return NotificationText(
        title = messages.format(titleKey, emptyList()),
        content = messages.format("notification.mr.line", listOf(change.mr.iid, escapeHtml(change.mr.title))),
    )
}

/**
 * Builds a downstream pipeline event's balloon text (GLC-61). Same unified hierarchy as the upstream
 * pipeline balloon: the title is the outcome carrying the bridge's name ("Downstream pipeline failed —
 * release-management") and the body is the MR line — the same line every other event uses as its body.
 * Pure; escapes BOTH the bridge name and the MR title before interpolating them, since a bridge name is
 * just as user-controlled as a title (a name like `Q&A Bot` would otherwise corrupt the balloon HTML).
 */
fun downstreamNotificationText(change: DownstreamStatusChange, messages: NotificationMessages): NotificationText {
    val titleKey =
        if (change.status == "success") "notification.downstream.success" else "notification.downstream.failed"
    return NotificationText(
        title = messages.format(titleKey, listOf(escapeHtml(change.bridgeName))),
        content = messages.format("notification.mr.line", listOf(change.mr.iid, escapeHtml(change.mr.title))),
    )
}

/**
 * Builds an approval event's balloon text (GLC-55). Unified hierarchy like the other events: the title
 * lists the new approvers ("Approved by Alice, Bob") and the body is the MR line — the same line every
 * other event uses as its body — so an approval balloon reads like the rest. Pure; escapes BOTH the
 * approver names and the MR title before interpolating them, since a display name is just as
 * user-controlled as a title (a name like `Q&A Bot` would otherwise corrupt the balloon HTML).
 */
fun approvalNotificationText(change: ApprovalChange, messages: NotificationMessages): NotificationText {
    val names = change.newApprovers.joinToString(", ") { escapeHtml(it.name) }
    return NotificationText(
        title = messages.format("notification.approval.title", listOf(names)),
        content = messages.format("notification.mr.line", listOf(change.mr.iid, escapeHtml(change.mr.title))),
    )
}

/**
 * Builds an [MrEvent]'s balloon text (GLC-54): a per-variant title over the MR line (or, for new
 * comments, the count line). Pure; escapes the MR title before interpolating it.
 */
fun mrEventNotificationText(event: MrEvent, messages: NotificationMessages): NotificationText {
    val mr = event.mr
    val safeTitle = escapeHtml(mr.title)
    val line = messages.format("notification.mr.line", listOf(mr.iid, safeTitle))
    return when (event) {
        is MrEvent.NewMr ->
            NotificationText(messages.format("notification.newMr.title", emptyList()), line)
        is MrEvent.StateChanged ->
            NotificationText(messages.format("notification.mrState.title", listOf(event.new)), line)
        is MrEvent.NewPush ->
            NotificationText(messages.format("notification.push.title", emptyList()), line)
        is MrEvent.NewComments ->
            NotificationText(
                messages.format("notification.comments.title", emptyList()),
                messages.format("notification.comments.content", listOf(mr.iid, safeTitle, event.count)),
            )
    }
}

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
            val result = service.detectPipelineStatusChanges(ready)
            for (change in result.pipelines) notifyPipeline(change)
            for (change in result.downstreams) notifyDownstream(change)
        }

        if (settings.notifyApprovals) {
            for (change in service.detectApprovalChanges(ready)) notifyApproval(change)
        }

        // Always advance the MR snapshot (even when every event flag is off) so toggling a flag on
        // later never replays historical changes; then post only the enabled ones.
        for (event in service.detectScopeMrEvents(ready)) {
            val enabled = when (event) {
                // Only an OPEN merge request is news when it appears — a merged/closed one entering
                // the scope is filter/scope motion (e.g. the state filter switched to ALL), GLC-57.
                is MrEvent.NewMr -> settings.notifyNewMr && event.mr.state == "opened"
                is MrEvent.StateChanged -> settings.notifyMrState
                is MrEvent.NewPush -> settings.notifyPush
                is MrEvent.NewComments -> settings.notifyComments
            }
            if (enabled) notifyMrEvent(event)
        }
    }

    /** Binds the pure text builders to the plugin resource bundle (needs an IDE `Application`). */
    private val messages = NotificationMessages { key, params ->
        CockpitBundle.message(key, *params.toTypedArray())
    }

    private fun notifyPipeline(change: PipelineStatusChange) {
        val success = change.status == "success"
        val text = pipelineNotificationText(change, messages)
        val type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
        post(text, type, CockpitIcons.status(if (success) "success" else "failed"), change.mr)
    }

    private fun notifyDownstream(change: DownstreamStatusChange) {
        val success = change.status == "success"
        val text = downstreamNotificationText(change, messages)
        val type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
        post(text, type, CockpitIcons.status(change.status), change.mr)
    }

    private fun notifyApproval(change: ApprovalChange) {
        val text = approvalNotificationText(change, messages)
        post(text, NotificationType.INFORMATION, CockpitIcons.approval, change.mr)
    }

    private fun notifyMrEvent(event: MrEvent) {
        val text = mrEventNotificationText(event, messages)
        val icon = when (event) {
            is MrEvent.NewComments -> CockpitIcons.commentBadge
            is MrEvent.NewPush -> CockpitIcons.branchChip
            is MrEvent.NewMr, is MrEvent.StateChanged -> CockpitIcons.toolWindow
        }
        post(text, NotificationType.INFORMATION, icon, event.mr)
    }

    /**
     * Posts one balloon for [mr]'s [text], tagged with [icon] and carrying the two shared actions:
     * "Open in Cockpit" (activates the tool window and opens the MR's tab, even from the closed-window
     * poller — see [CockpitNavigation.openMr]) and "Open in GitLab" (the MR's web page). Both expire the
     * balloon when triggered.
     */
    private fun post(text: NotificationText, type: NotificationType, icon: Icon, mr: GitLabMergeRequest) {
        // Resolve the group per post (never cache): reading the setting on each event makes a
        // sticky-toggle change take effect on the next notification without restarting anything.
        val group = notificationGroupFor(GitLabCockpitSettings.getInstance().stickyNotifications)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(group)
            .createNotification(text.title, text.content, type)
            .setIcon(icon)
            .addAction(
                NotificationAction.createSimpleExpiring(
                    CockpitBundle.message("notification.action.openCockpit"),
                ) { CockpitNavigation.openMr(project, mr) },
            )
            .addAction(
                NotificationAction.createSimpleExpiring(
                    CockpitBundle.message("notification.action.openBrowser"),
                ) { BrowserUtil.browse(mr.webUrl) },
            )
            .notify(project)
    }
}
