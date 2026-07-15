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
 * Tests the F2a pipeline endpoints of [GitLabApiClient] against a local [HttpServer]: the two GETs
 * (MR pipelines, pipeline jobs) and the bodyless / bodied POST actions (retry job, play job, cancel
 * pipeline, create pipeline). Verifies HTTP method, path, query params, request body and that a
 * response with no interesting body becomes `Success(Unit)`.
 */
class GitLabPipelinesApiTest {

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

    private fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Longest-prefix match: this single context serves every /projects/123/... path in the test.
        srv.createContext("/api/v4/projects/123") { exchange: HttpExchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
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
    fun `getMrPipelines hits the MR pipelines path and parses them in order`() {
        responseStatus = 200
        responseBody = """
            [
              {
                "id": 201,
                "status": "running",
                "ref": "feature/x",
                "sha": "aaa111",
                "web_url": "https://gitlab.com/g/r/-/pipelines/201",
                "updated_at": "2026-07-14T10:00:00Z",
                "created_at": "2026-07-14T09:00:00Z"
              },
              {
                "id": 200,
                "status": "success",
                "ref": "feature/x",
                "sha": "bbb222",
                "web_url": "https://gitlab.com/g/r/-/pipelines/200"
              }
            ]
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getMrPipelines(123, 42) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val pipelines = (result as GitLabResult.Success).data
        assertEquals(listOf(201L, 200L), pipelines.map { it.id })
        assertEquals("running", pipelines[0].status)
        assertEquals("feature/x", pipelines[0].ref)
        assertEquals("2026-07-14T10:00:00Z", pipelines[0].updatedAt)
        // updated_at / created_at are optional and default to null.
        assertEquals(null, pipelines[1].updatedAt)
        assertEquals(null, pipelines[1].createdAt)

        assertEquals("GET", method)
        assertTrue("path was: $path", path!!.endsWith("/merge_requests/42/pipelines"))
    }

    @Test
    fun `getPipelineJobs hits the jobs path with per_page and parses them`() {
        responseStatus = 200
        responseBody = """
            [
              {
                "id": 900,
                "name": "unit",
                "stage": "test",
                "status": "failed",
                "duration": 12.5,
                "allow_failure": false,
                "web_url": "https://gitlab.com/g/r/-/jobs/900"
              },
              {
                "id": 901,
                "name": "lint",
                "stage": "test",
                "status": "success",
                "allow_failure": true,
                "web_url": "https://gitlab.com/g/r/-/jobs/901"
              }
            ]
        """.trimIndent()
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getPipelineJobs(123, 77) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val jobs = (result as GitLabResult.Success).data
        assertEquals(listOf(900L, 901L), jobs.map { it.id })
        assertEquals("test", jobs[0].stage)
        assertEquals(12.5, jobs[0].duration!!, 0.0001)
        assertEquals(null, jobs[1].duration)
        assertTrue(jobs[1].allowFailure)

        assertEquals("GET", method)
        assertTrue("path was: $path", path!!.endsWith("/pipelines/77/jobs"))
        assertNotNull("query captured", rawQuery)
        assertTrue("per_page param, was: $rawQuery", rawQuery!!.contains("per_page=100"))
    }

    @Test
    fun `retryJob posts to the job retry path with no body`() {
        responseStatus = 201
        responseBody = """{"id": 99, "status": "pending"}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.retryJob(123, 99) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)
        assertEquals("POST", method)
        assertTrue("path was: $path", path!!.endsWith("/jobs/99/retry"))
        assertEquals("", requestBody)
    }

    @Test
    fun `playJob posts to the job play path`() {
        responseStatus = 200
        responseBody = """{"id": 99, "status": "pending"}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.playJob(123, 99) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)
        assertEquals("POST", method)
        assertTrue("path was: $path", path!!.endsWith("/jobs/99/play"))
    }

    @Test
    fun `cancelPipeline posts to the pipeline cancel path`() {
        responseStatus = 200
        responseBody = """{"id": 77, "status": "canceled"}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.cancelPipeline(123, 77) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)
        assertEquals("POST", method)
        assertTrue("path was: $path", path!!.endsWith("/pipelines/77/cancel"))
    }

    @Test
    fun `createPipeline posts the ref json body to the pipeline path`() {
        responseStatus = 201
        responseBody = """{"id": 202, "status": "created", "ref": "feature/x"}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.createPipeline(123, "feature/x") }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals(Unit, (result as GitLabResult.Success).data)
        assertEquals("POST", method)
        assertTrue("path was: $path", path!!.endsWith("/projects/123/pipeline"))
        assertEquals("application/json", contentType)
        assertEquals("""{"ref":"feature/x"}""", requestBody)
    }

    @Test
    fun `retryPipeline maps non-2xx to HttpError`() {
        responseStatus = 403
        responseBody = """{"message":"403 Forbidden"}"""
        startServer()

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.retryPipeline(123, 77) }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(403, (result as GitLabResult.HttpError).status)
        assertTrue("path was: $path", path!!.endsWith("/pipelines/77/retry"))
    }
}
