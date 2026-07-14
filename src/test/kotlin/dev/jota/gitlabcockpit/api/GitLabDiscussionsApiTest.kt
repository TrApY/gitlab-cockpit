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
 * Tests the F3 discussion endpoints of [GitLabApiClient] against a local [HttpServer]: listing the
 * MR's threads (parsing both positioned and position-less notes) and replying to a thread (POST to
 * `/discussions/:id/notes` with a `{ "body": … }` payload).
 */
class GitLabDiscussionsApiTest {

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
    fun `getMrDiscussions parses positioned and position-less notes`() {
        responseStatus = 200
        responseBody = """
            [
              {
                "id": "abc123",
                "notes": [
                  {
                    "id": 1,
                    "type": "DiffNote",
                    "body": "Please rename this",
                    "system": false,
                    "author": {"id": 2, "username": "rev1", "name": "Reviewer One"},
                    "created_at": "2026-07-14T08:00:00Z",
                    "resolvable": true,
                    "resolved": false,
                    "position": {
                      "base_sha": "b",
                      "head_sha": "h",
                      "start_sha": "s",
                      "old_path": "src/App.kt",
                      "new_path": "src/App.kt",
                      "old_line": null,
                      "new_line": 12
                    }
                  }
                ]
              },
              {
                "id": "def456",
                "notes": [
                  {
                    "id": 2,
                    "body": "General comment",
                    "system": false,
                    "author": {"id": 3, "username": "rev2", "name": "Reviewer Two"},
                    "created_at": "2026-07-14T08:10:00Z"
                  }
                ]
              }
            ]
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMrDiscussions(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val discussions = (result as GitLabResult.Success).data
        assertEquals(2, discussions.size)
        assertEquals("abc123", discussions[0].id)

        val positioned = discussions[0].notes.single()
        assertEquals("src/App.kt", positioned.position?.newPath)
        assertEquals(12, positioned.position?.newLine)
        assertNull(positioned.position?.oldLine)
        assertTrue(positioned.resolvable)

        val general = discussions[1].notes.single()
        assertNull("general note has no position", general.position)

        assertEquals("GET", method)
        assertTrue("path ends with /discussions, was: $rawPath", rawPath!!.endsWith("/merge_requests/42/discussions"))
        assertTrue("per_page param, was: $rawQuery", rawQuery!!.contains("per_page=100"))
    }

    @Test
    fun `addDiscussionNote posts the body json to the discussion notes path`() {
        responseStatus = 201
        responseBody = """
            {
              "id": 999,
              "body": "Done, renamed it",
              "system": false,
              "author": {"id": 4, "username": "jota", "name": "Jo Ta"},
              "created_at": "2026-07-14T09:00:00Z"
            }
        """.trimIndent()
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.addDiscussionNote(123, 42, "abc123", "Done, renamed it")
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val note = (result as GitLabResult.Success).data
        assertEquals(999L, note.id)
        assertEquals("Done, renamed it", note.body)
        assertEquals("jota", note.author.username)

        assertEquals("POST", method)
        assertTrue(
            "path is the discussion notes path, was: $rawPath",
            rawPath!!.endsWith("/merge_requests/42/discussions/abc123/notes"),
        )
        assertEquals("application/json", contentType)
        assertEquals("""{"body":"Done, renamed it"}""", requestBody)
    }

    @Test
    fun `createDiffDiscussion posts the body and nested position and parses the discussion`() {
        responseStatus = 201
        responseBody = """
            {
              "id": "newdisc1",
              "notes": [
                {
                  "id": 555,
                  "type": "DiffNote",
                  "body": "Please rename this",
                  "system": false,
                  "author": {"id": 9, "username": "jota", "name": "Jo Ta"},
                  "created_at": "2026-07-15T09:00:00Z",
                  "position": {
                    "base_sha": "b",
                    "start_sha": "s",
                    "head_sha": "h",
                    "old_path": "src/App.kt",
                    "new_path": "src/App.kt",
                    "new_line": 12
                  }
                }
              ]
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
            GitLabApiClient(baseUrl()) { "t" }.createDiffDiscussion(123, 42, "Please rename this", position)
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val discussion = (result as GitLabResult.Success).data
        assertEquals("newdisc1", discussion.id)
        val note = discussion.notes.single()
        assertEquals("src/App.kt", note.position?.newPath)
        assertEquals(12, note.position?.newLine)

        assertEquals("POST", method)
        assertTrue(
            "path is the discussions path, was: $rawPath",
            rawPath!!.endsWith("/merge_requests/42/discussions"),
        )
        assertEquals("application/json", contentType)
        // The body carries the comment and the nested position; the null old_line is omitted and
        // position_type is always "text".
        assertEquals(
            """{"body":"Please rename this","position":{"base_sha":"b","start_sha":"s","head_sha":"h",""" +
                """"position_type":"text","old_path":"src/App.kt","new_path":"src/App.kt","new_line":12}}""",
            requestBody,
        )
    }
}
