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
        assignees: List<GitLabUser> = emptyList(),
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
        assignees = assignees,
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
    fun `line 2 joins iid author and date with the author display name, branches carried separately`() {
        val p = mrRowPresentation(mr(), showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals("!7 · José Tomás · 2h ago", p.line2)
        assertEquals("feature/cache", p.sourceBranch)
        assertEquals("main", p.targetBranch)
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
    fun `avatar users are the deduplicated participants capped at three, author first`() {
        // Author (id 1) is also a reviewer → appears once; plus one assignee and one more reviewer.
        val author = GitLabUser(id = 1, username = "jota", name = "José Tomás")
        val assignee = GitLabUser(id = 2, username = "sandra", name = "Sandra Camero")
        val other = GitLabUser(id = 3, username = "alex", name = "Alex Marin")
        val value = mr(
            authorName = "José Tomás",
            authorUsername = "jota",
            assignees = listOf(assignee),
            reviewers = listOf(author, other),
        )

        val p = mrRowPresentation(value, showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals(listOf(author, assignee, other), p.participants.map { it.user })
        assertEquals(0, p.avatarOverflow)
    }

    @Test
    fun `avatar overflow counts participants beyond the three shown`() {
        val value = mr(
            reviewers = listOf(reviewer(2), reviewer(3), reviewer(4), reviewer(5)),
        ) // author + 4 reviewers = 5 participants → 3 shown, +2

        val p = mrRowPresentation(value, showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals(5, p.participants.size) // uncapped; the renderer shows the first 3
        assertEquals(2, p.avatarOverflow)
    }

    @Test
    fun `no avatar overflow at or under the limit`() {
        val value = mr(reviewers = listOf(reviewer(2), reviewer(3))) // author + 2 = 3 participants
        val p = mrRowPresentation(value, showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals(0, p.avatarOverflow)
        assertFalse(p.avatarOverflow > 0)
    }

    // --- mrParticipantTooltip (GLC-44: one tooltip per shown avatar) --------------------------

    @Test
    fun `participant tooltip combines the roles of a deduplicated user`() {
        // Alex Marin (id 1) is author AND reviewer → one participant with both roles in its tooltip.
        val value = mr(
            authorName = "Alex Marin",
            authorUsername = "alex",
            assignees = listOf(GitLabUser(id = 2, username = "sandra", name = "Sandra Camero")),
            reviewers = listOf(GitLabUser(id = 1, username = "alex", name = "Alex Marin")),
        )
        val p = mrRowPresentation(value, showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals("Alex Marin (Author, Reviewer)", mrParticipantTooltip(p.participants[0]))
        assertEquals("Sandra Camero (Assignee)", mrParticipantTooltip(p.participants[1]))
    }

    @Test
    fun `participant tooltip of a lone author carries the author role`() {
        val p = mrRowPresentation(mr(), showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals("José Tomás (Author)", mrParticipantTooltip(p.participants.single()))
    }

    @Test
    fun `participant tooltip falls back to the username when the name is blank`() {
        val value = mr(authorName = "", authorUsername = "jota")
        val p = mrRowPresentation(value, showProject = false, relativeUpdatedAt = "2h ago")

        assertEquals("jota (Author)", mrParticipantTooltip(p.participants.single()))
    }
}
