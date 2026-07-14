package dev.jota.gitlabcockpit.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabApiClient
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabNote
import dev.jota.gitlabcockpit.api.GitLabPipeline
import dev.jota.gitlabcockpit.api.GitLabProject
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.MergeRequestQuery
import dev.jota.gitlabcockpit.api.MergeRequestUpdate
import dev.jota.gitlabcockpit.api.TraceChunk
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import dev.jota.gitlabcockpit.settings.TokenStore
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps only human comments, dropping GitLab's system notes (state changes, label edits,
 * assignments…). Pure and platform-free so it can be unit tested directly, and reused wherever a
 * raw note list needs the same filtering.
 */
fun userNotes(notes: List<GitLabNote>): List<GitLabNote> = notes.filterNot { it.system }

/**
 * Outcome of a "retry stage" run: how many jobs were actually retried and, if any retry failed, the
 * first error message (already localized to a short `HTTP nnn` / network cause string).
 */
data class RetryStageResult(val retried: Int, val firstError: String?)

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

    /**
     * The user the configured token belongs to, populated by the first list load. Exposed read-only
     * so the detail panel can tell whether the current user already approved an MR without an extra
     * round-trip. Null until the first successful load.
     */
    val currentUser: GitLabUser?
        get() = cachedUser

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

    /** Fetches an MR's comment thread, already filtered to human notes (system notes dropped). */
    suspend fun getNotes(iid: Long): GitLabResult<List<GitLabNote>> =
        withClientAndProject { client, glProject ->
            when (val r = client.getMrNotes(glProject.id, iid)) {
                is GitLabResult.Success -> GitLabResult.Success(userNotes(r.data))
                is GitLabResult.HttpError -> r
                is GitLabResult.NetworkError -> r
            }
        }

    /** Posts a general comment on an MR and returns the created note. */
    suspend fun addNote(iid: Long, body: String): GitLabResult<GitLabNote> =
        withClientAndProject { client, glProject -> client.createMrNote(glProject.id, iid, body) }

    /**
     * Approves an MR as the current user. On success the MR's approvals cache entry is dropped so
     * the "reviewer, not approved" list filter re-fetches instead of serving a stale approval state.
     */
    suspend fun approve(iid: Long): GitLabResult<Unit> =
        withClientAndProject { client, glProject ->
            client.approveMr(glProject.id, iid).also { if (it is GitLabResult.Success) approvalsCache.remove(iid) }
        }

    /** Revokes the current user's approval. Invalidates the approvals cache like [approve]. */
    suspend fun unapprove(iid: Long): GitLabResult<Unit> =
        withClientAndProject { client, glProject ->
            client.unapproveMr(glProject.id, iid).also { if (it is GitLabResult.Success) approvalsCache.remove(iid) }
        }

    /**
     * Fetches an MR's fresh approval state for the detail view, bypassing the `updated_at`-keyed
     * [approvalsCache] used by the list filter so the overview always reflects the latest approve /
     * revoke.
     */
    suspend fun getApprovalsFor(iid: Long): GitLabResult<GitLabApprovals> =
        withClientAndProject { client, glProject -> client.getApprovals(glProject.id, iid) }

    // --- Pipelines (F2a) ----------------------------------------------------------------------

    /** Pipelines the given MR has triggered, newest-first (as GitLab returns them). */
    suspend fun getMrPipelines(iid: Long): GitLabResult<List<GitLabPipeline>> =
        withClientAndProject { client, glProject -> client.getMrPipelines(glProject.id, iid) }

    /** All jobs of a pipeline (stage-ordered), for the stage → job tree. */
    suspend fun getPipelineJobs(pipelineId: Long): GitLabResult<List<GitLabJob>> =
        withClientAndProject { client, glProject -> client.getPipelineJobs(glProject.id, pipelineId) }

    /** Retries a whole pipeline. */
    suspend fun retryPipeline(pipelineId: Long): GitLabResult<Unit> =
        withClientAndProject { client, glProject -> client.retryPipeline(glProject.id, pipelineId) }

    /** Cancels a whole pipeline. */
    suspend fun cancelPipeline(pipelineId: Long): GitLabResult<Unit> =
        withClientAndProject { client, glProject -> client.cancelPipeline(glProject.id, pipelineId) }

    /** Retries a single job. */
    suspend fun retryJob(jobId: Long): GitLabResult<Unit> =
        withClientAndProject { client, glProject -> client.retryJob(glProject.id, jobId) }

    /** Cancels a single job. */
    suspend fun cancelJob(jobId: Long): GitLabResult<Unit> =
        withClientAndProject { client, glProject -> client.cancelJob(glProject.id, jobId) }

    /** Plays (starts) a manual job. */
    suspend fun playJob(jobId: Long): GitLabResult<Unit> =
        withClientAndProject { client, glProject -> client.playJob(glProject.id, jobId) }

    /** Creates a new pipeline on [ref] (the MR's source branch). */
    suspend fun createPipeline(ref: String): GitLabResult<Unit> =
        withClientAndProject { client, glProject -> client.createPipeline(glProject.id, ref) }

    /**
     * "Retry stage": GitLab has no stage-retry endpoint, so this retries each retryable job of the
     * stage sequentially. Only `failed`/`canceled` jobs (those [isJobRetryable]) are retried; already
     * `success` jobs are left alone. Retrying continues past a failing job; the first error, if any,
     * is reported. [pipelineId] is accepted for call-site symmetry with the other pipeline actions.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun retryStage(pipelineId: Long, stage: StageGroup): RetryStageResult {
        val result = withClientAndProject { client, glProject ->
            var retried = 0
            var firstError: String? = null
            val targets = stage.jobs.filter { isJobRetryable(it.status) && it.status in RETRY_STAGE_STATUSES }
            for (job in targets) {
                when (val r = client.retryJob(glProject.id, job.id)) {
                    is GitLabResult.Success -> retried++
                    else -> if (firstError == null) firstError = describeError(r)
                }
            }
            GitLabResult.Success(RetryStageResult(retried, firstError))
        }
        return when (result) {
            is GitLabResult.Success -> result.data
            else -> RetryStageResult(0, describeError(result))
        }
    }

    // --- Job logs (F2b) -----------------------------------------------------------------------

    /** A slice of a job's trace starting at [offset], for the streaming log viewer. */
    suspend fun getJobTrace(jobId: Long, offset: Long): GitLabResult<TraceChunk> =
        withClientAndProject { client, glProject -> client.getJobTrace(glProject.id, jobId, offset) }

    /** Fresh detail of a single job; the log viewer polls it to learn when the job leaves running. */
    suspend fun getJob(jobId: Long): GitLabResult<GitLabJob> =
        withClientAndProject { client, glProject -> client.getJob(glProject.id, jobId) }

    // --- Pipeline notifications (F2b) ----------------------------------------------------------

    /** Last known status of each MR's latest pipeline, keyed by MR iid; the watcher compares against it. */
    private val lastPipelineStatus = ConcurrentHashMap<Long, String>()

    /**
     * One watcher pass: for the (at most [MAX_WATCHED_MRS] most recent) MRs the current user authored
     * or is assigned to, fetches each MR's latest pipeline status in parallel and compares it to the
     * last known one, returning the transitions worth notifying ([shouldNotify]). The status cache is
     * updated on every pass via an atomic [ConcurrentHashMap.put] (so overlapping passes never notify
     * twice); the first observation of an MR only memorizes. Never throws — unreachable MRs are
     * skipped. [ready.mrs] arrives newest-first (`order_by=updated_at`), so `take` keeps the recents.
     */
    suspend fun detectPipelineStatusChanges(ready: CockpitState.Ready): List<PipelineStatusChange> {
        val meId = ready.currentUser.id
        val candidates = ready.mrs
            .filter { mr -> mr.author.id == meId || mr.assignees.any { it.id == meId } }
            .take(MAX_WATCHED_MRS)
        if (candidates.isEmpty()) return emptyList()
        return coroutineScope {
            candidates.map { mr ->
                async {
                    val status = when (val r = getMrPipelines(mr.iid)) {
                        is GitLabResult.Success -> r.data.firstOrNull()?.status
                        else -> null
                    } ?: return@async null
                    val prev = lastPipelineStatus.put(mr.iid, status)
                    if (shouldNotify(prev, status)) PipelineStatusChange(mr, status) else null
                }
            }.awaitAll().filterNotNull()
        }
    }

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

    /** Short, non-localized description of a failed result, used inside [RetryStageResult.firstError]. */
    private fun describeError(result: GitLabResult<*>): String = when (result) {
        is GitLabResult.HttpError -> "HTTP ${result.status}"
        is GitLabResult.NetworkError -> result.cause.message ?: result.cause.javaClass.simpleName
        is GitLabResult.Success<*> -> ""
    }

    companion object {
        fun getInstance(project: Project): CockpitProjectService = project.service()

        /** Job statuses [retryStage] will actually retry (a subset of [isJobRetryable]). */
        private val RETRY_STAGE_STATUSES = setOf("failed", "canceled")

        /** Cap on how many of the current user's MRs the pipeline watcher inspects per pass. */
        private const val MAX_WATCHED_MRS = 10
    }
}
