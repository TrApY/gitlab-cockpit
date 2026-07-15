package dev.jota.gitlabcockpit.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Tests [GitLabApiClient.mergeMr] against a local [HttpServer]: verifies the HTTP method, path,
 * `Content-Type` and the exact JSON body (squash + should_remove_source_branch always sent,
 * merge_when_pipeline_succeeds only when requested), that a `200` becomes `Success(Unit)` without
 * decoding the returned MR JSON, and that a `405` (non-mergeable) becomes [GitLabResult.HttpError].
 */
class GitLabMergeApiTest {

    private var server: HttpServer? = null

    @Volatile
    private var method: String? = null

    @Volatile
    private var path: String? = null

    @Volatile
    private var contentType: String? = null

    @Volatile
    private var requestBody: String? = null

    @Volatile
    private var responseStatus: Int = 200

    @Volatile
    private var responseBody: String = "{}"

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/projects/123/merge_requests/42/merge") { exchange: HttpExchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        srv.start()
        server = srv
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server!!.address.port}"

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `merge sends squash and should_remove_source_branch and omits mwps by default`() {
        responseStatus = 200
        responseBody = """{"iid": 42, "state": "merged"}"""
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.mergeMr(
                projectId = 123,
                mrIid = 42,
                squash = true,
                removeSourceBranch = false,
                mergeWhenPipelineSucceeds = false,
            )
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)

        assertEquals("PUT", method)
        assertTrue("path ends with /merge, was: $path", path!!.endsWith("/merge_requests/42/merge"))
        assertEquals("application/json", contentType)
        assertEquals("""{"squash":true,"should_remove_source_branch":false}""", requestBody)
    }

    @Test
    fun `merge includes merge_when_pipeline_succeeds when requested`() {
        responseStatus = 200
        responseBody = """{"iid": 42, "state": "opened"}"""
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.mergeMr(
                projectId = 123,
                mrIid = 42,
                squash = false,
                removeSourceBranch = true,
                mergeWhenPipelineSucceeds = true,
            )
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(
            """{"squash":false,"should_remove_source_branch":true,"merge_when_pipeline_succeeds":true}""",
            requestBody,
        )
    }

    @Test
    fun `a non-mergeable MR maps 405 to HttpError`() {
        responseStatus = 405
        responseBody = """{"message":"405 Method Not Allowed"}"""
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.mergeMr(123, 42, squash = false, removeSourceBranch = false, mergeWhenPipelineSucceeds = false)
        }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(405, (result as GitLabResult.HttpError).status)
    }
}
