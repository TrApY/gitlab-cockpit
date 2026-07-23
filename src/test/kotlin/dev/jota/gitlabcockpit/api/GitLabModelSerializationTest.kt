package dev.jota.gitlabcockpit.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
              "source_project_id": 100,
              "labels": ["frontend", "ci"],
              "author": {"id": 1, "username": "jota", "name": "Jo Ta", "state": "active"},
              "references": {"short": "!42", "relative": "!42", "full": "group/project!42"},
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
        assertEquals(100L, mr.projectId)
        assertEquals("group/project!42", mr.references?.full)
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
        // GLC-42: labels parse as a plain list of names; a same-project MR's source_project_id == project_id.
        assertEquals(listOf("frontend", "ci"), mr.labels)
        assertEquals(100L, mr.sourceProjectId)
    }

    @Test
    fun `merge request defaults when optional fields are absent`() {
        val payload = """
            {
              "iid": 7,
              "project_id": 55,
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

        assertEquals(55L, mr.projectId)
        assertFalse(mr.draft)
        assertFalse(mr.hasConflicts)
        assertTrue(mr.reviewers.isEmpty())
        assertTrue(mr.assignees.isEmpty())
        assertNull(mr.headPipeline)
        assertNull(mr.references)
        // Merge-related fields (GLC-26) default when absent.
        assertNull(mr.detailedMergeStatus)
        assertNull(mr.createdAt)
        assertNull(mr.mergedAt)
        assertNull(mr.closedAt)
        assertFalse(mr.squash)
        assertNull(mr.forceRemoveSourceBranch)
        // Notification fields (GLC-27) default to null when absent.
        assertNull(mr.sha)
        assertNull(mr.userNotesCount)
        // GLC-42 fields default when absent.
        assertNull(mr.sourceProjectId)
        assertTrue(mr.labels.isEmpty())
    }

    @Test
    fun `merge request parses sha and user_notes_count from the list endpoint`() {
        val payload = """
            {
              "iid": 42,
              "project_id": 100,
              "title": "Watched MR",
              "state": "opened",
              "source_branch": "feature",
              "target_branch": "main",
              "web_url": "https://gitlab.com/g/r/-/merge_requests/42",
              "updated_at": "2026-07-15T10:00:00Z",
              "sha": "abc123def456",
              "user_notes_count": 7,
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"}
            }
        """.trimIndent()

        val mr = json.decodeFromString<GitLabMergeRequest>(payload)

        assertEquals("abc123def456", mr.sha)
        assertEquals(7, mr.userNotesCount)
    }

    @Test
    fun `merge request parses the merge status dates squash and force_remove_source_branch`() {
        val payload = """
            {
              "iid": 42,
              "project_id": 100,
              "title": "Mergeable MR",
              "state": "merged",
              "source_branch": "feature",
              "target_branch": "main",
              "web_url": "https://gitlab.com/g/r/-/merge_requests/42",
              "updated_at": "2026-07-15T10:00:00Z",
              "created_at": "2026-07-14T08:00:00Z",
              "merged_at": "2026-07-15T09:59:00Z",
              "closed_at": null,
              "detailed_merge_status": "mergeable",
              "squash": true,
              "force_remove_source_branch": true,
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"}
            }
        """.trimIndent()

        val mr = json.decodeFromString<GitLabMergeRequest>(payload)

        assertEquals("mergeable", mr.detailedMergeStatus)
        assertEquals("2026-07-14T08:00:00Z", mr.createdAt)
        assertEquals("2026-07-15T09:59:00Z", mr.mergedAt)
        assertNull(mr.closedAt)
        assertTrue(mr.squash)
        assertEquals(true, mr.forceRemoveSourceBranch)
    }

    @Test
    fun `approvals parses approvals_required and approvals_left when present`() {
        val approvals = json.decodeFromString<GitLabApprovals>(
            """{"approvals_required": 2, "approvals_left": 1, "approved_by": []}""",
        )

        assertEquals(2, approvals.approvalsRequired)
        assertEquals(1, approvals.approvalsLeft)
    }

    @Test
    fun `approvals defaults approvals_required and approvals_left to null when absent`() {
        val approvals = json.decodeFromString<GitLabApprovals>("""{"approved_by": []}""")

        assertNull(approvals.approvalsRequired)
        assertNull(approvals.approvalsLeft)
    }

    @Test
    fun `merge request detail parses head_pipeline including a null ref`() {
        val payload = """
            {
              "iid": 42,
              "project_id": 77,
              "title": "External CI",
              "state": "opened",
              "source_branch": "feature",
              "target_branch": "main",
              "web_url": "https://gitlab.com/g/r/-/merge_requests/42",
              "updated_at": "2026-07-15T10:00:00Z",
              "author": {"id": 1, "username": "jota", "name": "Jo Ta"},
              "head_pipeline": {
                "id": 501,
                "status": "running",
                "ref": null,
                "sha": "deadbeef",
                "web_url": "https://gitlab.com/g/r/-/pipelines/501"
              }
            }
        """.trimIndent()

        val mr = json.decodeFromString<GitLabMergeRequest>(payload)

        val head = mr.headPipeline
        assertNotNull(head)
        assertEquals(501L, head!!.id)
        assertEquals("running", head.status)
        assertNull(head.ref)
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

    @Test
    fun `draft note keeps id note and position and ignores unknown fields`() {
        val draft = json.decodeFromString<GitLabDraftNote>(
            """
            {
              "id": 77,
              "note": "Please rename this",
              "merge_request_id": 5,
              "author_id": 9,
              "resolve_discussion": false,
              "line_code": "abc_1_2",
              "position": {
                "base_sha": "b",
                "head_sha": "h",
                "start_sha": "s",
                "old_path": "src/App.kt",
                "new_path": "src/App.kt",
                "new_line": 12
              }
            }
            """.trimIndent(),
        )

        assertEquals(77L, draft.id)
        assertEquals("Please rename this", draft.note)
        assertEquals("src/App.kt", draft.position?.newPath)
        assertEquals(12, draft.position?.newLine)
    }

    @Test
    fun `draft note defaults position to null when absent`() {
        val draft = json.decodeFromString<GitLabDraftNote>(
            """{"id": 78, "note": "General draft", "merge_request_id": 5, "author_id": 9}""",
        )

        assertEquals(78L, draft.id)
        assertEquals("General draft", draft.note)
        assertNull(draft.position)
    }

    @Test
    fun `upload parses the real uploads payload and ignores unknown fields`() {
        // Real POST /projects/:id/uploads response (GLC-56); the extra id/secret/file_name are ignored.
        val payload = """
            {
              "id": 5,
              "alt": "dk",
              "url": "/uploads/66dbcd21ec5d24ed6ea225176098d52b/dk.png",
              "full_path": "/-/project/1234/uploads/66dbcd21ec5d24ed6ea225176098d52b/dk.png",
              "markdown": "![dk](/uploads/66dbcd21ec5d24ed6ea225176098d52b/dk.png)",
              "secret": "66dbcd21ec5d24ed6ea225176098d52b",
              "file_name": "dk.png"
            }
        """.trimIndent()

        val upload = json.decodeFromString<GitLabUpload>(payload)

        assertEquals("dk", upload.alt)
        assertEquals("/uploads/66dbcd21ec5d24ed6ea225176098d52b/dk.png", upload.url)
        assertEquals("/-/project/1234/uploads/66dbcd21ec5d24ed6ea225176098d52b/dk.png", upload.fullPath)
        assertEquals("![dk](/uploads/66dbcd21ec5d24ed6ea225176098d52b/dk.png)", upload.markdown)
    }

    @Test
    fun `upload defaults every field to empty when absent`() {
        val upload = json.decodeFromString<GitLabUpload>("{}")

        assertEquals("", upload.alt)
        assertEquals("", upload.url)
        assertEquals("", upload.fullPath)
        assertEquals("", upload.markdown)
    }

    @Test
    fun `branch parses the real payload and ignores unknown fields`() {
        // Real GET /projects/:id/repository/branches item (GLC-57); every field but name/default is ignored.
        val payload = """
            {
              "name": "main",
              "merged": false,
              "protected": true,
              "default": true,
              "developers_can_push": false,
              "developers_can_merge": false,
              "can_push": true,
              "web_url": "https://gitlab.com/g/r/-/tree/main",
              "commit": {
                "id": "abc123",
                "short_id": "abc123",
                "title": "Initial commit",
                "created_at": "2026-07-14T08:00:00Z"
              }
            }
        """.trimIndent()

        val branch = json.decodeFromString<GitLabBranch>(payload)

        assertEquals("main", branch.name)
        assertTrue(branch.default)
    }

    @Test
    fun `branch defaults default to false when absent`() {
        val branch = json.decodeFromString<GitLabBranch>("""{"name": "feature/x"}""")

        assertEquals("feature/x", branch.name)
        assertFalse(branch.default)
    }

    @Test
    fun `bridge parses the downstream pipeline and ignores unknown fields`() {
        // Real GET /projects/:id/pipelines/:pipeline_id/bridges item (GLC-60): a trigger job carrying
        // the downstream_pipeline it started, here in another project of the group. id/stage/sha/ref
        // and allow_failure are all ignored — only name/status/downstream_pipeline are modeled.
        val payload = """
            {
              "id": 4001,
              "name": "trigger-release-management",
              "stage": "release",
              "status": "failed",
              "allow_failure": false,
              "downstream_pipeline": {
                "id": 9002,
                "sha": "abc123",
                "ref": "main",
                "status": "failed",
                "project_id": 555,
                "web_url": "https://gitlab.com/g/release-management/-/pipelines/9002"
              }
            }
        """.trimIndent()

        val bridge = json.decodeFromString<GitLabBridge>(payload)

        assertEquals("trigger-release-management", bridge.name)
        assertEquals("failed", bridge.status)
        val downstream = bridge.downstream
        assertNotNull(downstream)
        assertEquals(9002L, downstream!!.id)
        assertEquals(555L, downstream.projectId)
        assertEquals("failed", downstream.status)
        assertEquals("https://gitlab.com/g/release-management/-/pipelines/9002", downstream.webUrl)
    }

    @Test
    fun `bridge without a downstream pipeline defaults it to null`() {
        // A bridge that has not fired yet (e.g. a manual trigger) omits downstream_pipeline entirely.
        val bridge = json.decodeFromString<GitLabBridge>(
            """{"id": 4002, "name": "trigger-not-yet", "status": "manual"}""",
        )

        assertEquals("trigger-not-yet", bridge.name)
        assertEquals("manual", bridge.status)
        assertNull(bridge.downstream)
    }

    @Test
    fun `bridge with an explicit null downstream pipeline parses as null`() {
        // GitLab may also serialize the field explicitly as null before the bridge triggers.
        val bridge = json.decodeFromString<GitLabBridge>(
            """{"id": 4003, "name": "trigger", "status": "created", "downstream_pipeline": null}""",
        )

        assertNull(bridge.downstream)
    }
}
