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
 * Tests the F3 diff endpoints of [GitLabApiClient] against a local [HttpServer]: the MR diffs list
 * and the raw-file fetch. Verifies HTTP method, path (including the `%2F`-encoded file path), query
 * params and that a raw text body is returned verbatim.
 */
class GitLabDiffsApiTest {

    private var server: HttpServer? = null

    @Volatile private var method: String? = null
    @Volatile private var rawPath: String? = null
    @Volatile private var rawQuery: String? = null
    @Volatile private var responseStatus: Int = 200
    @Volatile private var responseBody: String = ""

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { exchange: HttpExchange ->
            method = exchange.requestMethod
            rawPath = exchange.requestURI.rawPath
            rawQuery = exchange.requestURI.rawQuery
            exchange.requestBody.readBytes()
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
    fun `getMrDiffs parses the changed files with per_page and defaults`() {
        responseStatus = 200
        responseBody = """
            [
              {
                "old_path": "src/App.kt",
                "new_path": "src/App.kt",
                "a_mode": "100644",
                "b_mode": "100644",
                "diff": "@@ -1 +1 @@",
                "new_file": false,
                "renamed_file": false,
                "deleted_file": false
              },
              {
                "old_path": "src/Added.kt",
                "new_path": "src/Added.kt",
                "new_file": true
              },
              {
                "old_path": "old/Name.kt",
                "new_path": "new/Name.kt",
                "renamed_file": true
              }
            ]
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMrDiffs(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val files = (result as GitLabResult.Success).data
        assertEquals(3, files.size)
        assertEquals("src/App.kt", files[0].newPath)
        assertEquals(false, files[0].newFile)
        assertEquals(false, files[0].renamedFile)
        assertEquals(false, files[0].deletedFile)
        assertTrue(files[1].newFile)
        assertTrue(files[2].renamedFile)
        assertEquals("old/Name.kt", files[2].oldPath)

        assertEquals("GET", method)
        assertTrue("path ends with /diffs, was: $rawPath", rawPath!!.endsWith("/merge_requests/42/diffs"))
        assertTrue("per_page param, was: $rawQuery", rawQuery!!.contains("per_page=100"))
    }

    @Test
    fun `getRawFile encodes the file path as one segment and passes ref`() {
        responseStatus = 200
        responseBody = "line 1\nline 2\n"
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.getRawFile(7, "src/main/App.kt", "abc123")
        }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals("line 1\nline 2\n", (result as GitLabResult.Success).data)

        assertEquals("GET", method)
        val path = rawPath
        assertNotNull("path captured", path)
        // The '/' in the file path must be encoded as %2F (single path segment).
        assertTrue("path contains %2F-encoded file path, was: $path", path!!.contains("src%2Fmain%2FApp.kt"))
        assertTrue("path ends with /raw, was: $path", path.endsWith("/raw"))
        assertTrue("path targets the files endpoint, was: $path", path.contains("/repository/files/"))
        assertTrue("ref query, was: $rawQuery", rawQuery!!.contains("ref=abc123"))
    }

    @Test
    fun `getRawFile maps a 404 to HttpError`() {
        responseStatus = 404
        responseBody = """{"message":"404 File Not Found"}"""
        startServer()

        val result = runBlocking {
            GitLabApiClient(baseUrl()) { "t" }.getRawFile(7, "missing.kt", "abc123")
        }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(404, (result as GitLabResult.HttpError).status)
    }
}
