package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for [AwardEmoji]: the GitLab-name → Unicode mapping of the standard MR reaction set, the
 * null result for an unmapped name, and the `:name:` display fallback (GLC-40).
 */
class AwardEmojiTest {

    @Test
    fun `every standard reaction maps to its unicode emoji`() {
        assertEquals("👍", AwardEmoji.emojiFor("thumbsup"))
        assertEquals("👎", AwardEmoji.emojiFor("thumbsdown"))
        assertEquals("😄", AwardEmoji.emojiFor("smile"))
        assertEquals("🎉", AwardEmoji.emojiFor("tada"))
        assertEquals("😕", AwardEmoji.emojiFor("confused"))
        assertEquals("❤️", AwardEmoji.emojiFor("heart"))
        assertEquals("🚀", AwardEmoji.emojiFor("rocket"))
        assertEquals("👀", AwardEmoji.emojiFor("eyes"))
    }

    @Test
    fun `the standard set has the eight quick reactions in order`() {
        assertEquals(
            listOf("thumbsup", "thumbsdown", "smile", "tada", "confused", "heart", "rocket", "eyes"),
            AwardEmoji.STANDARD.map { it.first },
        )
    }

    @Test
    fun `an unmapped name has no unicode emoji`() {
        assertNull(AwardEmoji.emojiFor("ok_hand"))
        assertNull(AwardEmoji.emojiFor(""))
    }

    @Test
    fun `display returns the unicode emoji for a known name`() {
        assertEquals("🚀", AwardEmoji.display("rocket"))
    }

    @Test
    fun `display falls back to colon-wrapped name for an unknown reaction`() {
        assertEquals(":ok_hand:", AwardEmoji.display("ok_hand"))
    }
}
