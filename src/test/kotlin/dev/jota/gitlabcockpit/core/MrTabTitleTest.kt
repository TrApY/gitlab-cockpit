package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [mrTabLabel]: the `!<iid> <title>` tab label with the title trimmed and ellipsized to
 * [MR_TAB_TITLE_MAX] characters.
 */
class MrTabTitleTest {

    @Test
    fun `a short title is shown whole with the iid prefix`() {
        assertEquals("!42 Fix login", mrTabLabel(42, "Fix login"))
    }

    @Test
    fun `a title of exactly the limit is not truncated`() {
        val title = "a".repeat(MR_TAB_TITLE_MAX)

        assertEquals("!1 $title", mrTabLabel(1, title))
    }

    @Test
    fun `a title over the limit is cut to the limit with an ellipsis`() {
        val title = "0123456789".repeat(4) // 40 chars
        val expected = "!7 " + title.take(MR_TAB_TITLE_MAX) + "…"

        val label = mrTabLabel(7, title)

        assertEquals(expected, label)
        // The visible title portion is the limit plus the single ellipsis character.
        assertEquals(MR_TAB_TITLE_MAX + 1, label.removePrefix("!7 ").length)
    }

    @Test
    fun `surrounding whitespace is trimmed before measuring`() {
        assertEquals("!5 spaced", mrTabLabel(5, "   spaced   "))
    }
}
