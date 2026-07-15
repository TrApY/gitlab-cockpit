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
 * Tests [GitLabApiClient.getProjectMembers] and [GitLabApiClient.getMergeRequest] (detail) against
 * a local [HttpServer]. Verifies query params, extra-field tolerance, and model parsing.
 */
class GitLabMembersAndDetailApiTest {

    private var server: HttpServer? = null

    @Volatile
    private var receivedQuery: String? = null

    private fun startServer(path: String, status: Int, body: String) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext(path) { exchange: HttpExchange ->
            receivedQuery = exchange.requestURI.rawQuery
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
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
    fun `getProjectMembers parses users and ignores extra fields`() {
        val body = """
            [
              {"id": 2, "username": "rev1", "name": "Rev One", "access_level": 40, "state": "active"},
              {"id": 3, "username": "rev2", "name": "Rev Two", "access_level": 30}
            ]
        """.trimIndent()
        startServer("/api/v4/projects/123/members/all", 200, body)

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getProjectMembers(123) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val members = (result as GitLabResult.Success).data
        assertEquals(2, members.size)
        assertEquals(listOf("rev1", "rev2"), members.map { it.username })
        assertEquals(listOf(2L, 3L), members.map { it.id })

        val query = receivedQuery
        assertNotNull("query captured", query)
        assertTrue("per_page param, was: $query", query!!.contains("per_page=100"))
    }

    @Test
    fun `getMergeRequest parses the detail including description`() {
        val body = """
            {
              "iid": 42,
              "project_id": 123,
              "title": "Detail title",
              "state": "opened",
              "source_branch": "feature",
              "target_branch": "main",
              "web_url": "https://gitlab.com/g/r/-/merge_requests/42",
              "updated_at": "2026-07-14T10:00:00Z",
              "description": "A **markdown** body",
              "draft": true,
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
              "reviewers": [{"id": 2, "username": "rev1", "name": "Rev One"}],
              "assignees": [{"id": 4, "username": "asg1", "name": "Assignee One"}]
            }
        """.trimIndent()
        startServer("/api/v4/projects/123/merge_requests/42", 200, body)

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMergeRequest(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val mr = (result as GitLabResult.Success).data
        assertEquals(42L, mr.iid)
        assertEquals(123L, mr.projectId)
        assertEquals("Detail title", mr.title)
        assertEquals("A **markdown** body", mr.description)
        assertTrue(mr.draft)
        assertEquals(listOf("rev1"), mr.reviewers.map { it.username })
        assertEquals(listOf("asg1"), mr.assignees.map { it.username })
    }
}
