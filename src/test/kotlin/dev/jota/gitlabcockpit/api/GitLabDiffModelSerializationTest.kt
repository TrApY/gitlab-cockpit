package dev.jota.gitlabcockpit.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the F3 diff models tolerate the real GitLab payload shape (unknown fields, defaults). */
class GitLabDiffModelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `diff refs parse the three shas and ignore unknown fields`() {
        val refs = json.decodeFromString<DiffRefs>(
            """{"base_sha": "bbb", "head_sha": "hhh", "start_sha": "sss", "extra": "ignored"}""",
        )

        assertEquals("bbb", refs.baseSha)
        assertEquals("hhh", refs.headSha)
        assertEquals("sss", refs.startSha)
    }

    @Test
    fun `diff file parses paths and defaults its flags to false`() {
        val file = json.decodeFromString<GitLabDiffFile>(
            """
            {
              "old_path": "src/App.kt",
              "new_path": "src/App.kt",
              "a_mode": "100644",
              "b_mode": "100644",
              "diff": "@@ -1 +1 @@",
              "generated_file": false
            }
            """.trimIndent(),
        )

        assertEquals("src/App.kt", file.oldPath)
        assertEquals("src/App.kt", file.newPath)
        assertFalse(file.newFile)
        assertFalse(file.renamedFile)
        assertFalse(file.deletedFile)
    }

    @Test
    fun `diff file parses the change flags when present`() {
        val renamed = json.decodeFromString<GitLabDiffFile>(
            """{"old_path": "old.kt", "new_path": "new.kt", "renamed_file": true}""",
        )
        assertTrue(renamed.renamedFile)
        assertFalse(renamed.newFile)
        assertFalse(renamed.deletedFile)

        val deleted = json.decodeFromString<GitLabDiffFile>(
            """{"old_path": "gone.kt", "new_path": "gone.kt", "deleted_file": true}""",
        )
        assertTrue(deleted.deletedFile)
    }

    @Test
    fun `merge request detail exposes diff refs while the list shape leaves them null`() {
        val detail = json.decodeFromString<GitLabMergeRequest>(
            """
            {
              "iid": 42,
              "project_id": 7,
              "title": "With refs",
              "state": "opened",
              "source_branch": "s",
              "target_branch": "t",
              "web_url": "https://gitlab.com/x/-/merge_requests/42",
              "updated_at": "2026-07-14T09:30:00Z",
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
              "diff_refs": {"base_sha": "b", "head_sha": "h", "start_sha": "s"}
            }
            """.trimIndent(),
        )
        assertNotNull(detail.diffRefs)
        assertEquals("b", detail.diffRefs?.baseSha)
        assertEquals("h", detail.diffRefs?.headSha)

        val listShape = json.decodeFromString<GitLabMergeRequest>(
            """
            {
              "iid": 7,
              "project_id": 7,
              "title": "No refs",
              "state": "opened",
              "source_branch": "s",
              "target_branch": "t",
              "web_url": "https://gitlab.com/x/-/merge_requests/7",
              "updated_at": "2026-07-14T09:30:00Z",
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"}
            }
            """.trimIndent(),
        )
        assertNull(listShape.diffRefs)
    }
}
