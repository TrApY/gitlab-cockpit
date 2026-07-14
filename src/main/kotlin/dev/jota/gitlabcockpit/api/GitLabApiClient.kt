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
 * the boolean flags classify the change (see [dev.jota.gitlabcockpit.core.changeTypeOf]). Unknown
 * fields (the textual `diff`, mode bits…) are ignored by the configured [Json].
 */
@Serializable
data class GitLabDiffFile(
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String,
    @SerialName("new_file") val newFile: Boolean = false,
    @SerialName("renamed_file") val renamedFile: Boolean = false,
    @SerialName("deleted_file") val deletedFile: Boolean = false,
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
 * `canceled` / `skipped`…). Unknown fields are ignored by the configured [Json].
 */
@Serializable
data class GitLabPipeline(
    val id: Long,
    val status: String,
    val ref: String,
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

/** Request body for `POST /merge_requests/:iid/notes`: `{ "body": "…" }`. */
@Serializable
private data class NoteCreateBody(val body: String)

/** Request body for `POST /projects/:id/pipeline`: `{ "ref": "…" }`. */
@Serializable
private data class PipelineCreateBody(val ref: String)

/**
 * Server-side query for [GitLabApiClient.getMergeRequests]. `state` is one of
 * `opened` / `merged` / `closed` / `all`; the username filters are optional and only added to
 * the request when non-null.
 */
data class MergeRequestQuery(
    val state: String = "opened",
    val authorUsername: String? = null,
    val reviewerUsername: String? = null,
    val assigneeUsername: String? = null,
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

    /** Calls `GET /projects/:id/merge_requests` with the [filter] mapped to query params. */
    suspend fun getMergeRequests(
        projectId: Long,
        filter: MergeRequestQuery,
    ): GitLabResult<List<GitLabMergeRequest>> {
        val query = buildList {
            add("state" to filter.state)
            add("per_page" to "50")
            add("order_by" to "updated_at")
            filter.authorUsername?.let { add("author_username" to it) }
            filter.reviewerUsername?.let { add("reviewer_username" to it) }
            filter.assigneeUsername?.let { add("assignee_username" to it) }
        }
        return get(
            "/projects/$projectId/merge_requests",
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
     * Calls `GET /projects/:id/members/all` (inherited + direct members). Extra fields such as
     * `access_level` are ignored by the model.
     */
    suspend fun getProjectMembers(projectId: Long): GitLabResult<List<GitLabUser>> =
        get(
            "/projects/$projectId/members/all",
            listOf("per_page" to "100"),
            ListSerializer(GitLabUser.serializer()),
        )

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

    /** Calls `GET /projects/:id/merge_requests/:iid/pipelines` (GitLab returns them newest-first). */
    suspend fun getMrPipelines(projectId: Long, mrIid: Long): GitLabResult<List<GitLabPipeline>> =
        get(
            "/projects/$projectId/merge_requests/$mrIid/pipelines",
            emptyList(),
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
     * Builds a PUT request with a JSON [body] and delegates to [send]. Sends `Content-Type:
     * application/json`. Any failure while assembling the request is wrapped in
     * [GitLabResult.NetworkError].
     */
    private suspend fun <T> put(
        path: String,
        body: String,
        deserializer: DeserializationStrategy<T>,
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
        return send(request, deserializer)
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
