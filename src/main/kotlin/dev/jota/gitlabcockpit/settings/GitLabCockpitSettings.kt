package dev.jota.gitlabcockpit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * A single configured GitLab instance. The token is intentionally NOT part of this state:
 * it lives in [TokenStore] (PasswordSafe) and never touches the XML on disk.
 */
data class InstanceState(
    var name: String = "",
    var baseUrl: String = "",
)

/**
 * Application-level persistent settings. Modeled as a list of instances so multi-instance
 * support can arrive without a state migration, even though the V1 UI edits only the first entry.
 */
@Service(Service.Level.APP)
@State(
    name = "GitLabCockpitSettings",
    storages = [Storage("gitlabCockpit.xml")],
)
class GitLabCockpitSettings : PersistentStateComponent<GitLabCockpitSettings.State> {

    class State {
        var instances: MutableList<InstanceState> = mutableListOf()

        /**
         * Remembered "Squash commits" merge option, as a tri-state string (`"unset"`/`"true"`/
         * `"false"`); `"unset"` means "use the MR's own GitLab default". Persisted as a plain string so
         * the XML stays forward-compatible; read/written through [GitLabCockpitSettings.mergeSquash].
         */
        var mergeSquash: String = UNSET

        /** Remembered "Delete source branch" merge option; same tri-state encoding as [mergeSquash]. */
        var mergeDeleteSourceBranch: String = UNSET

        /**
         * Master switch for IDE notifications. When `false` no balloon is ever raised, whatever the
         * per-event flags below say. Defaults to `true` so existing users keep the pipeline balloons.
         */
        var notificationsEnabled: Boolean = true

        /** Raise a balloon when a watched MR's pipeline finishes (success/failed). Status-quo default. */
        var notifyPipeline: Boolean = true

        /** Raise a balloon when a new MR appears in the current user's scope (author/assignee/reviewer). */
        var notifyNewMr: Boolean = false

        /** Raise a balloon when a watched MR changes state (merged/closed/reopened). */
        var notifyMrState: Boolean = false

        /** Raise a balloon when new commits are pushed to a watched MR (its head SHA changes). */
        var notifyPush: Boolean = false

        /** Raise a balloon when new comments are added to a watched MR (its note count grows). */
        var notifyComments: Boolean = false

        /** Raise a balloon when a watched MR gains a new approver (someone approves it). GLC-55. */
        var notifyApprovals: Boolean = false

        /**
         * When `true`, event balloons use the STICKY_BALLOON group so they stay on screen until the
         * user dismisses them instead of auto-hiding (GLC-30). Defaults to `false` — the status-quo
         * auto-hiding behavior for existing users. Applies to the watcher's event notifications only.
         */
        var stickyNotifications: Boolean = false

        /**
         * How often (minutes) the background poller checks for events while the tool window is closed
         * (GLC-28). Only 1/2/5 are offered in the UI; re-read every tick so a change takes effect
         * without restarting the loop. Defaults to 2.
         */
        var backgroundPollMinutes: Int = 2

        /**
         * When `true`, the notification scope widens to every MR of the current filter instead of only
         * the user's role scope (+ watched MRs). Defaults to `false` (GLC-28).
         */
        var notifyScopeAllFiltered: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** Mutable list backing the persisted state; mutating entries updates the stored settings. */
    val instances: MutableList<InstanceState>
        get() = state.instances

    /**
     * Remembered "Squash commits" merge option: `true`/`false` when the user pinned it, or `null` to
     * defer to the MR's own GitLab default. Backed by [State.mergeSquash]'s tri-state string.
     */
    var mergeSquash: Boolean?
        get() = decodeTriState(state.mergeSquash)
        set(value) { state.mergeSquash = encodeTriState(value) }

    /** Remembered "Delete source branch" merge option; same semantics as [mergeSquash]. */
    var mergeDeleteSourceBranch: Boolean?
        get() = decodeTriState(state.mergeDeleteSourceBranch)
        set(value) { state.mergeDeleteSourceBranch = encodeTriState(value) }

    /** Master switch for IDE notifications; see [State.notificationsEnabled]. */
    var notificationsEnabled: Boolean
        get() = state.notificationsEnabled
        set(value) { state.notificationsEnabled = value }

    /** Notify on pipeline finished (success/failed); see [State.notifyPipeline]. */
    var notifyPipeline: Boolean
        get() = state.notifyPipeline
        set(value) { state.notifyPipeline = value }

    /** Notify on a new MR in scope; see [State.notifyNewMr]. */
    var notifyNewMr: Boolean
        get() = state.notifyNewMr
        set(value) { state.notifyNewMr = value }

    /** Notify on MR state changes; see [State.notifyMrState]. */
    var notifyMrState: Boolean
        get() = state.notifyMrState
        set(value) { state.notifyMrState = value }

    /** Notify on new commits pushed; see [State.notifyPush]. */
    var notifyPush: Boolean
        get() = state.notifyPush
        set(value) { state.notifyPush = value }

    /** Notify on new comments; see [State.notifyComments]. */
    var notifyComments: Boolean
        get() = state.notifyComments
        set(value) { state.notifyComments = value }

    /** Notify on a new approver of a watched MR; see [State.notifyApprovals]. */
    var notifyApprovals: Boolean
        get() = state.notifyApprovals
        set(value) { state.notifyApprovals = value }

    /** Keep event balloons on screen until dismissed (STICKY_BALLOON); see [State.stickyNotifications]. */
    var stickyNotifications: Boolean
        get() = state.stickyNotifications
        set(value) { state.stickyNotifications = value }

    /** Background poll interval in minutes; see [State.backgroundPollMinutes]. */
    var backgroundPollMinutes: Int
        get() = state.backgroundPollMinutes
        set(value) { state.backgroundPollMinutes = value }

    /** Widen the notification scope to every MR of the current filter; see [State.notifyScopeAllFiltered]. */
    var notifyScopeAllFiltered: Boolean
        get() = state.notifyScopeAllFiltered
        set(value) { state.notifyScopeAllFiltered = value }

    private fun decodeTriState(raw: String): Boolean? = when (raw) {
        TRUE -> true
        FALSE -> false
        else -> null
    }

    private fun encodeTriState(value: Boolean?): String = when (value) {
        true -> TRUE
        false -> FALSE
        null -> UNSET
    }

    companion object {
        fun getInstance(): GitLabCockpitSettings = service()

        private const val UNSET = "unset"
        private const val TRUE = "true"
        private const val FALSE = "false"
    }
}
