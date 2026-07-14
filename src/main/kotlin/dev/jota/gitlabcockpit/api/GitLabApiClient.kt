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

/** A merge request from `/projects/:id/merge_requests`. */
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

    /**
     * Shared GET implementation. Builds the request, sends it off the EDT on [Dispatchers.IO]
     * via [runInterruptible], and decodes the body with [deserializer]. Any transport or decode
     * failure is wrapped in [GitLabResult.NetworkError]; non-2xx yields [GitLabResult.HttpError].
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

        return try {
            val response = runInterruptible(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
            if (response.statusCode() in 200..299) {
                GitLabResult.Success(json.decodeFromString(deserializer, response.body()))
            } else {
                GitLabResult.HttpError(response.statusCode(), response.body())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitLabResult.NetworkError(e)
        }
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
