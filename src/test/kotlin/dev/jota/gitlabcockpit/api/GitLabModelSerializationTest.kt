package dev.jota.gitlabcockpit.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the merge-request and approvals models tolerate the real GitLab payload shape. */
class GitLabModelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `merge request with reviewers assignees and unknown fields`() {
        val payload = """
            {
              "id": 987654,
              "iid": 42,
              "project_id": 100,
              "title": "Add cockpit tool window",
              "state": "opened",
              "source_branch": "feature/h2",
              "target_branch": "develop",
              "web_url": "https://gitlab.com/g/r/-/merge_requests/42",
              "updated_at": "2026-07-14T09:30:00.000Z",
              "draft": true,
              "has_conflicts": true,
              "merge_status": "can_be_merged",
              "labels": ["frontend", "ci"],
              "author": {"id": 1, "username": "jota", "name": "Jo Ta", "state": "active"},
              "reviewers": [
                {"id": 2, "username": "rev1", "name": "Reviewer One"},
                {"id": 3, "username": "rev2", "name": "Reviewer Two"}
              ],
              "assignees": [
                {"id": 4, "username": "asg1", "name": "Assignee One"}
              ]
            }
        """.trimIndent()

        val mr = json.decodeFromString<GitLabMergeRequest>(payload)

        assertEquals(42L, mr.iid)
        assertEquals("Add cockpit tool window", mr.title)
        assertEquals("opened", mr.state)
        assertEquals("feature/h2", mr.sourceBranch)
        assertEquals("develop", mr.targetBranch)
        assertEquals("2026-07-14T09:30:00.000Z", mr.updatedAt)
        assertTrue(mr.draft)
        assertTrue(mr.hasConflicts)
        assertEquals("jota", mr.author.username)
        assertEquals(listOf("rev1", "rev2"), mr.reviewers.map { it.username })
        assertEquals(listOf("asg1"), mr.assignees.map { it.username })
    }

    @Test
    fun `merge request defaults when optional fields are absent`() {
        val payload = """
            {
              "iid": 7,
              "title": "Minimal",
              "state": "merged",
              "source_branch": "s",
              "target_branch": "t",
              "web_url": "https://gitlab.com/x/-/merge_requests/7",
              "updated_at": "2026-01-01T00:00:00Z",
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"}
            }
        """.trimIndent()

        val mr = json.decodeFromString<GitLabMergeRequest>(payload)

        assertFalse(mr.draft)
        assertFalse(mr.hasConflicts)
        assertTrue(mr.reviewers.isEmpty())
        assertTrue(mr.assignees.isEmpty())
    }

    @Test
    fun `approvals payload with approved_by users`() {
        val payload = """
            {
              "id": 5,
              "iid": 42,
              "approvals_required": 2,
              "approvals_left": 1,
              "approved_by": [
                {"user": {"id": 2, "username": "rev1", "name": "Reviewer One"}},
                {"user": {"id": 3, "username": "rev2", "name": "Reviewer Two"}}
              ]
            }
        """.trimIndent()

        val approvals = json.decodeFromString<GitLabApprovals>(payload)

        assertEquals(listOf(2L, 3L), approvals.approvedBy.map { it.user.id })
        assertEquals(listOf("rev1", "rev2"), approvals.approvedBy.map { it.user.username })
    }

    @Test
    fun `approvals payload with empty approved_by`() {
        val approvals = json.decodeFromString<GitLabApprovals>(
            """{"approvals_required": 1, "approved_by": []}""",
        )

        assertTrue(approvals.approvedBy.isEmpty())
    }

    @Test
    fun `note tolerates unknown fields and defaults system to false`() {
        val payload = """
            {
              "id": 101,
              "type": null,
              "body": "Looks good to me",
              "attachment": null,
              "author": {"id": 2, "username": "rev1", "name": "Reviewer One", "state": "active"},
              "created_at": "2026-07-14T10:00:00.000Z",
              "updated_at": "2026-07-14T10:00:00.000Z",
              "noteable_id": 5,
              "noteable_type": "MergeRequest",
              "resolvable": false
            }
        """.trimIndent()

        val note = json.decodeFromString<GitLabNote>(payload)

        assertEquals(101L, note.id)
        assertEquals("Looks good to me", note.body)
        assertFalse(note.system)
        assertEquals("rev1", note.author.username)
        assertEquals("2026-07-14T10:00:00.000Z", note.createdAt)
    }

    @Test
    fun `system note parses the system flag`() {
        val note = json.decodeFromString<GitLabNote>(
            """
            {
              "id": 9,
              "body": "changed the description",
              "system": true,
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
              "created_at": "2026-07-14T09:00:00Z"
            }
            """.trimIndent(),
        )

        assertTrue(note.system)
    }
}
