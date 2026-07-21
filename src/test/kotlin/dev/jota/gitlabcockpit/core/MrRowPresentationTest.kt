package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabReferences
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [mrRowPresentation]: line composition, author fallback, project prefix, overflow. */
class MrRowPresentationTest {

    private fun mr(
        iid: Long = 7,
        title: String = "Add cache",
        draft: Boolean = false,
        hasConflicts: Boolean = false,
        authorName: String = "José Tomás",
        authorUsername: String = "jota",
        reviewers: List<GitLabUser> = emptyList(),
        references: GitLabReferences? = null,
    ): GitLabMergeRequest = GitLabMergeRequest(
        iid = iid,
        projectId = 42,
        title = title,
        state = "opened",
        sourceBranch = "feature/cache",
        targetBranch = "main",
        webUrl = "https://gitlab.com/group/project/-/merge_requests/$iid",
        updatedAt = "2026-07-14T10:00:00.000Z",
        draft = draft,
        hasConflicts = hasConflicts,
        author = GitLabUser(id = 1, username = authorUsername, name = authorName),
        reviewers = reviewers,
        references = references,
    )

    private fun reviewer(id: Long): GitLabUser = GitLabUser(id = id, username = "r$id", name = "Reviewer $id")

    @Test
    fun `line 1 is just the title when not draft and no conflicts`() {
        val p = mrRowPresentation(mr(), showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals(listOf(MrRowSegment("Add cache", MrSegmentStyle.NORMAL)), p.line1)
    }

    @Test
    fun `draft adds a warning prefix and conflicts add a danger suffix`() {
        val p = mrRowPresentation(
            mr(draft = true, hasConflicts = true),
            showProject = false,
            relativeUpdatedAt = "2h ago",
        )

        assertEquals(
            listOf(
                MrRowSegment("Draft: ", MrSegmentStyle.WARNING),
                MrRowSegment("Add cache", MrSegmentStyle.NORMAL),
                MrRowSegment(" · conflicts", MrSegmentStyle.DANGER),
            ),
            p.line1,
        )
    }

    @Test
    fun `line 2 joins iid author date and branches with the author display name`() {
        val p = mrRowPresentation(mr(), showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals("!7 · José Tomás · 2h ago · feature/cache → main", p.line2)
    }

    @Test
    fun `line 2 falls back to the username when the author name is blank`() {
        val p = mrRowPresentation(
            mr(authorName = "", authorUsername = "jota"),
            showProject = false,
            relativeUpdatedAt = "5m ago",
        )

        assertTrue(p.line2.startsWith("!7 · jota · "))
    }

    @Test
    fun `showProject prefixes line 2 with the group project label`() {
        val p = mrRowPresentation(
            mr(references = GitLabReferences(full = "group/project!7")),
            showProject = true,
            relativeUpdatedAt = "2h ago",
        )

        assertTrue("expected the project prefix, was: ${p.line2}", p.line2.startsWith("group/project · !7 · "))
    }

    @Test
    fun `showProject omits the prefix when it cannot be derived`() {
        val noLabel = mr().copy(webUrl = "https://gitlab.com/no-marker-here", references = null)
        val p = mrRowPresentation(noLabel, showProject = true, relativeUpdatedAt = "2h ago")

        assertTrue(p.line2.startsWith("!7 · "))
    }

    @Test
    fun `reviewer overflow counts reviewers beyond the shown avatars`() {
        val threeReviewers = mr(reviewers = listOf(reviewer(1), reviewer(2), reviewer(3)))

        assertEquals(1, mrRowPresentation(threeReviewers, showProject = false, relativeUpdatedAt = "2h ago").reviewerOverflow)
    }

    @Test
    fun `no reviewer overflow when at or under the avatar limit`() {
        val twoReviewers = mr(reviewers = listOf(reviewer(1), reviewer(2)))
        val p = mrRowPresentation(twoReviewers, showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals(0, p.reviewerOverflow)
        assertFalse(p.reviewerOverflow > 0)
    }
}
