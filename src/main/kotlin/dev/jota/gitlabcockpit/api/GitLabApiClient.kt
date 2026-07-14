package dev.jota.gitlabcockpit.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

    /** Calls `GET /version`. Blocking I/O runs on [Dispatchers.IO] via [runInterruptible]. */
    suspend fun getVersion(): GitLabResult<GitLabVersion> {
        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create("$apiBase/version"))
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
                GitLabResult.Success(json.decodeFromString<GitLabVersion>(response.body()))
            } else {
                GitLabResult.HttpError(response.statusCode(), response.body())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitLabResult.NetworkError(e)
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
