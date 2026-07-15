package dev.jota.gitlabcockpit.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * GitLab `/version` payload. Unknown fields are ignored by the configured [Json] instance,
 * so the model only declares what the plugin actually uses.
 */
@Serializable
data class GitLabVersion(
    @SerialName("version") val version: String,
    val revision: String? = null,
)

/** A GitLab user as returned by `/user` and embedded in merge requests / approvals. */
@Serializable
data class GitLabUser(
    val id: Long,
    val username: String,
    val name: String = "",
)

/** A GitLab project. Only the fields the cockpit needs are modeled. */
@Serializable
data class GitLabProject(
    val id: Long,
    @SerialName("path_with_namespace") val pathWithNamespace: String,
    @SerialName("web_url") val webUrl: String,
)

/** A merge request from `/projects/:id/merge_requests` (list) or `/merge_requests/:iid` (detail). */
@Serializable
data class GitLabMergeRequest(
    val iid: Long,
    /**
     * The id of the project the MR lives in. Both the list and the detail endpoints return it; it is
     * the missing half of an MR's identity in the "All projects" mode, where MRs from many projects
     * share the same [iid] space (see [dev.jota.gitlabcockpit.core.MrRef]).
     */
    @SerialName("project_id") val projectId: Long,
    val title: String,
    val state: String,
    @SerialName("source_branch") val sourceBranch: String,
    @SerialName("target_branch") val targetBranch: String,
    @SerialName("web_url") val webUrl: String,
    @SerialName("updated_at") val updatedAt: String,
    val draft: Boolean = false,
    @SerialName("has_conflicts") val hasConflicts: Boolean = false,
    val author: GitLabUser,
    val reviewers: List<GitLabUser> = emptyList(),
    val assignees: List<GitLabUser> = emptyList(),
    /** The raw markdown body. Present on the list endpoint too, but may be null/absent. */
    val description: String? = null,
    /**
     * The diff SHAs the detail endpoint (`/merge_requests/:iid`) returns; the *list* endpoint does
     * not, so this is nullable. Needed to fetch base/head file contents for the diff viewer.
     */
    @SerialName("diff_refs") val diffRefs: DiffRefs? = null,
    /**
     * The MR's head pipeline, returned only by the detail endpoint (`/merge_requests/:iid`) — the
     * *list* endpoint omits it, so this is nullable. It is the single source of truth for pipelines
     * that GitLab does not list under `/pipelines` (e.g. externally reported ones from Jenkins), so
     * the Pipelines tab merges it into the pipeline list (see
     * [dev.jota.gitlabcockpit.core.mergeHeadPipeline]).
     */
    @SerialName("head_pipeline") val headPipeline: GitLabPipeline? = null,
    /**
     * The MR's reference block. Its [GitLabReferences.full] (`group/project!iid`) is the primary
     * source for the "All projects" row label; it is nullable because older GitLab versions (or a
     * trimmed payload) may omit it, in which case the label is derived from [webUrl] instead (see
     * [dev.jota.gitlabcockpit.core.projectLabelOf]).
     */
    val references: GitLabReferences? = null,
)

/**
 * The `references` block of a merge request. Only [full] (the fully-qualified reference,
 * `group/project!iid`) is modeled — the short/relative variants are ignored by the configured [Json].
 */
@Serializable
data class GitLabReferences(
    @SerialName("full") val full: String,
)

/**
 * The `diff_refs` block of a merge request: the three SHAs a diff is anchored to. [baseSha] and
 * [headSha] are the two sides the file diff compares; [startSha] is the merge base (needed later for
 * inline discussion positions in F4). Unknown fields are ignored by the configured [Json].
 */
@Serializable
data class DiffRefs(
    @SerialName("base_sha") val baseSha: String,
    @SerialName("head_sha") val headSha: String,
    @SerialName("start_sha") val startSha: String,
)

/**
 * A single changed file from `/merge_requests/:iid/diffs`. [oldPath]/[newPath] differ for renames;
 * the boolean flags classify the change (see [dev.jota.gitlabcockpit.core.changeTypeOf]). [diff] is
 * the file's unified-diff hunks (only the `@@`/` `/`+`/`-` lines — no `---`/`+++` headers), used by
 * [dev.jota.gitlabcockpit.core.buildLineMap] to know which lines can be commented on; it defaults to
 * empty so an absent `diff` never breaks parsing. Remaining fields (mode bits…) are ignored by the
 * configured [Json].
 */
@Serializable
data class GitLabDiffFile(
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String,
    @SerialName("new_file") val newFile: Boolean = false,
    @SerialName("renamed_file") val renamedFile: Boolean = false,
    @SerialName("deleted_file") val deletedFile: Boolean = false,
    val diff: String = "",
)

/**
 * Body for `PUT /projects/:id/merge_requests/:iid`. Every field is optional: only the non-null
 * ones are sent (see [GitLabApiClient.updateJson], configured with `explicitNulls = false`), so a
 * partial update touches only the provided attributes. An empty list is meaningful — it clears the
 * reviewers or assignees.
 */
