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
 * Tests the GLC-40 endpoints of [GitLabApiClient] against a local [HttpServer]: editing / deleting a
 * note ([GitLabApiClient.updateMrNote] / [GitLabApiClient.deleteMrNote]) and the emoji-reaction CRUD
 * ([GitLabApiClient.getNoteAwards] / [GitLabApiClient.addNoteAward] / [GitLabApiClient.deleteNoteAward]).
 * Verifies HTTP method, path, the `name` query param, request body and JSON decoding.
 */
class GitLabNoteAwardsApiTest {

    private var server: HttpServer? = null

    @Volatile private var method: String? = null
    @Volatile private var path: String? = null
    @Volatile private var rawQuery: String? = null
    @Volatile private var contentType: String? = null
    @Volatile private var requestBody: String? = null
    @Volatile private var responseStatus: Int = 200
    @Volatile private var responseBody: String = ""
    @Volatile private var withBody: Boolean = true

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Longest-prefix match also serves /notes/:id and /notes/:id/award_emoji(/:id).
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
    fun `updateMrNote puts the body json and parses the updated note`() {
        responseStatus = 200
        responseBody = """
            {
              "id": 777,
              "body": "Edited body",
              "system": false,
              "author": {"id": 2, "username": "jota", "name": "Jo Ta"},
              "created_at": "2026-07-14T09:00:00Z"
            }
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.updateMrNote(123, 42, 777, "Edited body") }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val note = (result as GitLabResult.Success).data
        assertEquals(777L, note.id)
        assertEquals("Edited body", note.body)

        assertEquals("PUT", method)
        assertTrue("path ends with /notes/777, was: $path", path!!.endsWith("/merge_requests/42/notes/777"))
        assertEquals("application/json", contentType)
        assertEquals("""{"body":"Edited body"}""", requestBody)
    }

    @Test
    fun `deleteMrNote deletes the note and handles a 204`() {
        responseStatus = 204
        withBody = false
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.deleteMrNote(123, 42, 777) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)
        assertEquals("DELETE", method)
        assertTrue("path ends with /notes/777, was: $path", path!!.endsWith("/merge_requests/42/notes/777"))
    }

    @Test
    fun `getNoteAwards parses the awards with their reactor`() {
        responseStatus = 200
        responseBody = """
            [
              {"id": 1, "name": "thumbsup", "user": {"id": 2, "username": "jota", "name": "Jo Ta"}},
              {"id": 2, "name": "rocket", "user": {"id": 3, "username": "rev", "name": "Rev Iewer"}}
            ]
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getNoteAwards(123, 42, 777) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val awards = (result as GitLabResult.Success).data
        assertEquals(2, awards.size)
        assertEquals(listOf("thumbsup", "rocket"), awards.map { it.name })
        assertEquals(2L, awards[0].user.id)

        assertEquals("GET", method)
        assertTrue("path ends with /award_emoji, was: $path", path!!.endsWith("/notes/777/award_emoji"))
    }

    @Test
    fun `addNoteAward posts with the name query param and parses the created award`() {
        responseStatus = 201
        responseBody = """{"id": 9, "name": "tada", "user": {"id": 2, "username": "jota", "name": "Jo Ta"}}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.addNoteAward(123, 42, 777, "tada") }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val award = (result as GitLabResult.Success).data
        assertEquals(9L, award.id)
        assertEquals("tada", award.name)

        assertEquals("POST", method)
        assertTrue("path ends with /award_emoji, was: $path", path!!.endsWith("/notes/777/award_emoji"))
        val query = rawQuery
        assertNotNull("query captured", query)
        assertTrue("name param, was: $query", query!!.contains("name=tada"))
        // No JSON body is sent — the emoji name travels as a query param.
        assertEquals("", requestBody)
    }

    @Test
    fun `deleteNoteAward deletes the award and handles a 204`() {
        responseStatus = 204
        withBody = false
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.deleteNoteAward(123, 42, 777, 9) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)
        assertEquals("DELETE", method)
        assertTrue("path ends with /award_emoji/9, was: $path", path!!.endsWith("/notes/777/award_emoji/9"))
    }
}
