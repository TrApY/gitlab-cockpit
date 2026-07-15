package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [ReviewerSelectionModel]: [selectedIds] ordered by the member roster (not insertion
 * order), checks that survive filtering (a checked member stays selected even while hidden), the
 * toggle behaviour of [ReviewerSelectionModel.setChecked] and the [ReviewerSelectionModel.visibleItems]
 * delegation to [filterMembers].
 */
class ReviewerSelectionModelTest {

    private fun user(id: Long, username: String, name: String) = GitLabUser(id, username, name)

    private val members = listOf(
        user(1, "alice", "Alice"),
        user(2, "bob", "Bob"),
        user(3, "carol", "Carol"),
    )

    @Test
    fun `selectedIds are returned in members order regardless of the initial set order`() {
        val model = ReviewerSelectionModel(members, setOf(3L, 1L))
        assertEquals(listOf(1L, 3L), model.selectedIds())
    }

    @Test
    fun `a checked member stays selected even while a filter hides it`() {
        val model = ReviewerSelectionModel(members, emptySet())
        model.setChecked(2L, true)

        // The query hides Bob (id 2), leaving only Alice visible.
        assertEquals(listOf(1L), model.visibleItems("ali").map { it.id })
        // But the check is preserved: Bob is still selected.
        assertEquals(listOf(2L), model.selectedIds())
        assertTrue(model.isChecked(2L))
    }

    @Test
    fun `setChecked toggles membership`() {
        val model = ReviewerSelectionModel(members, setOf(1L))
        model.setChecked(1L, false)
        model.setChecked(2L, true)

        assertEquals(listOf(2L), model.selectedIds())
        assertTrue(model.isChecked(2L))
        assertFalse(model.isChecked(1L))
    }

    @Test
    fun `visibleItems delegates to filterMembers`() {
        val model = ReviewerSelectionModel(members, emptySet())
        assertEquals(members, model.visibleItems(""))
        assertEquals(listOf(2L), model.visibleItems("bob").map { it.id })
    }

    @Test
    fun `an initial checked id absent from the roster is dropped from selectedIds`() {
        val model = ReviewerSelectionModel(members, setOf(1L, 99L))
        assertEquals(listOf(1L), model.selectedIds())
    }
}
