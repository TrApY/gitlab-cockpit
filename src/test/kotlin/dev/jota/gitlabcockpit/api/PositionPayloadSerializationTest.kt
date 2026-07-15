package dev.jota.gitlabcockpit.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [PositionPayload] serializes with the same encoder the client uses for the diff-discussion
 * body (`encodeDefaults = true` + `explicitNulls = false`): null fields are omitted, while the
 * `position_type` default is always emitted as `text`.
 */
class PositionPayloadSerializationTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private fun encode(position: PositionPayload): String =
        json.encodeToString(PositionPayload.serializer(), position)

    @Test
    fun `a new-side position omits the null old_line but keeps position_type`() {
        val encoded = encode(
            PositionPayload(
                baseSha = "b",
                startSha = "s",
                headSha = "h",
                oldPath = "src/App.kt",
                newPath = "src/App.kt",
                oldLine = null,
                newLine = 12,
            ),
        )

        assertEquals(
            """{"base_sha":"b","start_sha":"s","head_sha":"h","position_type":"text",""" +
                """"old_path":"src/App.kt","new_path":"src/App.kt","new_line":12}""",
            encoded,
        )
        assertTrue("position_type present", encoded.contains(""""position_type":"text""""))
        assertFalse("old_line omitted when null", encoded.contains("old_line"))
    }

    @Test
    fun `an old-side position omits the null new_line`() {
        val encoded = encode(
            PositionPayload(
                baseSha = "b",
                startSha = "s",
                headSha = "h",
                oldPath = "old/Gone.kt",
                newPath = "old/Gone.kt",
                oldLine = 3,
                newLine = null,
            ),
        )

        assertTrue("old_line present", encoded.contains(""""old_line":3"""))
        assertFalse("new_line omitted when null", encoded.contains("new_line"))
        assertTrue("position_type present", encoded.contains(""""position_type":"text""""))
    }

    @Test
    fun `a context position keeps both old_line and new_line`() {
        val encoded = encode(
            PositionPayload(
                baseSha = "b",
                startSha = "s",
                headSha = "h",
                oldPath = "src/App.kt",
                newPath = "src/App.kt",
                oldLine = 4,
                newLine = 4,
            ),
        )

        assertTrue(encoded.contains(""""old_line":4"""))
        assertTrue(encoded.contains(""""new_line":4"""))
    }
}
