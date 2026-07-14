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
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** Mutable list backing the persisted state; mutating entries updates the stored settings. */
    val instances: MutableList<InstanceState>
        get() = state.instances

    companion object {
        fun getInstance(): GitLabCockpitSettings = service()
    }
}
