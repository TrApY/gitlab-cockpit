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
 * Tests [GitLabApiClient.getProjectLabels] (GLC-42) against a local [HttpServer]: verifies the GET path
 * and query params, that only [GitLabLabel.name] / [GitLabLabel.color] are read (extra fields ignored),
 * and that a non-2xx becomes [GitLabResult.HttpError].
 */
class GitLabProjectLabelsApiTest {

    private var server: HttpServer? = null

    @Volatile
    private var requestUri: String? = null

    @Volatile
    private var method: String? = null

    private fun startServer(status: Int, body: String) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/projects/123/labels") { exchange: HttpExchange ->
            method = exchange.requestMethod
            requestUri = exchange.requestURI.toString()
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
    fun `GET parses labels keeping only name and color`() {
        startServer(
            200,
            """
            [
              {"id": 1, "name": "frontend", "color": "#d9534f", "text_color": "#FFFFFF", "description": "UI"},
              {"id": 2, "name": "ci", "color": "#428bca", "open_issues_count": 3}
            ]
            """.trimIndent(),
        )

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getProjectLabels(123) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val labels = (result as GitLabResult.Success).data
        assertEquals(listOf("frontend", "ci"), labels.map { it.name })
        assertEquals(listOf("#d9534f", "#428bca"), labels.map { it.color })

        assertEquals("GET", method)
        val uri = requestUri
        assertTrue("per_page in query, was: $uri", uri!!.contains("per_page=100"))
        assertTrue("include_ancestor_groups in query, was: $uri", uri.contains("include_ancestor_groups=true"))
    }

    @Test
    fun `non-2xx yields HttpError`() {
        startServer(404, """{"message":"404 Project Not Found"}""")

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getProjectLabels(123) }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(404, (result as GitLabResult.HttpError).status)
    }
}
