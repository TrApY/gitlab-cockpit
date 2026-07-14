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
 * Tests [GitLabApiClient.updateMergeRequest] against a local [HttpServer]: verifies the HTTP method,
 * path, `Content-Type`, the JSON body (null fields omitted) and that the response parses back into
 * the model.
 */
class GitLabUpdateMergeRequestApiTest {

    private var server: HttpServer? = null

    @Volatile
    private var method: String? = null

    @Volatile
    private var contentType: String? = null

    @Volatile
    private var requestBody: String? = null

    private fun startServer(status: Int, body: String) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/projects/123/merge_requests/42") { exchange: HttpExchange ->
            method = exchange.requestMethod
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
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
    fun `PUT sends json body and parses the updated merge request`() {
        val response = """
            {
              "iid": 42,
              "title": "Renamed",
              "state": "opened",
              "source_branch": "feature",
              "target_branch": "main",
              "web_url": "https://gitlab.com/g/r/-/merge_requests/42",
              "updated_at": "2026-07-14T10:00:00Z",
              "description": "Updated body",
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
              "reviewers": [
                {"id": 2, "username": "rev1", "name": "Rev One"},
                {"id": 3, "username": "rev2", "name": "Rev Two"}
              ]
            }
        """.trimIndent()
        startServer(200, response)

        val update = MergeRequestUpdate(title = "Renamed", reviewerIds = listOf(2L, 3L))
        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.updateMergeRequest(123, 42, update) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val mr = (result as GitLabResult.Success).data
        assertEquals(42L, mr.iid)
        assertEquals("Renamed", mr.title)
        assertEquals("Updated body", mr.description)
        assertEquals(listOf("rev1", "rev2"), mr.reviewers.map { it.username })

        assertEquals("PUT", method)
        assertEquals("application/json", contentType)
        val sentBody = requestBody
        assertNotNull("request body captured", sentBody)
        assertTrue("title in body, was: $sentBody", sentBody!!.contains("\"title\":\"Renamed\""))
        assertTrue("reviewer_ids in body, was: $sentBody", sentBody.contains("\"reviewer_ids\":[2,3]"))
        assertTrue("description omitted, was: $sentBody", !sentBody.contains("\"description\""))
        assertTrue("assignee_ids omitted, was: $sentBody", !sentBody.contains("\"assignee_ids\""))
    }

    @Test
    fun `empty reviewer list clears reviewers via empty array in body`() {
        startServer(
            200,
            """
            {
              "iid": 42,
              "title": "T",
              "state": "opened",
              "source_branch": "s",
              "target_branch": "t",
              "web_url": "https://gitlab.com/g/r/-/merge_requests/42",
              "updated_at": "2026-07-14T10:00:00Z",
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
              "reviewers": []
            }
            """.trimIndent(),
        )

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.updateMergeRequest(123, 42, MergeRequestUpdate(reviewerIds = emptyList()))
        }

        assertTrue(result is GitLabResult.Success)
        assertTrue((result as GitLabResult.Success).data.reviewers.isEmpty())
        assertEquals("""{"reviewer_ids":[]}""", requestBody)
    }

    @Test
    fun `non-2xx yields HttpError`() {
        startServer(403, """{"message":"403 Forbidden"}""")

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.updateMergeRequest(123, 42, MergeRequestUpdate(title = "x"))
        }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(403, (result as GitLabResult.HttpError).status)
    }
}
