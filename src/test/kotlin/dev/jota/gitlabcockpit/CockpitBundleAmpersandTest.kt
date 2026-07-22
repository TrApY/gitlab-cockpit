package dev.jota.gitlabcockpit

import com.intellij.BundleBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * Regression test for GLC-43 C11 — the "Events & Discussions" tab losing its `&`.
 *
 * Root cause (verified against the platform bytecode): **every** value returned by an
 * [com.intellij.AbstractBundle] goes through `postprocessValue` -> [BundleBase.replaceMnemonicAmpersand],
 * which treats a lone `&` as a mnemonic marker — it drops the `&` and inserts the mnemonic control char
 * (U+001B, ESC) before the next character. So `CockpitBundle.message("detail.tab.timeline")` never
 * returned a literal `&`, no matter how the title was later applied (plain `addTab`, `setTitleAt`, or the
 * iter3 `setTabComponentAt` JBLabel) — the source string was already mangled at the bundle layer, which
 * is why that earlier "tab component" fix could not work.
 *
 * The fix escapes the ampersand in the `.properties` as `\\&` (loaded by `Properties` as `\&`), which
 * [BundleBase.replaceMnemonicAmpersand] turns back into a literal `&` **on every platform** (the
 * backslash branch, unlike `&&`, never consults `SystemInfoRt.isMac`).
 *
 * This test reproduces exactly the runtime pipeline without needing an `Application`: it loads the raw
 * bundle value the way `PropertyResourceBundle` does (`Properties` un-escaping) and runs it through the
 * very `replaceMnemonicAmpersand` the bundle applies, then asserts the rendered title carries a literal
 * `&` and no mnemonic marker.
 */
class CockpitBundleAmpersandTest {

    /** The IntelliJ mnemonic control char (U+001B) inserted for a lone `&`; must never survive our fix. */
    private val mnemonicMarker = '\u001B'

    private val bundle: Properties = Properties().apply {
        CockpitBundleAmpersandTest::class.java.getResourceAsStream("/messages/CockpitBundle.properties")
            .use { load(it) }
    }

    /** The rendered value the tab actually shows: raw property value -> bundle mnemonic post-processing. */
    private fun rendered(key: String): String =
        BundleBase.replaceMnemonicAmpersand(bundle.getProperty(key)!!)!!

    @Test
    fun `the timeline tab title keeps a literal ampersand after bundle mnemonic processing`() {
        val title = rendered("detail.tab.timeline")

        assertEquals("Events & Discussions", title)
        assertTrue("expected a literal '&', was: $title", title.contains('&'))
        assertFalse("mnemonic marker leaked into the title: $title", title.contains(mnemonicMarker))
    }

    @Test
    fun `the timeline tab title with a count keeps its literal ampersand`() {
        val title = rendered("detail.tab.timelineCount")

        assertEquals("Events & Discussions ({0})", title)
        assertTrue(title.contains('&'))
        assertFalse(title.contains(mnemonicMarker))
    }
}
