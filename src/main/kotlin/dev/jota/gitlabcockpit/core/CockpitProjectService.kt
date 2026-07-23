package dev.jota.gitlabcockpit.core

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.DiffRefs
import dev.jota.gitlabcockpit.api.GitLabApiClient
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabAward
import dev.jota.gitlabcockpit.api.GitLabBranch
import dev.jota.gitlabcockpit.api.GitLabBridge
import dev.jota.gitlabcockpit.api.GitLabDiffFile
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabDraftNote
import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabLabel
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabMrVersion
import dev.jota.gitlabcockpit.api.GitLabNote
import dev.jota.gitlabcockpit.api.GitLabPipeline
import dev.jota.gitlabcockpit.api.GitLabProject
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUpload
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.MergeRequestQuery
import dev.jota.gitlabcockpit.api.MergeRequestUpdate
import dev.jota.gitlabcockpit.api.PositionPayload
import dev.jota.gitlabcockpit.api.TraceChunk
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import dev.jota.gitlabcockpit.settings.TokenStore
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
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

/**
 * The git [repository] and its [remote] to check an MR's source branch into (GLC-42). Resolved by
 * [CockpitProjectService.resolveCheckoutTarget] from the same remote/project resolution the tool
 * window already uses, so the checkout targets the exact repository the cockpit is talking to.
 */
