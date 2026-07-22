package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [LabelSelectionModel] (GLC-42), mirroring [ReviewerSelectionModelTest]: [selectedNames]
 * ordered by the label roster, checks that survive filtering, the [LabelSelectionModel.setChecked]
 * toggle, [LabelSelectionModel.visibleItems] delegation to [filterLabels], and dropping a checked name
 * absent from the roster.
 */
class LabelSelectionModelTest {

    private fun label(name: String) = GitLabLabel(name, "#808080")

    private val labels = listOf(label("backend"), label("frontend"), label("ci"))

    @Test
    fun `selectedNames are returned in roster order regardless of the initial set order`() {
        val model = LabelSelectionModel(labels, setOf("ci", "backend"))
        assertEquals(listOf("backend", "ci"), model.selectedNames())
    }

    @Test
    fun `a checked label stays selected even while a filter hides it`() {
        val model = LabelSelectionModel(labels, emptySet())
        model.setChecked("frontend", true)

        assertEquals(listOf("ci"), model.visibleItems("ci").map { it.name })
        assertEquals(listOf("frontend"), model.selectedNames())
        assertTrue(model.isChecked("frontend"))
    }

    @Test
    fun `setChecked toggles membership`() {
        val model = LabelSelectionModel(labels, setOf("backend"))
        model.setChecked("backend", false)
        model.setChecked("ci", true)

        assertEquals(listOf("ci"), model.selectedNames())
        assertTrue(model.isChecked("ci"))
        assertFalse(model.isChecked("backend"))
    }

    @Test
    fun `visibleItems delegates to filterLabels`() {
        val model = LabelSelectionModel(labels, emptySet())
        assertEquals(labels, model.visibleItems(""))
        assertEquals(listOf("frontend"), model.visibleItems("front").map { it.name })
    }

    @Test
    fun `an initial checked name absent from the roster is dropped from selectedNames`() {
        val model = LabelSelectionModel(labels, setOf("backend", "ghost"))
        assertEquals(listOf("backend"), model.selectedNames())
    }
}
