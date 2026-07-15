package dev.jota.gitlabcockpit.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies the [GitLabVersion] model tolerates unknown fields and optional revision. */
class GitLabVersionSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unknown fields do not break deserialization`() {
        val payload = """
            {"version":"17.5.1","revision":"deadbeef","enterprise":true,"nested":{"a":1,"b":[2,3]}}
        """.trimIndent()

        val version = json.decodeFromString<GitLabVersion>(payload)

        assertEquals("17.5.1", version.version)
        assertEquals("deadbeef", version.revision)
    }

    @Test
    fun `missing revision defaults to null`() {
        val version = json.decodeFromString<GitLabVersion>("""{"version":"15.0.0"}""")

        assertEquals("15.0.0", version.version)
        assertNull(version.revision)
    }
}
