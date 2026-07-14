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
 * Pure-logic tests for [GitLabApiClient] against a local [HttpServer] from the JDK.
 * No IntelliJ platform fixtures are involved.
 */
class GitLabApiClientTest {

    private var server: HttpServer? = null

    @Volatile
    private var receivedToken: String? = null

    private fun startServer(status: Int, body: String) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/version") { exchange: HttpExchange ->
            receivedToken = exchange.requestHeaders.getFirst("PRIVATE-TOKEN")
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
    fun `200 with valid json returns Success and sends PRIVATE-TOKEN header`() {
        startServer(200, """{"version":"17.1.0","revision":"abcd1234","enterprise":true}""")

        val result = runBlocking { GitLabApiClient(baseUrl()) { "secret-token" }.getVersion() }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val success = result as GitLabResult.Success
        assertEquals("17.1.0", success.data.version)
        assertEquals("abcd1234", success.data.revision)
        assertEquals("secret-token", receivedToken)
    }

    @Test
    fun `401 returns HttpError with status`() {
        startServer(401, """{"message":"401 Unauthorized"}""")

        val result = runBlocking { GitLabApiClient(baseUrl()) { "bad-token" }.getVersion() }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(401, (result as GitLabResult.HttpError).status)
    }

    @Test
    fun `closed port returns NetworkError`() {
        // Bind + release an ephemeral port so connecting to it is refused.
        val probe = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        probe.start()
        val port = probe.address.port
        probe.stop(0)

        val result = runBlocking { GitLabApiClient("http://127.0.0.1:$port") { "x" }.getVersion() }

        assertTrue("expected NetworkError but was $result", result is GitLabResult.NetworkError)
    }

    @Test
    fun `trailing slash base url still reaches api v4 version endpoint`() {
        startServer(200, """{"version":"16.11.0"}""")

        // baseUrl ends with a slash and has no /api/v4 — normalization must still hit the context.
        val result = runBlocking { GitLabApiClient("${baseUrl()}/") { "t" }.getVersion() }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        assertEquals("16.11.0", (result as GitLabResult.Success).data.version)
    }

    @Test
    fun `normalizeBaseUrl handles trailing slash and existing api v4 suffix`() {
        assertEquals("https://gitlab.com/api/v4", GitLabApiClient.normalizeBaseUrl("https://gitlab.com"))
        assertEquals("https://gitlab.com/api/v4", GitLabApiClient.normalizeBaseUrl("https://gitlab.com/"))
        assertEquals("https://gitlab.com/api/v4", GitLabApiClient.normalizeBaseUrl("https://gitlab.com/api/v4"))
        assertEquals("https://gitlab.com/api/v4", GitLabApiClient.normalizeBaseUrl("https://gitlab.com/api/v4/"))
        assertEquals("https://gitlab.com/api/v4", GitLabApiClient.normalizeBaseUrl("  https://gitlab.com//  "))
    }
}