@Serializable
data class MergeRequestUpdate(
    val title: String? = null,
    val description: String? = null,
    @SerialName("reviewer_ids") val reviewerIds: List<Long>? = null,
    @SerialName("assignee_ids") val assigneeIds: List<Long>? = null,
)

/** Approval state of a merge request from `/merge_requests/:iid/approvals`. */
@Serializable
data class GitLabApprovals(
    @SerialName("approved_by") val approvedBy: List<ApprovedBy> = emptyList(),
)

/** Wrapper GitLab uses for each approver: `{ "user": { ... } }`. */
@Serializable
data class ApprovedBy(
    val user: GitLabUser,
)

/**
 * A CI pipeline attached to a merge request (`/merge_requests/:iid/pipelines`). `status` is one of
 * GitLab's pipeline statuses (`success` / `failed` / `running` / `pending` / `created` / `manual` /
 * `canceled` / `skipped`…). `ref` is the branch/tag the pipeline ran on; externally reported
 * pipelines (e.g. Jenkins via GitLab's external pipeline API) can carry no ref, so it is nullable.
 * Unknown fields are ignored by the configured [Json].
 */
@Serializable
data class GitLabPipeline(
    val id: Long,
    val status: String,
    val ref: String? = null,
    val sha: String,
    @SerialName("web_url") val webUrl: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * A CI job inside a pipeline (`/pipelines/:id/jobs`). `stage` groups jobs into pipeline stages and
 * `status` mirrors [GitLabPipeline.status]. `duration` is the run time in seconds (null while the job
 * has not run); `allowFailure` marks jobs whose failure must not fail the pipeline.
 */
@Serializable
data class GitLabJob(
    val id: Long,
    val name: String,
    val stage: String,
    val status: String,
    val duration: Double? = null,
    @SerialName("allow_failure") val allowFailure: Boolean = false,
    @SerialName("web_url") val webUrl: String,
)

/**
 * A slice of a CI job's raw trace (`/jobs/:job_id/trace`, plain text — not JSON). [content] is the
 * new text that starts at the requested offset; [nextOffset] is the byte offset the next incremental
 * poll should resume from (counted in UTF-8 bytes so it lines up with the `Range: bytes=` header).
 */
data class TraceChunk(val content: String, val nextOffset: Long)

/**
 * A note (comment) on a merge request from `/merge_requests/:iid/notes`. `system` is `true` for
 * GitLab's auto-generated notes (state changes, label edits, assignments…); the cockpit filters
 * those out and only shows human comments. Unknown fields are ignored by the configured [Json].
 */
@Serializable
data class GitLabNote(
    val id: Long,
    val body: String,
    val system: Boolean = false,
    val author: GitLabUser,
    @SerialName("created_at") val createdAt: String,
)

/**
 * A discussion thread on a merge request from `/merge_requests/:iid/discussions`. A discussion is a
 * group of [notes]; diff comments carry a [GitLabDiscussionNote.position], general comments do not.
 * Unknown fields are ignored by the configured [Json].
 */
@Serializable
data class GitLabDiscussion(
    val id: String,
    val notes: List<GitLabDiscussionNote> = emptyList(),
)

/**
 * One note inside a [GitLabDiscussion]. [system] marks GitLab's auto-generated notes (dropped from
 * the UI); [position] is present only for notes anchored to a diff line. [resolvable]/[resolved]
 * reflect the thread's resolution state. Unknown fields are ignored by the configured [Json].
 */
@Serializable
data class GitLabDiscussionNote(
    val id: Long,
    val body: String,
    val system: Boolean = false,
    val author: GitLabUser,
    @SerialName("created_at") val createdAt: String,
    val resolvable: Boolean = false,
    val resolved: Boolean = false,
    val position: NotePosition? = null,
)

/**
 * The diff anchor of a positioned discussion note. Either a new-side line ([newLine] on [newPath])
 * or an old-side line ([oldLine] on [oldPath]) is set. Unknown position fields (the SHAs, line
 * codes…) are ignored by the configured [Json].
 */
@Serializable
data class NotePosition(
    @SerialName("new_path") val newPath: String? = null,
    @SerialName("old_path") val oldPath: String? = null,
    @SerialName("new_line") val newLine: Int? = null,
    @SerialName("old_line") val oldLine: Int? = null,
)

/**
 * A draft note from `/merge_requests/:iid/draft_notes` (a personal, unpublished review comment). Only
 * [id], [note] (its body) and the optional diff [position] are modeled; the rest of GitLab's payload
 * (`merge_request_id`, `author_id`, `resolve_discussion`…) is ignored by the configured [Json].
 */
@Serializable
data class GitLabDraftNote(
    val id: Long,
    val note: String,
    val position: NotePosition? = null,
)

/**
 * Request-side diff position for [GitLabApiClient.createDiffDiscussion]. Serialized with
 * [GitLabApiClient.discussionJson], which omits null fields but always emits [positionType]. The
 * three SHAs come from the MR's `diff_refs`; [oldPath] and [newPath] are always both sent, while
 * only the relevant one of [oldLine]/[newLine] is set (both, for a context line).
 */
@Serializable
data class PositionPayload(
    @SerialName("base_sha") val baseSha: String,
    @SerialName("start_sha") val startSha: String,
    @SerialName("head_sha") val headSha: String,
    @SerialName("position_type") val positionType: String = "text",
    @SerialName("old_path") val oldPath: String? = null,
    @SerialName("new_path") val newPath: String? = null,
    @SerialName("old_line") val oldLine: Int? = null,
    @SerialName("new_line") val newLine: Int? = null,
)

/** Request body for `POST /merge_requests/:iid/notes`: `{ "body": "…" }`. */
@Serializable
private data class NoteCreateBody(val body: String)

/** Request body for `POST /merge_requests/:iid/discussions`: `{ "body": …, "position": {…} }`. */
@Serializable
private data class DiffDiscussionCreateBody(
    val body: String,
    val position: PositionPayload,
)

/** Request body for `POST /projects/:id/pipeline`: `{ "ref": "…" }`. */
@Serializable
private data class PipelineCreateBody(val ref: String)

/**
 * Request body for `POST /merge_requests/:iid/draft_notes`: `{ "note": …, "position": {…}? }`. The
 * [position] is omitted for an unpositioned (general) draft; it is encoded by
 * [GitLabApiClient.discussionJson], which drops the null and always emits `position_type`.
 */
@Serializable
private data class DraftNoteCreateBody(
    val note: String,
    val position: PositionPayload? = null,
)

/** Request body for `PUT /merge_requests/:iid/discussions/:id`: `{ "resolved": true|false }`. */
@Serializable
private data class DiscussionResolveBody(val resolved: Boolean)

/**
 * Server-side query for [GitLabApiClient.getMergeRequests]. `state` is one of
 * `opened` / `merged` / `closed` / `all`; the username filters are optional and only added to
 * the request when non-null. When [allProjects] is set the query targets the instance-wide
 * `/merge_requests` endpoint (with `scope=all`) instead of a single project's list.
 */
data class MergeRequestQuery(
    val state: String = "opened",
    val authorUsername: String? = null,
    val reviewerUsername: String? = null,
    val assigneeUsername: String? = null,
    val allProjects: Boolean = false,
)

/**
 * Outcome of a GitLab API call. No exception is ever propagated to the caller: transport
 * failures become [NetworkError] and non-2xx responses become [HttpError].
 */
sealed class GitLabResult<out T> {
    data class Success<out T>(val data: T) : GitLabResult<T>()
    data class HttpError(val status: Int, val body: String?) : GitLabResult<Nothing>()
    data class NetworkError(val cause: Throwable) : GitLabResult<Nothing>()
}

/**
 * Minimal GitLab REST v4 client built on the JDK [HttpClient]. It authenticates with the
 * `PRIVATE-TOKEN` header and never logs or stores the token.
 *
 * @param baseUrl instance URL (with or without a trailing slash and with or without `/api/v4`).
 * @param tokenProvider supplies the PAT lazily; may return `null` when no token is configured.
 */
class GitLabApiClient(
    baseUrl: String,
    private val tokenProvider: () -> String?,
) {

    private val apiBase: String = normalizeBaseUrl(baseUrl)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Encoder for request bodies. `encodeDefaults = false` + `explicitNulls = false` mean null
     * fields (which are also the defaults on [MergeRequestUpdate]) are omitted from the JSON, while
     * a non-null empty list is still serialized as `[]`.
     */
    private val updateJson = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Encoder for the diff-discussion body. Like [updateJson] it omits null fields
     * (`explicitNulls = false`), but it keeps defaults (`encodeDefaults = true`) so
     * [PositionPayload.positionType] is always emitted as `"text"` — GitLab requires it, and
     * [updateJson]'s `encodeDefaults = false` would drop it because it equals the property default.
     */
    private val discussionJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    /** Calls `GET /version`. */
    suspend fun getVersion(): GitLabResult<GitLabVersion> =
        get("/version", emptyList(), GitLabVersion.serializer())

    /** Calls `GET /user` — the user the configured token belongs to. */
    suspend fun getCurrentUser(): GitLabResult<GitLabUser> =
        get("/user", emptyList(), GitLabUser.serializer())

    /** Calls `GET /projects/:pathWithNamespace` (URL-encoded) to resolve a project by its path. */
    suspend fun getProjectByPath(pathWithNamespace: String): GitLabResult<GitLabProject> {
        val encoded = URLEncoder.encode(pathWithNamespace, StandardCharsets.UTF_8)
        return get("/projects/$encoded", emptyList(), GitLabProject.serializer())
    }

    /**
     * Lists merge requests. When [filter] has `allProjects = false` this calls
     * `GET /projects/:id/merge_requests` for the single [projectId]; when it is set it calls the
     * instance-wide `GET /merge_requests` with `scope=all` (so it is not limited to the current
     * user's own MRs), still narrowed by the same state/username params. [projectId] is ignored in
     * the global case.
     */
    suspend fun getMergeRequests(
        projectId: Long,
        filter: MergeRequestQuery,
    ): GitLabResult<List<GitLabMergeRequest>> {
        val query = buildList {
            add("state" to filter.state)
            add("per_page" to "50")
            add("order_by" to "updated_at")
            if (filter.allProjects) add("scope" to "all")
            filter.authorUsername?.let { add("author_username" to it) }
            filter.reviewerUsername?.let { add("reviewer_username" to it) }
            filter.assigneeUsername?.let { add("assignee_username" to it) }
        }
        val path = if (filter.allProjects) "/merge_requests" else "/projects/$projectId/merge_requests"
        return get(
            path,
            query,
            ListSerializer(GitLabMergeRequest.serializer()),
        )
    }

    /** Calls `GET /projects/:id/merge_requests/:iid/approvals`. */
    suspend fun getApprovals(projectId: Long, mrIid: Long): GitLabResult<GitLabApprovals> =
        get(
            "/projects/$projectId/merge_requests/$mrIid/approvals",
            emptyList(),
            GitLabApprovals.serializer(),
        )

    /** Calls `GET /projects/:id/merge_requests/:iid` — the fresh detail of a single MR. */
    suspend fun getMergeRequest(projectId: Long, mrIid: Long): GitLabResult<GitLabMergeRequest> =
        get(
            "/projects/$projectId/merge_requests/$mrIid",
            emptyList(),
            GitLabMergeRequest.serializer(),
        )

    /**
     * Calls `GET /projects/:id/members/all` (inherited + direct members), paging through the result
     * with `per_page=100` + `page=N` until a page comes back short (fewer than [MEMBER_PAGE_SIZE]
     * items, i.e. the last page) or the [MAX_MEMBER_PAGES] safety cap is hit. The accumulated members
     * of every page are concatenated. Any page that fails short-circuits: its error is returned as-is
     * rather than the partial accumulation. Extra fields such as `access_level` are ignored by the
     * model.
     */
    suspend fun getProjectMembers(projectId: Long): GitLabResult<List<GitLabUser>> {
        val all = mutableListOf<GitLabUser>()
        var page = 1
        while (page <= MAX_MEMBER_PAGES) {
            val result = get(
                "/projects/$projectId/members/all",
                listOf("per_page" to MEMBER_PAGE_SIZE.toString(), "page" to page.toString()),
                ListSerializer(GitLabUser.serializer()),
            )
            when (result) {
                is GitLabResult.Success -> {
                    all += result.data
                    if (result.data.size < MEMBER_PAGE_SIZE) return GitLabResult.Success(all)
                    page++
                }
                is GitLabResult.HttpError -> return result
                is GitLabResult.NetworkError -> return result
            }
        }
        return GitLabResult.Success(all)
    }

    /**
     * Calls `PUT /projects/:id/merge_requests/:iid` with only the non-null attributes of [update].
     * The updated MR is returned.
     */
    suspend fun updateMergeRequest(
        projectId: Long,
        mrIid: Long,
        update: MergeRequestUpdate,
    ): GitLabResult<GitLabMergeRequest> {
        val body = updateJson.encodeToString(MergeRequestUpdate.serializer(), update)
        return put(
            "/projects/$projectId/merge_requests/$mrIid",
            body,
            GitLabMergeRequest.serializer(),
        )
    }

    /**
     * Calls `GET /projects/:id/merge_requests/:iid/notes` sorted oldest-first. Returns every note,
     * including system notes; the caller (or [dev.jota.gitlabcockpit.core.userNotes]) filters those.
     */
    suspend fun getMrNotes(projectId: Long, mrIid: Long): GitLabResult<List<GitLabNote>> =
        get(
            "/projects/$projectId/merge_requests/$mrIid/notes",
            listOf("sort" to "asc", "order_by" to "created_at", "per_page" to "100"),
            ListSerializer(GitLabNote.serializer()),
        )

    /** Calls `POST /projects/:id/merge_requests/:iid/notes` with `{ "body": … }`; returns the note. */
    suspend fun createMrNote(projectId: Long, mrIid: Long, body: String): GitLabResult<GitLabNote> {
        val payload = updateJson.encodeToString(NoteCreateBody.serializer(), NoteCreateBody(body))
        return post(
            "/projects/$projectId/merge_requests/$mrIid/notes",
            payload,
            GitLabNote.serializer(),
        )
    }

    /**
     * Calls `POST /projects/:id/merge_requests/:iid/approve`. GitLab answers `201` with the approval
     * JSON, which is intentionally ignored — success is all the caller needs.
     */
    suspend fun approveMr(projectId: Long, mrIid: Long): GitLabResult<Unit> =
        post("/projects/$projectId/merge_requests/$mrIid/approve", null, null)

    /**
     * Calls `POST /projects/:id/merge_requests/:iid/unapprove`. GitLab answers `204` with no body,
     * so nothing is decoded.
     */
    suspend fun unapproveMr(projectId: Long, mrIid: Long): GitLabResult<Unit> =
        post("/projects/$projectId/merge_requests/$mrIid/unapprove", null, null)

    // --- Changed files & diff (F3) ------------------------------------------------------------

    /** Calls `GET /projects/:id/merge_requests/:iid/diffs?per_page=100` — the MR's changed files. */
    suspend fun getMrDiffs(projectId: Long, mrIid: Long): GitLabResult<List<GitLabDiffFile>> =
        get(
            "/projects/$projectId/merge_requests/$mrIid/diffs",
            listOf("per_page" to "100"),
            ListSerializer(GitLabDiffFile.serializer()),
        )

    /**
     * Calls `GET /projects/:id/repository/files/:path/raw?ref=:ref` for the raw contents of a file at
     * a given [ref] (a branch, tag or SHA). [path] is encoded as a single path segment (so its `/`
     * separators become `%2F`, as GitLab requires) via [URLEncoder]. The response is plain text, not
     * JSON: `200` yields the body verbatim; any non-2xx becomes [GitLabResult.HttpError] (a `404` is
     * expected for a side that does not exist, e.g. the old side of a new file).
     */
    suspend fun getRawFile(projectId: Long, path: String, ref: String): GitLabResult<String> {
        val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8)
        val encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8)
        val fullPath = "/projects/$projectId/repository/files/$encodedPath/raw?ref=$encodedRef"
        return when (val raw = getRaw(fullPath, emptyList())) {
            is GitLabResult.Success ->
                if (raw.data.status in 200..299) {
                    GitLabResult.Success(raw.data.body)
                } else {
                    GitLabResult.HttpError(raw.data.status, raw.data.body)
                }
            is GitLabResult.HttpError -> raw
            is GitLabResult.NetworkError -> raw
        }
    }

    /**
     * Calls `GET /projects/:id/uploads/:secret/:filename` (the Markdown uploads download API, GitLab
     * 17+) for one embedded attachment, authenticated with the PAT so images referenced as
     * `/uploads/…` in a description or comment can be fetched. [filename] is encoded as a single path
     * segment. The response is binary: `2xx` yields the raw bytes; any non-2xx becomes
     * [GitLabResult.HttpError] (its body decoded best-effort as UTF-8); a transport failure becomes
     * [GitLabResult.NetworkError].
     */
    suspend fun getProjectUpload(
        projectId: Long,
        secret: String,
        filename: String,
    ): GitLabResult<ByteArray> {
        val encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
        return getBytes("/projects/$projectId/uploads/$secret/$encodedFilename")
    }

    /** Calls `GET /projects/:id/merge_requests/:iid/discussions?per_page=100` — the MR's threads. */
    suspend fun getMrDiscussions(projectId: Long, mrIid: Long): GitLabResult<List<GitLabDiscussion>> =
        get(
            "/projects/$projectId/merge_requests/$mrIid/discussions",
            listOf("per_page" to "100"),
            ListSerializer(GitLabDiscussion.serializer()),
        )

    /**
     * Calls `POST /projects/:id/merge_requests/:iid/discussions/:discussion_id/notes` with
     * `{ "body": … }` to reply to an existing thread; returns the created note.
     */
    suspend fun addDiscussionNote(
        projectId: Long,
        mrIid: Long,
        discussionId: String,
        body: String,
    ): GitLabResult<GitLabDiscussionNote> {
        val payload = updateJson.encodeToString(NoteCreateBody.serializer(), NoteCreateBody(body))
        return post(
            "/projects/$projectId/merge_requests/$mrIid/discussions/$discussionId/notes",
            payload,
            GitLabDiscussionNote.serializer(),
        )
    }

    /**
     * Calls `POST /projects/:id/merge_requests/:iid/discussions` with a `{ "body": …, "position": {…} }`
     * payload to open a new diff-anchored thread. [position] carries the `diff_refs` SHAs and the
     * old/new path+line the note anchors to; its null fields are omitted and `position_type` is always
     * `"text"`. Returns the created [GitLabDiscussion].
     */
    suspend fun createDiffDiscussion(
        projectId: Long,
        mrIid: Long,
        body: String,
        position: PositionPayload,
    ): GitLabResult<GitLabDiscussion> {
        val payload = discussionJson.encodeToString(
            DiffDiscussionCreateBody.serializer(),
            DiffDiscussionCreateBody(body, position),
        )
        return post(
            "/projects/$projectId/merge_requests/$mrIid/discussions",
            payload,
            GitLabDiscussion.serializer(),
        )
    }

    // --- Draft notes & review submission (F4b) ------------------------------------------------

    /** Calls `GET /projects/:id/merge_requests/:iid/draft_notes?per_page=100` — the user's drafts. */
    suspend fun getDraftNotes(projectId: Long, mrIid: Long): GitLabResult<List<GitLabDraftNote>> =
        get(
            "/projects/$projectId/merge_requests/$mrIid/draft_notes",
            listOf("per_page" to "100"),
            ListSerializer(GitLabDraftNote.serializer()),
        )

    /**
     * Calls `POST /projects/:id/merge_requests/:iid/draft_notes` with `{ "note": …, "position": {…}? }`
     * to add a personal (unpublished) review comment. A null [position] posts a general draft (the
     * field is omitted); a non-null one anchors the draft to a diff line, encoded exactly like
     * [createDiffDiscussion]'s position. Returns the created [GitLabDraftNote].
     */
    suspend fun createDraftNote(
        projectId: Long,
        mrIid: Long,
        note: String,
        position: PositionPayload? = null,
    ): GitLabResult<GitLabDraftNote> {
        val payload = discussionJson.encodeToString(
            DraftNoteCreateBody.serializer(),
            DraftNoteCreateBody(note, position),
        )
        return post(
            "/projects/$projectId/merge_requests/$mrIid/draft_notes",
            payload,
            GitLabDraftNote.serializer(),
        )
    }

    /**
     * Calls `DELETE /projects/:id/merge_requests/:iid/draft_notes/:draft_note_id` to discard a draft.
     * GitLab answers `204` with no body, so nothing is decoded.
     */
    suspend fun deleteDraftNote(projectId: Long, mrIid: Long, draftNoteId: Long): GitLabResult<Unit> =
        delete("/projects/$projectId/merge_requests/$mrIid/draft_notes/$draftNoteId")

    /**
     * Calls `POST /projects/:id/merge_requests/:iid/draft_notes/bulk_publish` to publish every draft
     * as the review submission. GitLab answers `204` with no body, so nothing is decoded.
     */
    suspend fun publishAllDraftNotes(projectId: Long, mrIid: Long): GitLabResult<Unit> =
        post("/projects/$projectId/merge_requests/$mrIid/draft_notes/bulk_publish", null, null)

    /**
     * Calls `PUT /projects/:id/merge_requests/:iid/discussions/:discussion_id` with
     * `{ "resolved": true|false }` to resolve or reopen a thread. GitLab answers `200` with the
     * updated discussion JSON, which is intentionally ignored (null deserializer) — success is all the
     * caller needs.
     */
    suspend fun resolveDiscussion(
        projectId: Long,
        mrIid: Long,
        discussionId: String,
        resolved: Boolean,
    ): GitLabResult<Unit> {
        val payload = updateJson.encodeToString(DiscussionResolveBody.serializer(), DiscussionResolveBody(resolved))
        return put("/projects/$projectId/merge_requests/$mrIid/discussions/$discussionId", payload, null)
    }

    /** Calls `GET /projects/:id/merge_requests/:iid/pipelines?per_page=100` (newest-first). */
    suspend fun getMrPipelines(projectId: Long, mrIid: Long): GitLabResult<List<GitLabPipeline>> =
        get(
            "/projects/$projectId/merge_requests/$mrIid/pipelines",
            listOf("per_page" to "100"),
            ListSerializer(GitLabPipeline.serializer()),
        )

    /** Calls `GET /projects/:id/pipelines/:pipeline_id/jobs?per_page=100`. */
    suspend fun getPipelineJobs(projectId: Long, pipelineId: Long): GitLabResult<List<GitLabJob>> =
        get(
            "/projects/$projectId/pipelines/$pipelineId/jobs",
            listOf("per_page" to "100"),
            ListSerializer(GitLabJob.serializer()),
        )

    /** Calls `GET /projects/:id/jobs/:job_id` — a single job, used to poll its status while streaming. */
    suspend fun getJob(projectId: Long, jobId: Long): GitLabResult<GitLabJob> =
        get("/projects/$projectId/jobs/$jobId", emptyList(), GitLabJob.serializer())

    /**
     * Calls `GET /projects/:id/jobs/:job_id/trace` (plain text, not JSON) for the incremental log
     * viewer, resuming at [offset] via a `Range: bytes=<offset>-` header. GitLab may answer:
     *
     * - `206 Partial Content`: [content] is the fragment; the next offset advances by the fragment's
     *   UTF-8 byte size.
     * - `200 OK`: the server ignored the Range and returned the whole trace. When [offset] > 0 the
     *   first [offset] **bytes** are dropped (trimmed on the byte array, then re-decoded) so only the
     *   unseen tail is returned; the next offset is the trace's total byte size.
     * - `416 Range Not Satisfiable`: [offset] already equals the current size — an empty fragment at
     *   the same offset.
     *
     * Any other non-2xx becomes [GitLabResult.HttpError]. Byte counting (not character counting) is
     * deliberate so the offset stays aligned with the `Range` header even for multi-byte UTF-8 text.
     */
    suspend fun getJobTrace(projectId: Long, jobId: Long, offset: Long): GitLabResult<TraceChunk> {
        val headers = if (offset > 0) listOf("Range" to "bytes=$offset-") else emptyList()
        return when (val raw = getRaw("/projects/$projectId/jobs/$jobId/trace", headers)) {
            is GitLabResult.Success -> {
                val status = raw.data.status
                val bodyBytes = raw.data.body.toByteArray(StandardCharsets.UTF_8)
                val total = bodyBytes.size.toLong()
                when {
                    status == 206 -> GitLabResult.Success(TraceChunk(raw.data.body, offset + total))
                    status == 416 -> GitLabResult.Success(TraceChunk("", offset))
                    status in 200..299 -> {
                        val start = offset.coerceIn(0, total).toInt()
                        val content = if (start == 0) {
                            raw.data.body
                        } else {
                            String(bodyBytes.copyOfRange(start, bodyBytes.size), StandardCharsets.UTF_8)
                        }
                        GitLabResult.Success(TraceChunk(content, total))
                    }
                    else -> GitLabResult.HttpError(status, raw.data.body)
                }
            }
            is GitLabResult.HttpError -> raw
            is GitLabResult.NetworkError -> raw
        }
    }

    /** Calls `POST /projects/:id/pipelines/:pipeline_id/retry`; the returned pipeline JSON is ignored. */
    suspend fun retryPipeline(projectId: Long, pipelineId: Long): GitLabResult<Unit> =
        post("/projects/$projectId/pipelines/$pipelineId/retry", null, null)

    /** Calls `POST /projects/:id/pipelines/:pipeline_id/cancel`; the returned pipeline JSON is ignored. */
    suspend fun cancelPipeline(projectId: Long, pipelineId: Long): GitLabResult<Unit> =
        post("/projects/$projectId/pipelines/$pipelineId/cancel", null, null)

    /** Calls `POST /projects/:id/jobs/:job_id/retry`; the returned job JSON is ignored. */
    suspend fun retryJob(projectId: Long, jobId: Long): GitLabResult<Unit> =
        post("/projects/$projectId/jobs/$jobId/retry", null, null)

    /** Calls `POST /projects/:id/jobs/:job_id/cancel`; the returned job JSON is ignored. */
    suspend fun cancelJob(projectId: Long, jobId: Long): GitLabResult<Unit> =
        post("/projects/$projectId/jobs/$jobId/cancel", null, null)

    /** Calls `POST /projects/:id/jobs/:job_id/play` to start a manual job; the response is ignored. */
    suspend fun playJob(projectId: Long, jobId: Long): GitLabResult<Unit> =
        post("/projects/$projectId/jobs/$jobId/play", null, null)

    /**
     * Calls `POST /projects/:id/pipeline` with `{ "ref": … }` to create a new pipeline on [ref].
     * GitLab answers `201` with the created pipeline JSON, which is intentionally ignored.
     */
    suspend fun createPipeline(projectId: Long, ref: String): GitLabResult<Unit> {
        val payload = updateJson.encodeToString(PipelineCreateBody.serializer(), PipelineCreateBody(ref))
        return post("/projects/$projectId/pipeline", payload, null)
    }

    /**
     * Builds a GET request and delegates to [send]. Any failure while assembling the request is
     * wrapped in [GitLabResult.NetworkError].
     */
    private suspend fun <T> get(
        path: String,
        query: List<Pair<String, String>>,
        deserializer: DeserializationStrategy<T>,
    ): GitLabResult<T> {
        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path + encodeQuery(query)))
                .timeout(REQUEST_TIMEOUT)
                .header("PRIVATE-TOKEN", tokenProvider().orEmpty())
                .GET()
                .build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GitLabResult.NetworkError(e)
        }
        return send(request, deserializer)
    }

    /**
     * Sends a GET and returns the response body as raw bytes (via [HttpResponse.BodyHandlers.ofByteArray]),
     * for binary payloads such as image uploads. `2xx` yields [GitLabResult.Success] with the bytes; any
     * other completed response is a [GitLabResult.HttpError] whose body is decoded best-effort as UTF-8;
     * only transport failures become [GitLabResult.NetworkError].
     */
    private suspend fun getBytes(path: String): GitLabResult<ByteArray> {
        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(REQUEST_TIMEOUT)
                .header("PRIVATE-TOKEN", tokenProvider().orEmpty())
                .GET()
                .build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GitLabResult.NetworkError(e)
        }
        return try {
            val response = runInterruptible(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            }
            if (response.statusCode() in 200..299) {
                GitLabResult.Success(response.body())
            } else {
                val body = runCatching { String(response.body(), StandardCharsets.UTF_8) }.getOrNull()
                GitLabResult.HttpError(response.statusCode(), body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitLabResult.NetworkError(e)
        }
    }

    /** A raw (non-JSON) HTTP response: its status and body, both left untouched for the caller. */
    private class RawResponse(val status: Int, val body: String)

    /**
     * Sends a GET with the extra [headers] (used for `Range`) and returns the raw response body as a
     * string, without decoding JSON. Unlike [send], any *completed* response is a [GitLabResult.Success]
     * regardless of its status code — the caller ([getJobTrace]) interprets 200 / 206 / 416 itself.
     * Only transport failures become [GitLabResult.NetworkError].
     */
    private suspend fun getRaw(
        path: String,
        headers: List<Pair<String, String>>,
    ): GitLabResult<RawResponse> {
        val request = try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(REQUEST_TIMEOUT)
                .header("PRIVATE-TOKEN", tokenProvider().orEmpty())
            headers.forEach { (key, value) -> builder.header(key, value) }
            builder.GET().build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GitLabResult.NetworkError(e)
        }
        return try {
            val response = runInterruptible(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
            GitLabResult.Success(RawResponse(response.statusCode(), response.body()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitLabResult.NetworkError(e)
        }
    }

    /**
     * Builds a PUT request with a JSON [body] and delegates to [sendOptional]. Sends `Content-Type:
     * application/json`. A null [deserializer] means "don't decode the response" — the outcome is
     * [Unit] on success (used by [resolveDiscussion], which ignores the returned discussion). Any
     * failure while assembling the request is wrapped in [GitLabResult.NetworkError].
     */
    private suspend fun <T> put(
        path: String,
        body: String,
        deserializer: DeserializationStrategy<T>?,
    ): GitLabResult<T> {
        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(REQUEST_TIMEOUT)
                .header("PRIVATE-TOKEN", tokenProvider().orEmpty())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GitLabResult.NetworkError(e)
        }
        return sendOptional(request, deserializer)
    }

    /**
     * Builds a bodyless DELETE request and delegates to [sendOptional] with a null deserializer, so a
     * `204` (or any 2xx) yields `Success(Unit)`. Any failure while assembling the request is wrapped
     * in [GitLabResult.NetworkError].
     */
    private suspend fun delete(path: String): GitLabResult<Unit> {
        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(REQUEST_TIMEOUT)
                .header("PRIVATE-TOKEN", tokenProvider().orEmpty())
                .DELETE()
                .build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GitLabResult.NetworkError(e)
        }
        return sendOptional(request, null)
    }

    /**
     * Builds a POST request and delegates to [sendOptional]. When [body] is non-null it is sent as a
     * JSON payload (with `Content-Type: application/json`); a null [body] posts nothing (used by the
     * bodyless approve/unapprove endpoints). A null [deserializer] means "don't decode the response"
     * — the outcome is [Unit] on success. Any failure while assembling the request is wrapped in
     * [GitLabResult.NetworkError].
     */
    private suspend fun <T> post(
        path: String,
        body: String?,
        deserializer: DeserializationStrategy<T>?,
    ): GitLabResult<T> {
        val request = try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(REQUEST_TIMEOUT)
                .header("PRIVATE-TOKEN", tokenProvider().orEmpty())
            val publisher = if (body != null) {
                builder.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            } else {
                HttpRequest.BodyPublishers.noBody()
            }
            builder.POST(publisher).build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GitLabResult.NetworkError(e)
        }
        return sendOptional(request, deserializer)
    }

    /**
     * Shared send/decode step. Sends [request] off the EDT on [Dispatchers.IO] via
     * [runInterruptible] and decodes the body with [deserializer]. Any transport or decode failure
     * is wrapped in [GitLabResult.NetworkError]; non-2xx yields [GitLabResult.HttpError].
     */
    private suspend fun <T> send(
        request: HttpRequest,
        deserializer: DeserializationStrategy<T>,
    ): GitLabResult<T> = sendOptional(request, deserializer)

    /**
     * Like [send], but tolerates a null [deserializer]: a `null` deserializer means the response
     * body is irrelevant (approve returns `201` with JSON, unapprove `204` with none) and success
     * yields `Success(Unit)` without ever touching the parser. When a [deserializer] is present the
     * body is decoded exactly as [send] does — so routing [send] through here is behaviour-neutral.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> sendOptional(
        request: HttpRequest,
        deserializer: DeserializationStrategy<T>?,
    ): GitLabResult<T> = try {
        val response = runInterruptible(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
        if (response.statusCode() in 200..299) {
            if (deserializer == null) {
                GitLabResult.Success(Unit) as GitLabResult<T>
            } else {
                GitLabResult.Success(json.decodeFromString(deserializer, response.body()))
            }
        } else {
            GitLabResult.HttpError(response.statusCode(), response.body())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GitLabResult.NetworkError(e)
    }

    private fun encodeQuery(query: List<Pair<String, String>>): String {
        if (query.isEmpty()) return ""
        return "?" + query.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Page size for the paginated `members/all` fetch; a short page signals the last one. */
        private const val MEMBER_PAGE_SIZE = 100

        /** Safety cap on how many member pages [getProjectMembers] will request. */
        private const val MAX_MEMBER_PAGES = 20

        /**
         * Trims trailing slashes and ensures the URL ends with `/api/v4`, so callers may pass
         * either `https://gitlab.com`, `https://gitlab.com/`, `https://gitlab.com/api/v4`, or
         * `https://gitlab.com/api/v4/` and always get `https://gitlab.com/api/v4`.
         */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.endsWith("/api/v4")) trimmed else "$trimmed/api/v4"
        }
    }
}
