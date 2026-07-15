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
 * Tests the paginated [GitLabApiClient.getProjectMembers] against a local [HttpServer]: a full first
 * page (100) followed by a short page is concatenated and stops, while an error on a later page
 * short-circuits to that error rather than the partial accumulation.
 */
class GitLabMembersPaginationApiTest {

    private var server: HttpServer? = null

    private fun usersJson(ids: IntRange): String =
        ids.joinToString(",", "[", "]") { """{"id":$it,"username":"u$it","name":"User $it"}""" }

    /** Starts a server whose `members/all` response is decided per requested `page` by [handler]. */
    private fun startServer(handler: (page: Int) -> Pair<Int, String>) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/v4/projects/123/members/all") { exchange: HttpExchange ->
            val query = exchange.requestURI.rawQuery ?: ""
            // Parse the exact `page` param (not the `page` inside `per_page`).
            val page = query.split("&")
                .firstOrNull { it.startsWith("page=") }
                ?.substringAfter("=")?.toInt() ?: 1
            val (status, body) = handler(page)
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
    fun `a full page followed by a short page is concatenated`() {
        startServer { page ->
            when (page) {
                1 -> 200 to usersJson(1..100)   // full page → keep paging
                else -> 200 to usersJson(101..105) // short page → last one
            }
        }

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getProjectMembers(123) }

        assertTrue("expected Success but was $result", result is GitLabResult.Success)
        val members = (result as GitLabResult.Success).data
        assertEquals(105, members.size)
        assertEquals(1L, members.first().id)
        assertEquals(105L, members.last().id)
    }

    @Test
    fun `an error on a later page short-circuits to that error`() {
        startServer { page ->
            when (page) {
                1 -> 200 to usersJson(1..100)     // full page → a second page is requested
                else -> 500 to """{"message":"boom"}"""
            }
        }

        val result = runBlocking { GitLabApiClient(baseUrl()) { "t" }.getProjectMembers(123) }

        assertTrue("expected HttpError but was $result", result is GitLabResult.HttpError)
        assertEquals(500, (result as GitLabResult.HttpError).status)
    }
}
