package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.ApprovedBy
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [filterNotApproved] and [isGlobalByUserWithoutUser]. */
class MrFilterTest {

    private val me = 1L
    private val projectId = 500L

    private fun mr(iid: Long): GitLabMergeRequest = GitLabMergeRequest(
        iid = iid,
        projectId = projectId,
        title = "MR $iid",
        state = "opened",
        sourceBranch = "feature/$iid",
        targetBranch = "main",
        webUrl = "https://gitlab.com/g/r/-/merge_requests/$iid",
        updatedAt = "2026-07-14T10:00:00.000Z",
        author = GitLabUser(id = 99, username = "author", name = "Author"),
    )

    private fun ref(iid: Long): MrRef = MrRef(projectId, iid)

    private fun approvals(vararg approverIds: Long): GitLabApprovals =
        GitLabApprovals(approvedBy = approverIds.map { ApprovedBy(GitLabUser(it, "u$it", "U$it")) })

    @Test
    fun `merge request approved by me is excluded`() {
        val mrs = listOf(mr(1))
        val byRef = mapOf(ref(1) to approvals(me))

        assertEquals(emptyList<GitLabMergeRequest>(), filterNotApproved(mrs, byRef, me))
    }

    @Test
    fun `merge request approved only by others is kept`() {
        val mrs = listOf(mr(2))
        val byRef = mapOf(ref(2) to approvals(2L, 3L))

        assertEquals(listOf(mr(2)), filterNotApproved(mrs, byRef, me))
    }

    @Test
    fun `merge request with no approvals is kept`() {
        val mrs = listOf(mr(3))
        val byRef = mapOf(ref(3) to approvals())

        assertEquals(listOf(mr(3)), filterNotApproved(mrs, byRef, me))
    }

    @Test
    fun `merge request missing from approvals map is kept`() {
        val mrs = listOf(mr(4))

        assertEquals(listOf(mr(4)), filterNotApproved(mrs, emptyMap(), me))
    }

    @Test
    fun `mixed set keeps only the ones not approved by me`() {
        val mrs = listOf(mr(1), mr(2), mr(3), mr(4))
        val byRef = mapOf(
            ref(1) to approvals(me),        // approved by me -> drop
            ref(2) to approvals(2L),        // approved by other -> keep
            ref(3) to approvals(),          // nobody -> keep
            // 4 missing -> keep
        )

        assertEquals(listOf(mr(2), mr(3), mr(4)), filterNotApproved(mrs, byRef, me))
    }

    // --- filterByTitle ------------------------------------------------------------------------

    private fun titled(iid: Long, title: String): GitLabMergeRequest = mr(iid).copy(title = title)

    @Test
    fun `blank title query returns every merge request`() {
        val mrs = listOf(titled(1, "Fix login"), titled(2, "Add cache"))

        assertEquals(mrs, filterByTitle(mrs, ""))
        assertEquals(mrs, filterByTitle(mrs, "   "))
    }

    @Test
    fun `title query matches case-insensitively as a substring`() {
        val mrs = listOf(titled(1, "Fix Login bug"), titled(2, "Add cache"), titled(3, "Refactor LOGIN flow"))

        assertEquals(listOf(titled(1, "Fix Login bug"), titled(3, "Refactor LOGIN flow")), filterByTitle(mrs, "login"))
    }

    @Test
    fun `title query is trimmed before matching`() {
        val mrs = listOf(titled(1, "Fix login"), titled(2, "Add cache"))

        assertEquals(listOf(titled(2, "Add cache")), filterByTitle(mrs, "  cache  "))
    }

    @Test
    fun `title query with no match returns empty and preserves input order otherwise`() {
        val mrs = listOf(titled(3, "gamma"), titled(1, "alpha"), titled(2, "alphabet"))

        assertEquals(emptyList<GitLabMergeRequest>(), filterByTitle(mrs, "zzz"))
        assertEquals(listOf(titled(1, "alpha"), titled(2, "alphabet")), filterByTitle(mrs, "alpha"))
    }

    // --- isGlobalByUserWithoutUser ------------------------------------------------------------

    @Test
    fun `global by-user with a blank username is the whole-instance guard`() {
        assertTrue(isGlobalByUserWithoutUser(sel(RoleFilter.BY_USER, allProjects = true, username = null)))
        assertTrue(isGlobalByUserWithoutUser(sel(RoleFilter.BY_USER, allProjects = true, username = "")))
        assertTrue(isGlobalByUserWithoutUser(sel(RoleFilter.BY_USER, allProjects = true, username = "   ")))
    }

    @Test
    fun `global by-user with a username is not the guard`() {
        assertFalse(isGlobalByUserWithoutUser(sel(RoleFilter.BY_USER, allProjects = true, username = "jota")))
    }

    @Test
    fun `non-global or non-by-user selections are never the guard`() {
        assertFalse(isGlobalByUserWithoutUser(sel(RoleFilter.BY_USER, allProjects = false, username = null)))
        assertFalse(isGlobalByUserWithoutUser(sel(RoleFilter.I_AM_AUTHOR, allProjects = true, username = null)))
        assertFalse(isGlobalByUserWithoutUser(sel(RoleFilter.ALL, allProjects = true, username = null)))
    }

    private fun sel(role: RoleFilter, allProjects: Boolean, username: String?): MrFilterSelection =
        MrFilterSelection(
            role = role,
            state = MergeRequestState.OPENED,
            username = username,
            allProjects = allProjects,
        )
}
