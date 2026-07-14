package dev.jota.gitlabcockpit.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabApiClient
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabProject
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.MergeRequestQuery
import dev.jota.gitlabcockpit.api.MergeRequestUpdate
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import dev.jota.gitlabcockpit.settings.TokenStore
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** Snapshot the tool window renders. Every terminal outcome of a load maps to one of these. */
sealed interface CockpitState {
    /** No GitLab instance configured in Settings. */
    object NotConfigured : CockpitState

    /** An instance is configured, but no git remote of this project matches its host. */
    object NoGitLabRemote : CockpitState

    /** A load is in progress. */
    object Loading : CockpitState

    /** Merge requests loaded successfully. */
    data class Ready(
        val mrs: List<GitLabMergeRequest>,
        val currentUser: GitLabUser,
    ) : CockpitState

    /** Something failed; [message] is already localized and user-facing. */
    data class Error(val message: String) : CockpitState
}

/**
 * Project-level coordinator for the cockpit. Resolves the GitLab project from the project's git
 * remotes, caches the resolved project and current user (invalidated via [refresh]), and loads
 * merge requests for a given filter. All work runs on the injected [coroutineScope]
 * ([kotlinx.coroutines.Dispatchers.Default]) — never on the EDT; the caller marshals results to
 * the UI thread.
 */
