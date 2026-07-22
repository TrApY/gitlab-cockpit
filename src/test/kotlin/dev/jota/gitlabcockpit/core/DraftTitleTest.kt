package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the draft-title toggle (GLC-42) — the mechanism the Edit dialog uses to flip an MR's
 * draft state, since GitLab's REST update endpoint exposes no direct draft/WIP input. Covers adding /
 * removing the `Draft: ` prefix, idempotency, normalization of legacy `WIP:` / bracketed markers, and
 * that a lookalike word ("Drafting") is not mistaken for a marker.
 */
class DraftTitleTest {

    @Test
    fun `applyDraftState adds the prefix when draft and none is present`() {
        assertEquals("Draft: My feature", applyDraftState("My feature", draft = true))
    }

    @Test
    fun `applyDraftState is idempotent when the title already has the prefix`() {
        assertEquals("Draft: My feature", applyDraftState("Draft: My feature", draft = true))
    }

    @Test
    fun `applyDraftState removes the prefix when not draft`() {
        assertEquals("My feature", applyDraftState("Draft: My feature", draft = false))
    }

    @Test
    fun `applyDraftState leaves a plain title unchanged when not draft`() {
        assertEquals("My feature", applyDraftState("My feature", draft = false))
    }

    @Test
    fun `applyDraftState normalizes a legacy WIP marker to the Draft prefix`() {
        assertEquals("Draft: My feature", applyDraftState("WIP: My feature", draft = true))
        assertEquals("My feature", applyDraftState("WIP: My feature", draft = false))
    }

    @Test
    fun `applyDraftState strips bracketed and parenthesized markers case-insensitively`() {
        assertEquals("My feature", applyDraftState("[Draft] My feature", draft = false))
        assertEquals("My feature", applyDraftState("(WIP) My feature", draft = false))
        assertEquals("My feature", applyDraftState("draft: My feature", draft = false))
    }

    @Test
    fun `isDraftTitle detects markers and ignores lookalikes`() {
        assertTrue(isDraftTitle("Draft: X"))
        assertTrue(isDraftTitle("[WIP] X"))
        assertFalse(isDraftTitle("Drafting the plan"))
        assertFalse(isDraftTitle("Wipe the slate"))
        assertFalse(isDraftTitle("A normal title"))
    }

    @Test
    fun `stripDraftPrefix removes only a single leading marker`() {
        assertEquals("My feature", stripDraftPrefix("Draft: My feature"))
        assertEquals("Draft: My feature", stripDraftPrefix("Draft: Draft: My feature"))
    }
}
