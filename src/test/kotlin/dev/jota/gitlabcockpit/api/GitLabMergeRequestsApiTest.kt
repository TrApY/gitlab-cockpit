package dev.jota.gitlabcockpit.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Tests [GitLabApiClient.getMergeRequests] against a local [HttpServer]: verifies the generated
 * query params and that a JSON array response parses into the model list. No platform fixtures.
 */
class GitLabMergeRequestsApiTest {

    private var server: HttpServer? = null

    @Volatile
    private var receivedQuery: String? = null

    @Volatile
    private var receivedPath: String? = null

    private fun startServer(status: Int, body: String) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/projects/123/merge_requests") { exchange: HttpExchange ->
            receivedQuery = exchange.requestURI.rawQuery
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        srv.start()
        server = srv
    }

    /**
     * Registers *both* the single-project (`/projects/:id/merge_requests`) and the instance-wide
     * (`/merge_requests`) endpoints so a test can assert which one [GitLabApiClient.getMergeRequests]
     * picks (and with which query) depending on `allProjects`.
     */
    private fun startDualServer(status: Int, body: String) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val handler = com.sun.net.httpserver.HttpHandler { exchange: HttpExchange ->
            receivedPath = exchange.requestURI.path
            receivedQuery = exchange.requestURI.rawQuery
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        srv.createContext("/api/v4/projects/123/merge_requests", handler)
        srv.createContext("/api/v4/merge_requests", handler)
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
    fun `builds query params and parses the array`() {
        val body = """
            [
              {
                "iid": 10,
                "project_id": 123,
                "title": "First",
                "state": "opened",
                "source_branch": "f1",
                "target_branch": "main",
                "web_url": "https://gitlab.com/g/r/-/merge_requests/10",
                "updated_at": "2026-07-14T08:00:00Z",
                "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
                "reviewers": [{"id": 2, "username": "rev1", "name": "Rev One"}]
              },
              {
                "iid": 11,
                "project_id": 123,
                "title": "Second",
                "state": "opened",
                "source_branch": "f2",
                "target_branch": "main",
                "web_url": "https://gitlab.com/g/r/-/merge_requests/11",
                "updated_at": "2026-07-14T07:00:00Z",
                "author": {"id": 1, "username": "jota", "name": "Jo Ta"}
              }
            ]
        """.trimIndent()
        startServer(200, body)

        val filter = MergeRequestQuery(state = "opened", reviewerUsername = "jota")
        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMergeRequests(123, filter) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val mrs = (result as GitLabResult.Success).data
        assertEquals(2, mrs.size)
        assertEquals(10L, mrs[0].iid)
        assertEquals("First", mrs[0].title)
        assertEquals(listOf("rev1"), mrs[0].reviewers.map { it.username })

        val query = receivedQuery
        assertNotNull("query was captured", query)
        assertTrue("state param, was: $query", query!!.contains("state=opened"))
        assertTrue("per_page param, was: $query", query.contains("per_page=50"))
        assertTrue("order_by param, was: $query", query.contains("order_by=updated_at"))
        assertTrue("reviewer_username param, was: $query", query.contains("reviewer_username=jota"))
    }

    @Test
    fun `omits optional username params when not provided`() {
        startServer(200, "[]")

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.getMergeRequests(123, MergeRequestQuery(state = "all"))
        }

        assertTrue(result is GitLabResult.Success)
        assertEquals(0, (result as GitLabResult.Success).data.size)
        val query = receivedQuery ?: ""
        assertTrue("state param, was: $query", query.contains("state=all"))
        assertTrue("no author_username, was: $query", !query.contains("author_username"))
        assertTrue("no reviewer_username, was: $query", !query.contains("reviewer_username"))
        assertTrue("no assignee_username, was: $query", !query.contains("assignee_username"))
    }

    @Test
    fun `allProjects true hits the instance-wide endpoint with scope=all`() {
        startDualServer(200, "[]")

        val filter = MergeRequestQuery(state = "opened", authorUsername = "jota", allProjects = true)
        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMergeRequests(123, filter) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals("/api/v4/merge_requests", receivedPath)
        val query = receivedQuery ?: ""
        assertTrue("scope=all param, was: $query", query.contains("scope=all"))
        assertTrue("author_username param, was: $query", query.contains("author_username=jota"))
        assertTrue("state param, was: $query", query.contains("state=opened"))
    }

    @Test
    fun `allProjects false hits the single-project endpoint without scope`() {
        startDualServer(200, "[]")

        val filter = MergeRequestQuery(state = "opened", allProjects = false)
        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMergeRequests(123, filter) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals("/api/v4/projects/123/merge_requests", receivedPath)
        val query = receivedQuery ?: ""
        assertTrue("no scope param, was: $query", !query.contains("scope"))
    }
}