@Service(Service.Level.PROJECT)
class CockpitProjectService(
    private val project: Project,
    val coroutineScope: CoroutineScope,
) {

    private data class CachedApprovals(val updatedAt: String, val approvals: GitLabApprovals)

    @Volatile
    private var cachedProject: GitLabProject? = null

    @Volatile
    private var cachedUser: GitLabUser? = null

    /** Project members, loaded lazily by [getMembers] and dropped on [refresh]. */
    @Volatile
    private var cachedMembers: List<GitLabUser>? = null

    /** Keyed by MR iid; invalidated per-MR when its `updated_at` changes. */
    private val approvalsCache = ConcurrentHashMap<Long, CachedApprovals>()

    /** Drops all cached data so the next [loadMergeRequests] re-resolves everything. */
    fun refresh() {
        cachedProject = null
        cachedUser = null
        cachedMembers = null
        approvalsCache.clear()
    }

    /** Loads merge requests for [selection], resolving project/user/remote as needed. */
    suspend fun loadMergeRequests(selection: MrFilterSelection): CockpitState {
        val (client, glProject) = when (val resolution = resolveClientAndProject()) {
            is ProjectResolution.Ok -> resolution.client to resolution.glProject
            ProjectResolution.NotConfigured -> return CockpitState.NotConfigured
            ProjectResolution.NoRemote -> return CockpitState.NoGitLabRemote
            is ProjectResolution.Failed -> return toErrorState(resolution.error)
        }

        val currentUser = cachedUser ?: when (val r = client.getCurrentUser()) {
            is GitLabResult.Success -> r.data.also { cachedUser = it }
            is GitLabResult.HttpError -> return httpError(r)
            is GitLabResult.NetworkError -> return networkError(r)
        }

        val query = buildQuery(selection, currentUser)
        val mrs = when (val r = client.getMergeRequests(glProject.id, query)) {
            is GitLabResult.Success -> r.data
            is GitLabResult.HttpError -> return httpError(r)
            is GitLabResult.NetworkError -> return networkError(r)
        }

        val finalMrs = if (selection.role == RoleFilter.REVIEWER_NOT_APPROVED) {
            val approvals = loadApprovals(client, glProject.id, mrs)
            filterNotApproved(mrs, approvals, currentUser.id)
        } else {
            mrs
        }

        return CockpitState.Ready(finalMrs, currentUser)
    }

    /** Fetches the fresh detail of a single MR. Used by the detail panel on selection. */
    suspend fun getMrDetail(iid: Long): GitLabResult<GitLabMergeRequest> =
        withClientAndProject { client, glProject -> client.getMergeRequest(glProject.id, iid) }

    /**
     * Returns the project's members, cached in memory (invalidated by [refresh]). Only the first
     * successful load hits the network; subsequent calls reuse the cache.
     */
    suspend fun getMembers(): GitLabResult<List<GitLabUser>> {
        cachedMembers?.let { return GitLabResult.Success(it) }
        return withClientAndProject { client, glProject ->
            client.getProjectMembers(glProject.id).also {
                if (it is GitLabResult.Success) cachedMembers = it.data
            }
        }
    }

    /** Applies a partial update to an MR and returns the updated MR. */
    suspend fun updateMr(iid: Long, update: MergeRequestUpdate): GitLabResult<GitLabMergeRequest> =
        withClientAndProject { client, glProject -> client.updateMergeRequest(glProject.id, iid, update) }

    /**
     * Resolves the client + project once (reusing [cachedProject]) and runs [block] against them.
     * When the instance/remote/project cannot be resolved the failure is surfaced as a
     * [GitLabResult] so callers get a uniform error to display.
     */
    private suspend fun <T> withClientAndProject(
        block: suspend (GitLabApiClient, GitLabProject) -> GitLabResult<T>,
    ): GitLabResult<T> = when (val resolution = resolveClientAndProject()) {
        is ProjectResolution.Ok -> block(resolution.client, resolution.glProject)
        ProjectResolution.NotConfigured ->
            GitLabResult.NetworkError(IllegalStateException("No GitLab instance configured"))
        ProjectResolution.NoRemote ->
            GitLabResult.NetworkError(IllegalStateException("No matching git remote for the configured instance"))
        is ProjectResolution.Failed -> resolution.error
    }

    /**
     * Shared resolution of (client, project) used by both the MR list load and the detail/edit
     * paths. PasswordSafe is read here — off the EDT — never on the UI thread.
     */
    private suspend fun resolveClientAndProject(): ProjectResolution {
        val instance = GitLabCockpitSettings.getInstance().instances.firstOrNull()
        val baseUrl = instance?.baseUrl?.trim().orEmpty()
        if (baseUrl.isEmpty()) return ProjectResolution.NotConfigured

        val instanceHost = GitLabProjectResolver.hostOf(baseUrl) ?: return ProjectResolution.NotConfigured
        val coords = findMatchingRemote(instanceHost) ?: return ProjectResolution.NoRemote

        val token = TokenStore.get(baseUrl)
        val client = GitLabApiClient(baseUrl) { token }

        val glProject = cachedProject ?: when (val r = client.getProjectByPath(coords.pathWithNamespace)) {
            is GitLabResult.Success -> r.data.also { cachedProject = it }
            is GitLabResult.HttpError -> return ProjectResolution.Failed(r)
            is GitLabResult.NetworkError -> return ProjectResolution.Failed(r)
        }
        return ProjectResolution.Ok(client, glProject)
    }

    private fun toErrorState(result: GitLabResult<Nothing>): CockpitState.Error = when (result) {
        is GitLabResult.HttpError -> httpError(result)
        is GitLabResult.NetworkError -> networkError(result)
        is GitLabResult.Success -> throw IllegalStateException("Success is not an error")
    }

    /** Outcome of resolving the configured instance to a live client + project. */
    private sealed interface ProjectResolution {
        data class Ok(val client: GitLabApiClient, val glProject: GitLabProject) : ProjectResolution
        object NotConfigured : ProjectResolution
        object NoRemote : ProjectResolution
        data class Failed(val error: GitLabResult<Nothing>) : ProjectResolution
    }

    /**
     * Builds the server-side query. Role filters that GitLab supports directly are pushed down;
     * [RoleFilter.REVIEWER_NOT_APPROVED] narrows to "I am a reviewer" server-side and the
     * approval cross-check happens afterwards in the client.
     */
    private fun buildQuery(selection: MrFilterSelection, currentUser: GitLabUser): MergeRequestQuery {
        val state = selection.state.apiValue
        return when (selection.role) {
            RoleFilter.ALL ->
                MergeRequestQuery(state = state)
            RoleFilter.I_AM_AUTHOR ->
                MergeRequestQuery(state = state, authorUsername = currentUser.username)
            RoleFilter.I_AM_REVIEWER ->
                MergeRequestQuery(state = state, reviewerUsername = currentUser.username)
            RoleFilter.REVIEWER_NOT_APPROVED ->
                MergeRequestQuery(state = state, reviewerUsername = currentUser.username)
            RoleFilter.BY_USER ->
                MergeRequestQuery(state = state, authorUsername = selection.username?.trim()?.ifEmpty { null })
        }
    }

    /**
     * Fetches approvals for the (max 50) returned MRs in parallel, reusing cached approvals whose
     * MR `updated_at` is unchanged. MRs whose approvals could not be fetched are simply absent
     * from the returned map, which [filterNotApproved] treats as "not approved by me".
     */
    private suspend fun loadApprovals(
        client: GitLabApiClient,
        projectId: Long,
        mrs: List<GitLabMergeRequest>,
    ): Map<Long, GitLabApprovals> = coroutineScope {
        mrs.map { mr ->
            async {
                val cached = approvalsCache[mr.iid]
                if (cached != null && cached.updatedAt == mr.updatedAt) {
                    mr.iid to cached.approvals
                } else {
                    when (val r = client.getApprovals(projectId, mr.iid)) {
                        is GitLabResult.Success -> {
                            approvalsCache[mr.iid] = CachedApprovals(mr.updatedAt, r.data)
                            mr.iid to r.data
                        }
                        else -> mr.iid to null
                    }
                }
            }
        }.awaitAll()
            .mapNotNull { (iid, approvals) -> approvals?.let { iid to it } }
            .toMap()
    }

    private fun findMatchingRemote(instanceHost: String): RemoteCoords? {
        for (repo in GitRepositoryManager.getInstance(project).repositories) {
            for (remote in repo.remotes) {
                for (url in remote.urls) {
                    val coords = GitLabProjectResolver.parseRemoteUrl(url) ?: continue
                    if (coords.host.equals(instanceHost, ignoreCase = true)) return coords
                }
            }
        }
        return null
    }

    private fun httpError(error: GitLabResult.HttpError): CockpitState.Error =
        CockpitState.Error(CockpitBundle.message("toolwindow.error.http", error.status))

    private fun networkError(error: GitLabResult.NetworkError): CockpitState.Error =
        CockpitState.Error(
            CockpitBundle.message(
                "toolwindow.error.network",
                error.cause.message ?: error.cause.javaClass.simpleName,
            ),
        )

    companion object {
        fun getInstance(project: Project): CockpitProjectService = project.service()
    }
}