data class CheckoutTarget(val repository: GitRepository, val remote: GitRemote)

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
        /** The GitLab project resolved from this project's git remote, for the toolbar link. */
        val glProject: GitLabProject,
        /**
         * The `pathWithNamespace` of every git root of this project whose remote matches the
         * configured instance, already ordered and de-duplicated. Drives the repo selector when the
         * project has several matching roots (e.g. submodules); a single entry means no selector.
         */
        val remotePaths: List<String> = emptyList(),
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

    /** Project members, keyed by project id (loaded lazily by [getMembers] / [getResolvedMembers]). */
    private val cachedMembers = ConcurrentHashMap<Long, List<GitLabUser>>()

    /** Repository branches, keyed by project id (loaded lazily by [getBranches]; GLC-57). */
    private val cachedBranches = ConcurrentHashMap<Long, List<GitLabBranch>>()

    /** Keyed by [MrRef] (project + iid); invalidated per-MR when its `updated_at` changes. */
    private val approvalsCache = ConcurrentHashMap<MrRef, CachedApprovals>()

    /**
     * Downloads + caches Markdown image uploads to disk (one temp dir per project), fetching each
     * upload authenticated against the MR's own project. Not cleared by [refresh] — the bytes on disk
     * never go stale (an upload's secret+filename is immutable), so cached copies stay valid.
     */
    private val uploadImageCache = UploadImageCache { projectId, ref ->
        withClientAndProject { client, _ -> client.getProjectUpload(projectId, ref.secret, ref.filename) }
    }

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
        cachedMembers.clear()
        cachedBranches.clear()
        approvalsCache.clear()
    }

    /** Loads merge requests for [selection], resolving project/user/remote as needed. */
    suspend fun loadMergeRequests(selection: MrFilterSelection): CockpitState {
        lastLoadedSelection = selection
        val ok = when (val resolution = resolveClientAndProject()) {
            is ProjectResolution.Ok -> resolution
            ProjectResolution.NotConfigured -> return CockpitState.NotConfigured
            ProjectResolution.NoRemote -> return CockpitState.NoGitLabRemote
            is ProjectResolution.Failed -> return toErrorState(resolution.error)
        }
        val client = ok.client
        val glProject = ok.glProject
        val remotePaths = ok.remotePaths

        val currentUser = cachedUser ?: when (val r = client.getCurrentUser()) {
            is GitLabResult.Success -> r.data.also { cachedUser = it }
            is GitLabResult.HttpError -> return httpError(r)
            is GitLabResult.NetworkError -> return networkError(r)
        }

        // A global "By user" filter with no username would query the whole instance — short-circuit it.
        if (isGlobalByUserWithoutUser(selection)) {
            return CockpitState.Ready(emptyList(), currentUser, glProject, remotePaths)
        }

        val query = buildQuery(selection, currentUser)
        val mrs = when (val r = client.getMergeRequests(glProject.id, query)) {
            is GitLabResult.Success -> r.data
            is GitLabResult.HttpError -> return httpError(r)
            is GitLabResult.NetworkError -> return networkError(r)
        }

        val finalMrs = if (selection.role == RoleFilter.REVIEWER_NOT_APPROVED) {
            val approvals = loadApprovals(client, mrs)
            filterNotApproved(mrs, approvals, currentUser.id)
        } else {
            mrs
        }

        return CockpitState.Ready(finalMrs, currentUser, glProject, remotePaths)
    }

    /**
     * Persists [pathWithNamespace] as the git root the user chose to browse and drops all cached
     * data (via [refresh]) so the next load re-resolves against it. Does not reload by itself — the
     * panel triggers the reload.
     */
    fun selectRemote(pathWithNamespace: String) {
        PropertiesComponent.getInstance(project).setValue(SELECTED_REMOTE_PATH_KEY, pathWithNamespace)
        refresh()
    }

    /** Fetches the fresh detail of a single MR. Used by the detail panel on selection. */
    suspend fun getMrDetail(ref: MrRef): GitLabResult<GitLabMergeRequest> =
        withClientAndProject { client, _ -> client.getMergeRequest(ref.projectId, ref.iid) }

    /**
     * Returns the members of [projectId], cached in memory per project (invalidated by [refresh]).
     * Only the first successful load of a given project hits the network; the edit dialogs pass the
     * MR's own project id so they list the right project's members in the "All projects" mode.
     */
    suspend fun getMembers(projectId: Long): GitLabResult<List<GitLabUser>> {
        cachedMembers[projectId]?.let { return GitLabResult.Success(it) }
        return withClientAndProject { client, _ ->
            client.getProjectMembers(projectId).also {
                if (it is GitLabResult.Success) cachedMembers[projectId] = it.data
            }
        }
    }

    /**
     * Members of the git-resolved project, used by the "By user" filter autocomplete (which completes
     * against the resolved project even in the "All projects" mode). Shares the per-project
     * [cachedMembers] with [getMembers].
     */
    suspend fun getResolvedMembers(): GitLabResult<List<GitLabUser>> =
        withClientAndProject { client, glProject ->
            cachedMembers[glProject.id]?.let { return@withClientAndProject GitLabResult.Success(it) }
            client.getProjectMembers(glProject.id).also {
                if (it is GitLabResult.Success) cachedMembers[glProject.id] = it.data
            }
        }

    /**
     * Returns the branches of [projectId], cached in memory per project (invalidated by [refresh]), for
     * the Edit dialog's "Destination branch" autocomplete (GLC-57). Only the first successful load of a
     * given project hits the network; the edit dialog passes the MR's own project id so, in the "All
     * projects" mode, the completion lists the branches of the MR's project rather than the git-resolved
     * one. Mirrors [getMembers].
     */
    suspend fun getBranches(projectId: Long): GitLabResult<List<GitLabBranch>> {
        cachedBranches[projectId]?.let { return GitLabResult.Success(it) }
        return withClientAndProject { client, _ ->
            client.getRepositoryBranches(projectId).also {
                if (it is GitLabResult.Success) cachedBranches[projectId] = it.data
            }
        }
    }

    /**
     * The labels defined in [projectId] (its own plus ancestor-group labels), for the Edit dialog's
     * label picker (GLC-42). The MR's own project id is passed so, in the "All projects" mode, the
     * picker lists the labels of the MR's project rather than the git-resolved one.
     */
    suspend fun getProjectLabels(projectId: Long): GitLabResult<List<GitLabLabel>> =
        withClientAndProject { client, _ -> client.getProjectLabels(projectId) }

    /** Applies a partial update to an MR and returns the updated MR. */
    suspend fun updateMr(ref: MrRef, update: MergeRequestUpdate): GitLabResult<GitLabMergeRequest> =
        withClientAndProject { client, _ -> client.updateMergeRequest(ref.projectId, ref.iid, update) }

    /** Fetches an MR's comment thread, already filtered to human notes (system notes dropped). */
    suspend fun getNotes(ref: MrRef): GitLabResult<List<GitLabNote>> =
        withClientAndProject { client, _ ->
            when (val r = client.getMrNotes(ref.projectId, ref.iid)) {
                is GitLabResult.Success -> GitLabResult.Success(userNotes(r.data))
                is GitLabResult.HttpError -> r
                is GitLabResult.NetworkError -> r
            }
        }

    /**
     * Fetches an MR's raw notes with GitLab's system notes kept, for the Events & Discussions
     * timeline (GLC-34). Unlike [getNotes] — which drops system notes for a plain comment list — the
     * timeline turns the system notes into its event entries, so it needs them unfiltered.
     */
    suspend fun getTimelineNotes(ref: MrRef): GitLabResult<List<GitLabNote>> =
        withClientAndProject { client, _ -> client.getMrNotes(ref.projectId, ref.iid) }

    /** Posts a general comment on an MR and returns the created note. */
    suspend fun addNote(ref: MrRef, body: String): GitLabResult<GitLabNote> =
        withClientAndProject { client, _ -> client.createMrNote(ref.projectId, ref.iid, body) }

    /**
     * Uploads an attachment to [projectId] via `POST …/uploads` (GLC-56, the composer's clip button)
     * and returns the created [GitLabUpload], whose `markdown` the editor inserts at the caret. A thin
     * pass-through to the client, like the other note/upload wrappers.
     */
    suspend fun uploadAttachment(
        projectId: Long,
        filename: String,
        bytes: ByteArray,
        contentType: String,
    ): GitLabResult<GitLabUpload> =
        withClientAndProject { client, _ -> client.uploadProjectFile(projectId, filename, bytes, contentType) }

    /** Edits an existing note (GLC-40) and returns the updated note. */
    suspend fun updateNote(ref: MrRef, noteId: Long, body: String): GitLabResult<GitLabNote> =
        withClientAndProject { client, _ -> client.updateMrNote(ref.projectId, ref.iid, noteId, body) }

    /** Deletes an existing note (GLC-40). */
    suspend fun deleteNote(ref: MrRef, noteId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.deleteMrNote(ref.projectId, ref.iid, noteId) }

    /** The emoji reactions on a single note (GLC-40), for the lazily-loaded reactions row. */
    suspend fun getNoteAwards(ref: MrRef, noteId: Long): GitLabResult<List<GitLabAward>> =
        withClientAndProject { client, _ -> client.getNoteAwards(ref.projectId, ref.iid, noteId) }

    /** Adds an emoji reaction ([name]) to a note (GLC-40) and returns the created award. */
    suspend fun addNoteAward(ref: MrRef, noteId: Long, name: String): GitLabResult<GitLabAward> =
        withClientAndProject { client, _ -> client.addNoteAward(ref.projectId, ref.iid, noteId, name) }

    /** Removes the current user's emoji reaction ([awardId]) from a note (GLC-40). */
    suspend fun deleteNoteAward(ref: MrRef, noteId: Long, awardId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.deleteNoteAward(ref.projectId, ref.iid, noteId, awardId) }

    /**
     * Approves an MR as the current user. On success the MR's approvals cache entry is dropped so
     * the "reviewer, not approved" list filter re-fetches instead of serving a stale approval state.
     */
    suspend fun approve(ref: MrRef): GitLabResult<Unit> =
        withClientAndProject { client, _ ->
            client.approveMr(ref.projectId, ref.iid).also { if (it is GitLabResult.Success) approvalsCache.remove(ref) }
        }

    /** Revokes the current user's approval. Invalidates the approvals cache like [approve]. */
    suspend fun unapprove(ref: MrRef): GitLabResult<Unit> =
        withClientAndProject { client, _ ->
            client.unapproveMr(ref.projectId, ref.iid).also { if (it is GitLabResult.Success) approvalsCache.remove(ref) }
        }

    /**
     * Fetches an MR's fresh approval state for the detail view, bypassing the `updated_at`-keyed
     * [approvalsCache] used by the list filter so the overview always reflects the latest approve /
     * revoke.
     */
    suspend fun getApprovalsFor(ref: MrRef): GitLabResult<GitLabApprovals> =
        withClientAndProject { client, _ -> client.getApprovals(ref.projectId, ref.iid) }

    /**
     * Merges [ref] with the given options ([squash], [removeSourceBranch], [mergeWhenPipelineSucceeds]).
     * On success the MR's approvals cache entry is dropped so a subsequent detail reload reflects the
     * post-merge state instead of a stale one.
     */
    suspend fun merge(
        ref: MrRef,
        squash: Boolean,
        removeSourceBranch: Boolean,
        mergeWhenPipelineSucceeds: Boolean,
    ): GitLabResult<Unit> =
        withClientAndProject { client, _ ->
            client.mergeMr(ref.projectId, ref.iid, squash, removeSourceBranch, mergeWhenPipelineSucceeds)
                .also { if (it is GitLabResult.Success) approvalsCache.remove(ref) }
        }

    // --- Upload images in markdown (GLC-23) ---------------------------------------------------

    /**
     * Rewrites the upload image srcs in [html] to local `file://` URLs so the HTML editor kit can
     * render them: finds every `/uploads/<secret>/<filename>` image, downloads (authenticated) and
     * caches the ones not yet on disk against [projectId], and rewrites their srcs. Uploads that fail
     * to download keep their original src (they stay broken rather than dangling). When [html] embeds
     * no upload image it is returned unchanged with no network cost.
     */
    suspend fun resolveUploadImages(projectId: Long, html: String): String {
        val refs = findUploadImageRefs(html)
        if (refs.isEmpty()) return html
        val mapping = uploadImageCache.resolve(projectId, refs)
        if (mapping.isEmpty()) return html
        return rewriteUploadImageSrcs(html, mapping)
    }

    // --- Changed files & diff (F3) ------------------------------------------------------------

    /** The MR's changed files, for the file tree and the editor diff. */
    suspend fun getMrDiffs(ref: MrRef): GitLabResult<List<GitLabDiffFile>> =
        withClientAndProject { client, _ -> client.getMrDiffs(ref.projectId, ref.iid) }

    /**
     * Raw contents of [path] at [ref] (a SHA from the MR's `diff_refs`), for the diff sides. Takes the
     * MR's [projectId] explicitly so it fetches from the MR's own repository in the "All projects" mode.
     */
    suspend fun getRawFile(projectId: Long, path: String, ref: String): GitLabResult<String> =
        withClientAndProject { client, _ -> client.getRawFile(projectId, path, ref) }

    /** The MR's discussion threads (diff comments + general comments), for the comments panel. */
    suspend fun getMrDiscussions(ref: MrRef): GitLabResult<List<GitLabDiscussion>> =
        withClientAndProject { client, _ -> client.getMrDiscussions(ref.projectId, ref.iid) }

    /** Replies to an existing discussion thread and returns the created note. */
    suspend fun replyToDiscussion(
        ref: MrRef,
        discussionId: String,
        body: String,
    ): GitLabResult<GitLabDiscussionNote> =
        withClientAndProject { client, _ -> client.addDiscussionNote(ref.projectId, ref.iid, discussionId, body) }

    /**
     * Opens a new diff-anchored discussion on [file] at [pos]. Builds the [PositionPayload] from the
     * MR's [refs] (its `diff_refs` SHAs) — passed in by the caller, which already holds them, so no
     * extra round-trip or cached MR-detail state is needed. Both `old_path` and `new_path` are always
     * sent (from [file]); the old/new line come from [pos]. Returns the created discussion.
     */
    suspend fun createDiffThread(
        ref: MrRef,
        file: GitLabDiffFile,
        refs: DiffRefs,
        pos: LinePosition,
        body: String,
    ): GitLabResult<GitLabDiscussion> =
        withClientAndProject { client, _ ->
            val position = PositionPayload(
                baseSha = refs.baseSha,
                startSha = refs.startSha,
                headSha = refs.headSha,
                oldPath = file.oldPath,
                newPath = file.newPath,
                oldLine = pos.oldLine,
                newLine = pos.newLine,
            )
            client.createDiffDiscussion(ref.projectId, ref.iid, body, position)
        }

    // --- MR diff versions (GLC-41) ------------------------------------------------------------

    /** The MR's diff-version history (newest-first), for the "changes per version" selector. */
    suspend fun getMrVersions(ref: MrRef): GitLabResult<List<GitLabMrVersion>> =
        withClientAndProject { client, _ -> client.getMrVersions(ref.projectId, ref.iid) }

    /** The changed files of a single diff [versionId] of [ref], for the version-scoped file tree. */
    suspend fun getMrVersionDiffs(ref: MrRef, versionId: Long): GitLabResult<List<GitLabDiffFile>> =
        withClientAndProject { client, _ -> client.getMrVersionDiffs(ref.projectId, ref.iid, versionId) }

    // --- Draft notes, review submission & resolution (F4b) ------------------------------------

    /** The MR's pending draft notes (the current user's unpublished review comments). */
    suspend fun getDraftNotes(ref: MrRef): GitLabResult<List<GitLabDraftNote>> =
        withClientAndProject { client, _ -> client.getDraftNotes(ref.projectId, ref.iid) }

    /**
     * Adds a draft note; a null [position] posts a general draft, otherwise a diff-anchored one. A
     * non-null [inReplyToDiscussionId] threads the draft into that discussion (a reply-mode draft).
     */
    suspend fun createDraftNote(
        ref: MrRef,
        note: String,
        position: PositionPayload? = null,
        inReplyToDiscussionId: String? = null,
    ): GitLabResult<GitLabDraftNote> =
        withClientAndProject { client, _ ->
            client.createDraftNote(ref.projectId, ref.iid, note, position, inReplyToDiscussionId)
        }

    /**
     * Opens a diff-anchored draft on [file] at [pos] — the draft analogue of [createDiffThread].
     * Builds the [PositionPayload] from the MR's [refs] (its `diff_refs` SHAs), always sending both
     * `old_path` and `new_path` from [file] with the old/new line from [pos]. Returns the created draft.
     */
    suspend fun createDraftThread(
        ref: MrRef,
        file: GitLabDiffFile,
        refs: DiffRefs,
        pos: LinePosition,
        note: String,
    ): GitLabResult<GitLabDraftNote> =
        withClientAndProject { client, _ ->
            val position = PositionPayload(
                baseSha = refs.baseSha,
                startSha = refs.startSha,
                headSha = refs.headSha,
                oldPath = file.oldPath,
                newPath = file.newPath,
                oldLine = pos.oldLine,
                newLine = pos.newLine,
            )
            client.createDraftNote(ref.projectId, ref.iid, note, position)
        }

    /** Discards a single pending draft note. */
    suspend fun deleteDraftNote(ref: MrRef, draftId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.deleteDraftNote(ref.projectId, ref.iid, draftId) }

    /** Publishes every pending draft as the review submission (bulk publish). */
    suspend fun publishDrafts(ref: MrRef): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.publishAllDraftNotes(ref.projectId, ref.iid) }

    /** Resolves ([resolved] true) or reopens ([resolved] false) a discussion thread. */
    suspend fun setDiscussionResolved(
        ref: MrRef,
        discussionId: String,
        resolved: Boolean,
    ): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.resolveDiscussion(ref.projectId, ref.iid, discussionId, resolved) }

    // --- Pipelines (F2a) ----------------------------------------------------------------------

    /** Pipelines the given MR has triggered, newest-first (as GitLab returns them). */
    suspend fun getMrPipelines(ref: MrRef): GitLabResult<List<GitLabPipeline>> =
        withClientAndProject { client, _ -> client.getMrPipelines(ref.projectId, ref.iid) }

    /** All jobs of a pipeline (stage-ordered), for the stage → job tree. */
    suspend fun getPipelineJobs(projectId: Long, pipelineId: Long): GitLabResult<List<GitLabJob>> =
        withClientAndProject { client, _ -> client.getPipelineJobs(projectId, pipelineId) }

    /** The bridge (trigger) jobs of a pipeline and the downstream pipelines they started (GLC-60). */
    suspend fun getPipelineBridges(projectId: Long, pipelineId: Long): GitLabResult<List<GitLabBridge>> =
        withClientAndProject { client, _ -> client.getPipelineBridges(projectId, pipelineId) }

    /** Retries a whole pipeline. */
    suspend fun retryPipeline(projectId: Long, pipelineId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.retryPipeline(projectId, pipelineId) }

    /** Cancels a whole pipeline. */
    suspend fun cancelPipeline(projectId: Long, pipelineId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.cancelPipeline(projectId, pipelineId) }

    /** Retries a single job. */
    suspend fun retryJob(projectId: Long, jobId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.retryJob(projectId, jobId) }

    /** Cancels a single job. */
    suspend fun cancelJob(projectId: Long, jobId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.cancelJob(projectId, jobId) }

    /** Plays (starts) a manual job. */
    suspend fun playJob(projectId: Long, jobId: Long): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.playJob(projectId, jobId) }

    /** Creates a new pipeline on [ref] (the MR's source branch) in [projectId]. */
    suspend fun createPipeline(projectId: Long, ref: String): GitLabResult<Unit> =
        withClientAndProject { client, _ -> client.createPipeline(projectId, ref) }

    /**
     * "Retry stage": GitLab has no stage-retry endpoint, so this retries each retryable job of the
     * stage sequentially. Only `failed`/`canceled` jobs (those [isJobRetryable]) are retried; already
     * `success` jobs are left alone. Retrying continues past a failing job; the first error, if any,
     * is reported. [pipelineId] is accepted for call-site symmetry with the other pipeline actions.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun retryStage(projectId: Long, pipelineId: Long, stage: StageGroup): RetryStageResult {
        val result = withClientAndProject { client, _ ->
            var retried = 0
            var firstError: String? = null
            val targets = stage.jobs.filter { isJobRetryable(it.status) && it.status in RETRY_STAGE_STATUSES }
            for (job in targets) {
                when (val r = client.retryJob(projectId, job.id)) {
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
    suspend fun getJobTrace(projectId: Long, jobId: Long, offset: Long): GitLabResult<TraceChunk> =
        withClientAndProject { client, _ -> client.getJobTrace(projectId, jobId, offset) }

    /** Fresh detail of a single job; the log viewer polls it to learn when the job leaves running. */
    suspend fun getJob(projectId: Long, jobId: Long): GitLabResult<GitLabJob> =
        withClientAndProject { client, _ -> client.getJob(projectId, jobId) }

    // --- Pipeline notifications (F2b) ----------------------------------------------------------

    /** Last known status of each MR's latest pipeline, keyed by [MrRef]; the watcher compares against it. */
    private val lastPipelineStatus = ConcurrentHashMap<MrRef, String>()

    /**
     * Last known status of each downstream (bridge-triggered) pipeline, keyed by its own pipeline id
     * (GLC-61); the downstream watcher diffs against it. Keyed by pipeline id rather than [MrRef]
     * because a downstream pipeline has a stable identity of its own (possibly in another project) and
     * one MR pipeline can fan out to several bridges. Its own snapshot, separate from [lastPipelineStatus]
     * (which tracks the *upstream* MR pipeline), so the two deltas never interfere.
     */
    private val lastDownstreamStatus = ConcurrentHashMap<Long, String>()

    /**
     * Last known set of approver ids of each watched MR, keyed by [MrRef]; the approval watcher (GLC-55)
     * diffs against it. Deliberately its own snapshot, separate from the detail/list [approvalsCache]:
     * that cache is `updated_at`-keyed for the "reviewer, not approved" filter and answers a different
     * question (it would even go stale here, since approvals can change without `updated_at` moving).
     */
    private val lastApproverIds = ConcurrentHashMap<MrRef, Set<Long>>()

    /**
     * Last known [MrSnapshot] of every MR in the current user's notification scope, keyed by [MrRef].
     * `null` until the first watcher pass has run — the null vs. empty-map distinction is what makes
     * the first pass memorize silently (see [detectMrEvents]). Deliberately **not** reset by [refresh]
     * (mirroring [lastPipelineStatus]) so a manual refresh does not drop a round of events.
     */
    @Volatile
    private var lastMrSnapshot: Map<MrRef, MrSnapshot>? = null

    /** The selection of the last [loadMergeRequests]; feeds the filter-change rebaseline (GLC-57). */
    @Volatile
    private var lastLoadedSelection: MrFilterSelection? = null

    /** The selection the events snapshot was built under; a difference rebaselines silently (GLC-57). */
    @Volatile
    private var lastEventsSelection: MrFilterSelection? = null

    /**
     * One watcher pass: for the (at most [MAX_WATCHED_MRS] most recent) MRs the current user authored
     * or is assigned to, fetches each MR's latest pipeline in parallel and compares its status to the
     * last known one, returning the transitions worth notifying ([shouldNotify]). In the same async —
     * off the single pipeline fetch, no extra round — it also fetches that pipeline's bridges and diffs
     * their downstream pipelines (GLC-61), so a downstream that fails *after* the MR pipeline succeeded
     * is caught too. Both snapshots are updated on every pass via an atomic [ConcurrentHashMap.put] (so
     * overlapping passes never notify twice); the first observation of a pipeline or a downstream only
     * memorizes. Never throws — unreachable MRs (and MRs whose bridges cannot be fetched) are skipped.
     * [ready.mrs] arrives newest-first (`order_by=updated_at`), so `take` keeps the recents.
     */
    suspend fun detectPipelineStatusChanges(ready: CockpitState.Ready): PipelineWatchResult {
        val meId = ready.currentUser.id
        val candidates = ready.mrs
            .filter { mr -> mr.author.id == meId || mr.assignees.any { it.id == meId } }
            .take(MAX_WATCHED_MRS)
        if (candidates.isEmpty()) return PipelineWatchResult(emptyList(), emptyList())
        val passes = coroutineScope {
            candidates.map { mr ->
                async {
                    val ref = MrRef(mr.projectId, mr.iid)
                    val pipeline = when (val r = getMrPipelines(ref)) {
                        is GitLabResult.Success -> r.data.firstOrNull()
                        else -> null
                    } ?: return@async null
                    val prev = lastPipelineStatus.put(ref, pipeline.status)
                    val pipelineChange =
                        if (shouldNotify(prev, pipeline.status)) PipelineStatusChange(mr, pipeline.status) else null
                    MrPipelinePass(pipelineChange, detectDownstreamChanges(mr, ref.projectId, pipeline.id))
                }
            }.awaitAll().filterNotNull()
        }
        return PipelineWatchResult(
            pipelines = passes.mapNotNull { it.pipeline },
            downstreams = passes.flatMap { it.downstreams },
        )
    }

    /**
     * The downstream half of a single MR's pipeline pass (GLC-61): fetches the [pipelineId]'s bridges
     * in [projectId] and diffs each bridge's downstream pipeline against [lastDownstreamStatus] via the
     * pure [downstreamChange], memorizing every observed downstream status with an atomic
     * [ConcurrentHashMap.put]. A failed bridge fetch is ignored (this MR contributes no downstreams this
     * pass, and nothing is memorized, so the next pass retries); a bridge that has not triggered a
     * downstream yet contributes nothing.
     */
    private suspend fun detectDownstreamChanges(
        mr: GitLabMergeRequest,
        projectId: Long,
        pipelineId: Long,
    ): List<DownstreamStatusChange> {
        val bridges = when (val r = getPipelineBridges(projectId, pipelineId)) {
            is GitLabResult.Success -> r.data
            else -> return emptyList()
        }
        return bridges.mapNotNull { bridge ->
            val downstream = bridge.downstream ?: return@mapNotNull null
            val prev = lastDownstreamStatus.put(downstream.id, downstream.status)
            downstreamChange(prev, bridge, mr)
        }
    }

    /** The outcome of one MR's pipeline pass: its upstream [pipeline] change (if any) and [downstreams]. */
    private data class MrPipelinePass(
        val pipeline: PipelineStatusChange?,
        val downstreams: List<DownstreamStatusChange>,
    )

    /**
     * One approval-watcher pass (GLC-55): for the (at most [MAX_WATCHED_MRS] most recent) MRs the
     * current user authored or is assigned to — the same candidate set as [detectPipelineStatusChanges]
     * — fetches each MR's approvals in parallel and diffs the approver ids against the last known set
     * ([lastApproverIds]), returning the MRs that gained a NEW approver (with the new users, for the
     * balloon text; see [newApprovers]). The id snapshot is updated on every pass via an atomic
     * [ConcurrentHashMap.put] (so overlapping passes never notify twice); the first observation of an MR
     * only memorizes. An unapprove (ids that disappear) just updates the snapshot and never notifies.
     * Approvals are fetched fresh via [getApprovalsFor] (bypassing the `updated_at`-keyed detail cache).
     * Never throws — MRs whose approvals cannot be fetched are skipped (and left un-memorized, so the
     * next pass retries). [ready.mrs] arrives newest-first, so `take` keeps the recents.
     */
    suspend fun detectApprovalChanges(ready: CockpitState.Ready): List<ApprovalChange> {
        val meId = ready.currentUser.id
        val candidates = ready.mrs
            .filter { mr -> mr.author.id == meId || mr.assignees.any { it.id == meId } }
            .take(MAX_WATCHED_MRS)
        if (candidates.isEmpty()) return emptyList()
        return coroutineScope {
            candidates.map { mr ->
                async {
                    val ref = MrRef(mr.projectId, mr.iid)
                    val current = when (val r = getApprovalsFor(ref)) {
                        is GitLabResult.Success -> r.data.approvedBy
                        else -> return@async null
                    }
                    val prev = lastApproverIds.put(ref, current.mapTo(HashSet()) { it.user.id })
                    val gained = newApprovers(prev, current)
                    if (gained.isEmpty()) null else ApprovalChange(mr, gained)
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * One MR-events watcher pass: narrows [ready.mrs] to the effective notification scope
     * ([notificationScope]: role scope ∪ watched, or the whole filter when the "all filtered" setting
     * is on), fetches any watched MR that fell outside [ready.mrs] so its events are detected too, and
     * diffs the combined list against [lastMrSnapshot] via [detectMrEvents], returning the detected
     * events and atomically advancing the stored snapshot (see [advanceMrSnapshot]). The snapshot
     * always advances (even for event types the user has muted) so toggling a checkbox on never
     * replays historical changes. The individual fetches (capped at [MAX_WATCHED_FETCH], in parallel,
     * failures ignored) are the only network cost; both the visible-panel and the background poller go
     * through here, so watched-out-of-list detection is inherited by both.
     */
    suspend fun detectScopeMrEvents(ready: CockpitState.Ready): List<MrEvent> {
        val settings = GitLabCockpitSettings.getInstance()
        val watched = watchedRefs()
        val scoped = notificationScope(ready.mrs, ready.currentUser.id, watched, settings.notifyScopeAllFiltered)

        // Watched MRs the current filter did not return: fetch each on its own so it can still fire
        // events. They are disjoint from `scoped` (which only holds MRs present in `ready.mrs`), so
        // concatenating cannot introduce duplicates.
        val present = ready.mrs.mapTo(HashSet()) { MrRef(it.projectId, it.iid) }
        val missing = watched.filterNot { it in present }.take(MAX_WATCHED_FETCH)
        val fetched = if (missing.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                missing.map { ref -> async { (getMrDetail(ref) as? GitLabResult.Success)?.data } }
                    .awaitAll()
                    .filterNotNull()
            }
        }

        // A filter change means the scope's population changed wholesale: rebaseline silently so the
        // MRs the new filter exposes (e.g. every historical MR after switching the state to ALL) are
        // memorized without one "new MR" balloon each (GLC-57, user-reported after 0.13.0).
        val selection = lastLoadedSelection
        val baseline = selection != lastEventsSelection
        lastEventsSelection = selection
        return advanceMrSnapshot(scoped + fetched, baseline)
    }

    /**
     * Atomically diffs [mrs] against [lastMrSnapshot] and advances it. Synchronized (not `suspend`) so
     * overlapping passes never emit the same event twice, keeping the read-modify-write of the shared
     * snapshot indivisible while the network fetches in [detectScopeMrEvents] stay outside the lock.
     */
    @Synchronized
    private fun advanceMrSnapshot(mrs: List<GitLabMergeRequest>, baseline: Boolean): List<MrEvent> {
        // A baseline pass (the filter just changed, GLC-57) memorizes silently: filter-induced
        // appearance is scope motion, not news — detectMrEvents' null-previous rule does exactly that.
        val previous = if (baseline) null else lastMrSnapshot
        val (events, snapshot) = detectMrEvents(previous, mrs)
        lastMrSnapshot = snapshot
        return events
    }

    // --- Watched merge requests (GLC-28) -------------------------------------------------------

    /** The set of MRs the user explicitly watches in this project, decoded from [WATCHED_MRS_KEY]. */
    fun watchedRefs(): Set<MrRef> =
        decodeWatchedRefs(PropertiesComponent.getInstance(project).getValue(WATCHED_MRS_KEY))

    /** Whether [ref] is in this project's watch list. */
    fun isWatched(ref: MrRef): Boolean = ref in watchedRefs()

    /**
     * Adds ([watched] true) or removes ([watched] false) [ref] from this project's watch list and
     * persists it. Local only (no network); the change is picked up by the next watcher pass. The
     * value is unset when the list becomes empty so no stale empty entry lingers in the store.
     */
    fun setWatched(ref: MrRef, watched: Boolean) {
        val updated = watchedRefs().toMutableSet().apply { if (watched) add(ref) else remove(ref) }
        val props = PropertiesComponent.getInstance(project)
        val encoded = encodeWatchedRefs(updated)
        if (encoded.isEmpty()) props.unsetValue(WATCHED_MRS_KEY) else props.setValue(WATCHED_MRS_KEY, encoded)
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
        val candidates = findMatchingRemotes(instanceHost)
        val chosen = chooseRemote(candidates, persistedRemotePath(), project.basePath)
            ?: return ProjectResolution.NoRemote

        val token = TokenStore.get(baseUrl)
        val client = GitLabApiClient(baseUrl) { token }

        val glProject = cachedProject ?: when (val r = client.getProjectByPath(chosen.coords.pathWithNamespace)) {
            is GitLabResult.Success -> r.data.also { cachedProject = it }
            is GitLabResult.HttpError -> return ProjectResolution.Failed(r)
            is GitLabResult.NetworkError -> return ProjectResolution.Failed(r)
        }
        return ProjectResolution.Ok(client, glProject, candidates.map { it.coords.pathWithNamespace })
    }

    /** The `pathWithNamespace` the user last chose via [selectRemote], or null if none. */
    private fun persistedRemotePath(): String? =
        PropertiesComponent.getInstance(project).getValue(SELECTED_REMOTE_PATH_KEY)

    private fun toErrorState(result: GitLabResult<Nothing>): CockpitState.Error = when (result) {
        is GitLabResult.HttpError -> httpError(result)
        is GitLabResult.NetworkError -> networkError(result)
        is GitLabResult.Success -> throw IllegalStateException("Success is not an error")
    }

    /** Outcome of resolving the configured instance to a live client + project. */
    private sealed interface ProjectResolution {
        data class Ok(
            val client: GitLabApiClient,
            val glProject: GitLabProject,
            /** `pathWithNamespace` of every matching git root, ordered and de-duplicated. */
            val remotePaths: List<String>,
        ) : ProjectResolution
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
        val allProjects = selection.allProjects
        return when (selection.role) {
            RoleFilter.ALL ->
                MergeRequestQuery(state = state, allProjects = allProjects)
            RoleFilter.I_AM_AUTHOR ->
                MergeRequestQuery(state = state, authorUsername = currentUser.username, allProjects = allProjects)
            RoleFilter.I_AM_REVIEWER ->
                MergeRequestQuery(state = state, reviewerUsername = currentUser.username, allProjects = allProjects)
            RoleFilter.REVIEWER_NOT_APPROVED ->
                MergeRequestQuery(state = state, reviewerUsername = currentUser.username, allProjects = allProjects)
            RoleFilter.BY_USER ->
                MergeRequestQuery(
                    state = state,
                    authorUsername = selection.username?.trim()?.ifEmpty { null },
                    allProjects = allProjects,
                )
        }
    }

    /**
     * Fetches approvals for the (max 50) returned MRs in parallel, reusing cached approvals whose
     * MR `updated_at` is unchanged. Each MR's approvals are fetched against its own `project_id` (not
     * the git-resolved project) so the "reviewer, not approved" filter works in the "All projects"
     * mode; the cache is keyed by [MrRef]. MRs whose approvals could not be fetched are simply absent
     * from the returned map, which [filterNotApproved] treats as "not approved by me".
     */
    private suspend fun loadApprovals(
        client: GitLabApiClient,
        mrs: List<GitLabMergeRequest>,
    ): Map<MrRef, GitLabApprovals> = coroutineScope {
        mrs.map { mr ->
            async {
                val ref = MrRef(mr.projectId, mr.iid)
                val cached = approvalsCache[ref]
                if (cached != null && cached.updatedAt == mr.updatedAt) {
                    ref to cached.approvals
                } else {
                    when (val r = client.getApprovals(mr.projectId, mr.iid)) {
                        is GitLabResult.Success -> {
                            approvalsCache[ref] = CachedApprovals(mr.updatedAt, r.data)
                            ref to r.data
                        }
                        else -> ref to null
                    }
                }
            }
        }.awaitAll()
            .mapNotNull { (ref, approvals) -> approvals?.let { ref to it } }
            .toMap()
    }

    /**
     * Enumerates every git root of this project whose first matching remote resolves to
     * [instanceHost], as [CandidateRemote]s carrying the repo's root path. The result is ordered and
     * de-duplicated by [orderCandidates] (the project's own root first). Replaces the old
     * "first matching remote wins" behaviour, which let a nested submodule hijack the tool window.
     */
    private fun findMatchingRemotes(instanceHost: String): List<CandidateRemote> {
        val candidates = mutableListOf<CandidateRemote>()
        for (repo in GitRepositoryManager.getInstance(project).repositories) {
            val coords = repo.remotes.asSequence()
                .flatMap { it.urls.asSequence() }
                .mapNotNull { GitLabProjectResolver.parseRemoteUrl(it) }
                .firstOrNull { it.host.equals(instanceHost, ignoreCase = true) }
            if (coords != null) candidates.add(CandidateRemote(coords, repo.root.path))
        }
        return orderCandidates(candidates, project.basePath)
    }

    /**
     * Resolves the git [CheckoutTarget] — the [GitRepository] and the [GitRemote] — the local checkout
     * action should fetch from and check out into (GLC-42), reusing the exact same host/remote/project
     * resolution the tool window uses (see [findMatchingRemotes] / [chooseRemote]). Returns `null` when
     * no configured instance, no matching git remote, or no repository/remote can be resolved — the
     * checkout action then stays disabled. Synchronous and side-effect-free (only reads in-memory git
     * state), so the toolbar's action `update` can call it on the EDT.
     */
    fun resolveCheckoutTarget(): CheckoutTarget? {
        val baseUrl = GitLabCockpitSettings.getInstance().instances.firstOrNull()?.baseUrl?.trim().orEmpty()
        if (baseUrl.isEmpty()) return null
        val instanceHost = GitLabProjectResolver.hostOf(baseUrl) ?: return null

        val candidates = findMatchingRemotes(instanceHost)
        val chosen = chooseRemote(candidates, persistedRemotePath(), project.basePath) ?: return null

        val repo = GitRepositoryManager.getInstance(project).repositories
            .firstOrNull { it.root.path == chosen.rootPath } ?: return null
        val remote = repo.remotes.firstOrNull { remote ->
            remote.urls.any { url ->
                GitLabProjectResolver.parseRemoteUrl(url)?.let {
                    it.host.equals(instanceHost, ignoreCase = true) &&
                        it.pathWithNamespace == chosen.coords.pathWithNamespace
                } == true
            }
        } ?: return null
        return CheckoutTarget(repo, remote)
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

        /** Project-level [PropertiesComponent] key storing the chosen git root's `pathWithNamespace`. */
        private const val SELECTED_REMOTE_PATH_KEY = "dev.jota.gitlabcockpit.selectedRemotePath"

        /** Project-level [PropertiesComponent] key storing the encoded watched MRs (GLC-28). */
        private const val WATCHED_MRS_KEY = "dev.jota.gitlabcockpit.watchedMrs"

        /** Job statuses [retryStage] will actually retry (a subset of [isJobRetryable]). */
        private val RETRY_STAGE_STATUSES = setOf("failed", "canceled")

        /** Cap on how many of the current user's MRs the pipeline watcher inspects per pass. */
        private const val MAX_WATCHED_MRS = 10

        /** Cap on how many out-of-list watched MRs [detectScopeMrEvents] fetches individually per pass. */
        private const val MAX_WATCHED_FETCH = 10
    }
}
