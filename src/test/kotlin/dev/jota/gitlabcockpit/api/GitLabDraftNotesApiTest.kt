package dev.jota.gitlabcockpit.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Tests the F4b draft-note, review-submission and thread-resolution endpoints of [GitLabApiClient]
 * against a local [HttpServer]: listing drafts (with and without a diff position), creating a draft
 * (general and diff-anchored bodies), deleting a draft (a bodyless `DELETE`), bulk-publishing the
 * review (`POST …/draft_notes/bulk_publish`) and resolving/reopening a discussion (a `PUT` whose
 * `{ "resolved": … }` body is asserted for both values, and whose returned discussion is ignored).
 */
class GitLabDraftNotesApiTest {

    private var server: HttpServer? = null

    @Volatile private var method: String? = null
    @Volatile private var rawPath: String? = null
    @Volatile private var rawQuery: String? = null
    @Volatile private var contentType: String? = null
    @Volatile private var requestBody: String? = null
    @Volatile private var responseStatus: Int = 200
    @Volatile private var responseBody: String = ""

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { exchange: HttpExchange ->
            method = exchange.requestMethod
            rawPath = exchange.requestURI.rawPath
            rawQuery = exchange.requestURI.rawQuery
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            if (responseStatus == 204 || bytes.isEmpty()) {
                exchange.sendResponseHeaders(responseStatus, -1)
                exchange.responseBody.close()
            } else {
                exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
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
    fun `getDraftNotes parses drafts with and without a position`() {
        responseStatus = 200
        responseBody = """
            [
              {
                "id": 10,
                "note": "Please rename this",
                "merge_request_id": 5,
                "author_id": 9,
                "resolve_discussion": false,
                "position": {
                  "base_sha": "b",
                  "head_sha": "h",
                  "start_sha": "s",
                  "old_path": "src/App.kt",
                  "new_path": "src/App.kt",
                  "old_line": null,
                  "new_line": 12
                }
              },
              {
                "id": 11,
                "note": "General draft",
                "merge_request_id": 5,
                "author_id": 9
              }
            ]
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getDraftNotes(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val drafts = (result as GitLabResult.Success).data
        assertEquals(2, drafts.size)

        val positioned = drafts[0]
        assertEquals(10L, positioned.id)
        assertEquals("Please rename this", positioned.note)
        assertEquals("src/App.kt", positioned.position?.newPath)
        assertEquals(12, positioned.position?.newLine)
        assertNull(positioned.position?.oldLine)

        val general = drafts[1]
        assertEquals(11L, general.id)
        assertNull("general draft has no position", general.position)

        assertEquals("GET", method)
        assertTrue(
            "path ends with /draft_notes, was: $rawPath",
            rawPath!!.endsWith("/merge_requests/42/draft_notes"),
        )
        assertTrue("per_page param, was: $rawQuery", rawQuery!!.contains("per_page=100"))
    }

    @Test
    fun `createDraftNote without position posts only the note body`() {
        responseStatus = 201
        responseBody = """
            {
              "id": 55,
              "note": "General draft",
              "merge_request_id": 5,
              "author_id": 9
            }
        """.trimIndent()
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.createDraftNote(123, 42, "General draft")
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val draft = (result as GitLabResult.Success).data
        assertEquals(55L, draft.id)
        assertEquals("General draft", draft.note)
        assertNull(draft.position)

        assertEquals("POST", method)
        assertTrue(
            "path is the draft_notes path, was: $rawPath",
            rawPath!!.endsWith("/merge_requests/42/draft_notes"),
        )
        assertEquals("application/json", contentType)
        assertEquals("""{"note":"General draft"}""", requestBody)
    }

    @Test
    fun `createDraftNote with position posts the note and nested position`() {
        responseStatus = 201
        responseBody = """
            {
              "id": 56,
              "note": "Please rename this",
              "position": {
                "base_sha": "b",
                "start_sha": "s",
                "head_sha": "h",
                "old_path": "src/App.kt",
                "new_path": "src/App.kt",
                "new_line": 12
              }
            }
        """.trimIndent()
        startServer()

        val position = PositionPayload(
            baseSha = "b",
            startSha = "s",
            headSha = "h",
            oldPath = "src/App.kt",
            newPath = "src/App.kt",
            oldLine = null,
            newLine = 12,
        )
        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.createDraftNote(123, 42, "Please rename this", position)
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val draft = (result as GitLabResult.Success).data
        assertEquals(56L, draft.id)
        assertEquals(12, draft.position?.newLine)

        assertEquals("POST", method)
        assertTrue(
            "path is the draft_notes path, was: $rawPath",
            rawPath!!.endsWith("/merge_requests/42/draft_notes"),
        )
        assertEquals("application/json", contentType)
        // The null old_line is omitted and position_type is always "text".
        assertEquals(
            """{"note":"Please rename this","position":{"base_sha":"b","start_sha":"s","head_sha":"h",""" +
                """"position_type":"text","old_path":"src/App.kt","new_path":"src/App.kt","new_line":12}}""",
            requestBody,
        )
    }

    @Test
    fun `deleteDraftNote issues a DELETE to the exact draft path and treats 204 as success`() {
        responseStatus = 204
        responseBody = ""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.deleteDraftNote(123, 42, 55) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)

        assertEquals("DELETE", method)
        assertEquals("/api/v4/projects/123/merge_requests/42/draft_notes/55", rawPath)
    }

    @Test
    fun `publishAllDraftNotes posts to bulk_publish and treats 204 as success`() {
        responseStatus = 204
        responseBody = ""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.publishAllDraftNotes(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)

        assertEquals("POST", method)
        assertEquals("/api/v4/projects/123/merge_requests/42/draft_notes/bulk_publish", rawPath)
    }

    @Test
    fun `resolveDiscussion puts resolved true to the exact discussion path`() {
        responseStatus = 200
        // GitLab echoes the updated discussion; the client ignores it (null deserializer).
        responseBody = """{"id":"abc123","notes":[]}"""
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.resolveDiscussion(123, 42, "abc123", true)
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)

        assertEquals("PUT", method)
        assertEquals("/api/v4/projects/123/merge_requests/42/discussions/abc123", rawPath)
        assertEquals("application/json", contentType)
        assertEquals("""{"resolved":true}""", requestBody)
    }

    @Test
    fun `resolveDiscussion puts resolved false when reopening`() {
        responseStatus = 200
        responseBody = """{"id":"abc123","notes":[]}"""
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.resolveDiscussion(123, 42, "abc123", false)
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)

        assertEquals("PUT", method)
        assertEquals("/api/v4/projects/123/merge_requests/42/discussions/abc123", rawPath)
        assertEquals("""{"resolved":false}""", requestBody)
    }
}
