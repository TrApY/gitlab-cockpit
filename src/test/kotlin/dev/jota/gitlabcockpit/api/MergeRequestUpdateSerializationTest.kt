package dev.jota.gitlabcockpit.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies [MergeRequestUpdate] serializes with the same encoder the client uses
 * (`encodeDefaults = false` + `explicitNulls = false`): null fields are omitted, while a non-null
 * empty list is still written as `[]`.
 */
class MergeRequestUpdateSerializationTest {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    private fun encode(update: MergeRequestUpdate): String =
        json.encodeToString(MergeRequestUpdate.serializer(), update)

    @Test
    fun `title only omits the other null fields`() {
        assertEquals("""{"title":"New title"}""", encode(MergeRequestUpdate(title = "New title")))
    }

    @Test
    fun `description only omits the other null fields`() {
        assertEquals("""{"description":"body"}""", encode(MergeRequestUpdate(description = "body")))
    }

    @Test
    fun `empty reviewer list serializes as an empty array`() {
        assertEquals("""{"reviewer_ids":[]}""", encode(MergeRequestUpdate(reviewerIds = emptyList())))
    }

    @Test
    fun `empty assignee list serializes as an empty array`() {
        assertEquals("""{"assignee_ids":[]}""", encode(MergeRequestUpdate(assigneeIds = emptyList())))
    }

    @Test
    fun `populated ids use snake_case keys`() {
        assertEquals(
            """{"reviewer_ids":[2,3],"assignee_ids":[7]}""",
            encode(MergeRequestUpdate(reviewerIds = listOf(2L, 3L), assigneeIds = listOf(7L))),
        )
    }

    @Test
    fun `all-null update serializes to an empty object`() {
        assertEquals("{}", encode(MergeRequestUpdate()))
    }
}
