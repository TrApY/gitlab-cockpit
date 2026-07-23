package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabBranch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [orderBranchNames] (GLC-57): the default branch first, then case-insensitive alphabetical. */
class BranchOrderingTest {

    @Test
    fun `default branch leads and the rest is alphabetical`() {
        val branches = listOf(
            GitLabBranch("feature/z"),
            GitLabBranch("main", default = true),
            GitLabBranch("develop"),
            GitLabBranch("feature/a"),
        )

        assertEquals(
            listOf("main", "develop", "feature/a", "feature/z"),
            orderBranchNames(branches),
        )
    }

    @Test
    fun `without a default branch the whole list is alphabetical`() {
        val branches = listOf(
            GitLabBranch("release/2"),
            GitLabBranch("develop"),
            GitLabBranch("release/1"),
        )

        assertEquals(
            listOf("develop", "release/1", "release/2"),
            orderBranchNames(branches),
        )
    }

    @Test
    fun `alphabetical ordering is case-insensitive`() {
        val branches = listOf(
            GitLabBranch("Zeta"),
            GitLabBranch("alpha"),
            GitLabBranch("Beta"),
        )

        assertEquals(
            listOf("alpha", "Beta", "Zeta"),
            orderBranchNames(branches),
        )
    }

    @Test
    fun `the default branch leads even when it sorts last alphabetically`() {
        val branches = listOf(
            GitLabBranch("alpha"),
            GitLabBranch("zzz-default", default = true),
            GitLabBranch("beta"),
        )

        assertEquals(
            listOf("zzz-default", "alpha", "beta"),
            orderBranchNames(branches),
        )
    }

    @Test
    fun `an empty list yields an empty list`() {
        assertTrue(orderBranchNames(emptyList()).isEmpty())
    }
}
