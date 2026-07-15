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
 * Tests the H4 endpoints of [GitLabApiClient] against a local [HttpServer]: notes (GET/POST) and the
 * bodyless approve/unapprove POSTs. Verifies HTTP method, path, query params, request body and that
 * a `204`/empty response is turned into `Success(Unit)` without any JSON decoding.
 */
class GitLabNotesAndApprovalsApiTest {

    private var server: HttpServer? = null

    @Volatile
    private var method: String? = null

    @Volatile
    private var path: String? = null

    @Volatile
    private var rawQuery: String? = null

    @Volatile
    private var contentType: String? = null

    @Volatile
    private var requestBody: String? = null

    @Volatile
    private var responseStatus: Int = 200

    @Volatile
    private var responseBody: String = ""

    /** When false the handler answers with a bodyless response (Content-Length -1), like a `204`. */
    @Volatile
    private var withBody: Boolean = true

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Longest-prefix match: this context also serves /notes, /approve and /unapprove.
        srv.createContext("/api/v4/projects/123/merge_requests/42") { exchange: HttpExchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
            rawQuery = exchange.requestURI.rawQuery
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            if (withBody) {
                val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(responseStatus, -1)
                exchange.close()
            }
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
    fun `getMrNotes parses system and non-system notes with the right query`() {
        responseStatus = 200
        responseBody = """
            [
              {
                "id": 1,
                "body": "First human comment",
                "system": false,
                "author": {"id": 2, "username": "rev1", "name": "Reviewer One"},
                "created_at": "2026-07-14T08:00:00Z"
              },
              {
                "id": 2,
                "body": "changed the description",
                "system": true,
                "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
                "created_at": "2026-07-14T08:05:00Z"
              },
              {
                "id": 3,
                "body": "Second human comment",
                "author": {"id": 3, "username": "rev2", "name": "Reviewer Two"},
                "created_at": "2026-07-14T08:10:00Z"
              }
            ]
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMrNotes(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val notes = (result as GitLabResult.Success).data
        assertEquals(3, notes.size)
        assertEquals(listOf(1L, 2L, 3L), notes.map { it.id })
        assertEquals(listOf(false, true, false), notes.map { it.system })
        assertEquals("First human comment", notes[0].body)
        assertEquals("rev2", notes[2].author.username)

        assertEquals("GET", method)
        assertTrue("path ends with /notes, was: $path", path!!.endsWith("/merge_requests/42/notes"))
        val query = rawQuery
        assertNotNull("query captured", query)
        assertTrue("sort param, was: $query", query!!.contains("sort=asc"))
        assertTrue("order_by param, was: $query", query.contains("order_by=created_at"))
        assertTrue("per_page param, was: $query", query.contains("per_page=100"))
    }

    @Test
    fun `createMrNote posts the body json and parses the created note`() {
        responseStatus = 201
        responseBody = """
            {
              "id": 555,
              "body": "Looks **good** to me",
              "system": false,
              "author": {"id": 2, "username": "rev1", "name": "Reviewer One"},
              "created_at": "2026-07-14T09:00:00Z"
            }
        """.trimIndent()
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.createMrNote(123, 42, "Looks **good** to me")
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val note = (result as GitLabResult.Success).data
        assertEquals(555L, note.id)
        assertEquals("Looks **good** to me", note.body)
        assertEquals("rev1", note.author.username)

        assertEquals("POST", method)
        assertTrue("path ends with /notes, was: $path", path!!.endsWith("/merge_requests/42/notes"))
        assertEquals("application/json", contentType)
        assertEquals("""{"body":"Looks **good** to me"}""", requestBody)
    }

    @Test
    fun `approve posts to approve and ignores the 201 json body`() {
        responseStatus = 201
        responseBody = """{"id": 5, "iid": 42, "state": "opened", "approvals_left": 0}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.approveMr(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)

        assertEquals("POST", method)
        assertTrue("path ends with /approve, was: $path", path!!.endsWith("/merge_requests/42/approve"))
        // No JSON body is sent for the bodyless approve.
        assertEquals("", requestBody)
    }

    @Test
    fun `unapprove posts to unapprove and handles a 204 with no body`() {
        responseStatus = 204
        withBody = false
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.unapproveMr(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)

        assertEquals("POST", method)
        assertTrue("path ends with /unapprove, was: $path", path!!.endsWith("/merge_requests/42/unapprove"))
    }

    @Test
    fun `approve maps non-2xx to HttpError`() {
        responseStatus = 401
        responseBody = """{"message":"401 Unauthorized"}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.approveMr(123, 42) }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(401, (result as GitLabResult.HttpError).status)
    }
}
