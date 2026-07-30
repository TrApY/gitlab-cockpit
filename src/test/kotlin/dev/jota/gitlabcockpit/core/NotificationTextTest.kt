package dev.jota.gitlabcockpit.core

import com.intellij.BundleBase
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.MessageFormat
import java.util.Properties

/**
 * Pure tests for the GLC-54 notification-text builders ([pipelineNotificationText] and
 * [mrEventNotificationText]): the per-event title/body wording, the unified pipeline hierarchy
 * (outcome as title, MR line as body) and — the ticket's core fix — HTML-escaping of the MR title.
 *
 * The builders take a [NotificationMessages] resolver so they stay platform-free; this test binds it to
 * a resolver that reproduces the runtime bundle pipeline exactly without needing an IDE `Application`
 * (like [dev.jota.gitlabcockpit.CockpitBundleAmpersandTest]): the raw `.properties` value is run through
 * [BundleBase.replaceMnemonicAmpersand] and then [MessageFormat], the same order the platform uses — so
 * an escaped `&lt;` sitting in a *substituted* argument is inserted after mnemonic processing and kept
 * intact. The file is loaded as UTF-8, the encoding the platform reads bundles in.
 *
 * Also covers [mrEventSection] (GLC-64): which section of the MR tab each event's "Open in Cockpit"
 * action lands on — pure, and it lives in the same file as the builders above.
 */
class NotificationTextTest {

    private val raw: Properties = Properties().apply {
        NotificationTextTest::class.java.getResourceAsStream("/messages/CockpitBundle.properties")!!
            .reader(Charsets.UTF_8).use { load(it) }
    }

    /** Faithful stand-in for `CockpitBundle::message`: mnemonic post-processing then MessageFormat. */
    private val messages = NotificationMessages { key, params ->
        val template = BundleBase.replaceMnemonicAmpersand(raw.getProperty(key)!!)!!
        if (params.isEmpty()) template else MessageFormat.format(template, *params.toTypedArray())
    }

    private fun user(id: Long) = GitLabUser(id = id, username = "u$id", name = "U$id")

    private fun namedUser(id: Long, name: String) = GitLabUser(id = id, username = "u$id", name = name)

    private fun mr(iid: Long, title: String, state: String = "opened"): GitLabMergeRequest =
        GitLabMergeRequest(
            iid = iid,
            projectId = 500L,
            title = title,
            state = state,
            sourceBranch = "feature/$iid",
            targetBranch = "main",
            webUrl = "https://gitlab.com/g/r/-/merge_requests/$iid",
            updatedAt = "2026-07-22T10:00:00.000Z",
            author = user(2),
            sha = "sha-$iid",
            userNotesCount = 0,
        )

    // --- pipeline: unified hierarchy (outcome = title, MR line = body) ------------------------

    @Test
    fun `a succeeded pipeline uses the outcome as title and the MR line as body`() {
        val text = pipelineNotificationText(PipelineStatusChange(mr(42, "Fix login"), "success"), messages)

        assertEquals("Pipeline succeeded", text.title)
        assertEquals("!42  Fix login", text.content)
    }

    @Test
    fun `a failed pipeline uses the failed outcome as title and the MR line as body`() {
        val text = pipelineNotificationText(PipelineStatusChange(mr(42, "Fix login"), "failed"), messages)

        assertEquals("Pipeline failed", text.title)
        assertEquals("!42  Fix login", text.content)
    }

    // --- the 4 MrEvent variants ---------------------------------------------------------------

    @Test
    fun `a NewMr event titles with the scope line over the MR line`() {
        val text = mrEventNotificationText(MrEvent.NewMr(mr(7, "Add cache")), messages)

        assertEquals("New merge request in your scope", text.title)
        assertEquals("!7  Add cache", text.content)
    }

    @Test
    fun `a StateChanged event carries the new state in the title`() {
        val text = mrEventNotificationText(
            MrEvent.StateChanged(mr(7, "Add cache", state = "merged"), "opened", "merged"),
            messages,
        )

        assertEquals("Merge request merged", text.title)
        assertEquals("!7  Add cache", text.content)
    }

    @Test
    fun `a NewPush event titles with the push line`() {
        val text = mrEventNotificationText(MrEvent.NewPush(mr(7, "Add cache")), messages)

        assertEquals("New commits pushed", text.title)
        assertEquals("!7  Add cache", text.content)
    }

    @Test
    fun `a NewComments event carries the delta count in the body`() {
        val text = mrEventNotificationText(MrEvent.NewComments(mr(7, "Add cache"), 3), messages)

        assertEquals("New comments", text.title)
        assertEquals("!7  Add cache — 3 new", text.content)
    }

    // --- approval balloon (GLC-55): "Approved by ..." title over the MR line ------------------

    @Test
    fun `an approval by one user titles with the name over the MR line`() {
        val text = approvalNotificationText(ApprovalChange(mr(11, "Add cache"), listOf(user(3))), messages)

        assertEquals("Approved by U3", text.title)
        assertEquals("!11  Add cache", text.content)
    }

    @Test
    fun `an approval by two users joins the names in the title`() {
        val text = approvalNotificationText(
            ApprovalChange(mr(11, "Add cache"), listOf(user(3), user(4))),
            messages,
        )

        assertEquals("Approved by U3, U4", text.title)
        assertEquals("!11  Add cache", text.content)
    }

    @Test
    fun `an approver name with an ampersand is escaped in the title`() {
        val text = approvalNotificationText(
            ApprovalChange(mr(11, "Add cache"), listOf(namedUser(3, "Q&A Bot"))),
            messages,
        )

        assertEquals("Approved by Q&amp;A Bot", text.title)
        assertTrue(text.title.contains("&amp;"))
        assertFalse("raw ampersand leaked into the balloon: ${text.title}", text.title.contains("Q&A Bot"))
    }

    @Test
    fun `an approver name with angle brackets is escaped in the title`() {
        val text = approvalNotificationText(
            ApprovalChange(mr(11, "Add cache"), listOf(namedUser(3, "<script>"))),
            messages,
        )

        assertEquals("Approved by &lt;script&gt;", text.title)
        assertFalse(text.title.contains("<script>"))
    }

    @Test
    fun `an approval body is the MR line with an escaped title`() {
        val text = approvalNotificationText(
            ApprovalChange(mr(11, "Fix <T> handling"), listOf(user(3))),
            messages,
        )

        assertEquals("!11  Fix &lt;T&gt; handling", text.content)
        assertFalse(text.content.contains("<T>"))
    }

    // --- downstream balloon (GLC-61): "Downstream pipeline <outcome> — <bridge>" over the MR line -

    @Test
    fun `a succeeded downstream uses the outcome with the bridge name as title and the MR line as body`() {
        val text = downstreamNotificationText(
            DownstreamStatusChange(mr(42, "Fix login"), "release-management", "success"),
            messages,
        )

        assertEquals("Downstream pipeline succeeded — release-management", text.title)
        assertEquals("!42  Fix login", text.content)
    }

    @Test
    fun `a failed downstream uses the failed outcome with the bridge name as title and the MR line as body`() {
        val text = downstreamNotificationText(
            DownstreamStatusChange(mr(42, "Fix login"), "release-management", "failed"),
            messages,
        )

        assertEquals("Downstream pipeline failed — release-management", text.title)
        assertEquals("!42  Fix login", text.content)
    }

    @Test
    fun `a downstream bridge name with an ampersand is escaped in the title`() {
        val text = downstreamNotificationText(
            DownstreamStatusChange(mr(42, "Fix login"), "Q&A pipeline", "failed"),
            messages,
        )

        assertEquals("Downstream pipeline failed — Q&amp;A pipeline", text.title)
        assertTrue(text.title.contains("&amp;"))
        assertFalse("raw ampersand leaked into the balloon: ${text.title}", text.title.contains("Q&A pipeline"))
    }

    @Test
    fun `a downstream bridge name with angle brackets is escaped in the title`() {
        val text = downstreamNotificationText(
            DownstreamStatusChange(mr(42, "Fix login"), "<deploy>", "success"),
            messages,
        )

        assertEquals("Downstream pipeline succeeded — &lt;deploy&gt;", text.title)
        assertFalse(text.title.contains("<deploy>"))
    }

    @Test
    fun `a downstream body is the MR line with an escaped title`() {
        val text = downstreamNotificationText(
            DownstreamStatusChange(mr(11, "Fix <T> handling"), "release-management", "failed"),
            messages,
        )

        assertEquals("!11  Fix &lt;T&gt; handling", text.content)
        assertFalse(text.content.contains("<T>"))
    }

    // --- GLC-64: which MR-tab section an event's "Open in Cockpit" lands on -------------------

    @Test
    fun `a NewComments event opens the timeline, where the comments are`() {
        assertEquals(MrSection.TIMELINE, mrEventSection(MrEvent.NewComments(mr(7, "Add cache"), 3)))
    }

    @Test
    fun `a NewMr event opens the overview`() {
        assertEquals(MrSection.OVERVIEW, mrEventSection(MrEvent.NewMr(mr(7, "Add cache"))))
    }

    @Test
    fun `a StateChanged event opens the overview`() {
        assertEquals(
            MrSection.OVERVIEW,
            mrEventSection(MrEvent.StateChanged(mr(7, "Add cache", state = "merged"), "opened", "merged")),
        )
    }

    @Test
    fun `a NewPush event opens the overview`() {
        assertEquals(MrSection.OVERVIEW, mrEventSection(MrEvent.NewPush(mr(7, "Add cache"))))
    }

    // --- the GLC-54 bug: dynamic MR title must be HTML-escaped in the final text ---------------

    @Test
    fun `an MR title with angle brackets is escaped in a pipeline balloon body`() {
        val text = pipelineNotificationText(
            PipelineStatusChange(mr(7, "Fix <T> handling"), "failed"),
            messages,
        )

        assertEquals("!7  Fix &lt;T&gt; handling", text.content)
        assertTrue(text.content.contains("&lt;"))
        assertTrue(text.content.contains("&gt;"))
        assertFalse("raw angle brackets leaked into the balloon: ${text.content}", text.content.contains("<T>"))
    }

    @Test
    fun `an MR title with angle brackets is escaped in an MR event body`() {
        val text = mrEventNotificationText(MrEvent.NewMr(mr(9, "Fix <T> handling")), messages)

        assertEquals("!9  Fix &lt;T&gt; handling", text.content)
        assertFalse(text.content.contains("<T>"))
    }

    @Test
    fun `an ampersand in the MR title is escaped in the body`() {
        val text = mrEventNotificationText(MrEvent.NewComments(mr(8, "Cache & retry"), 1), messages)

        assertEquals("!8  Cache &amp; retry — 1 new", text.content)
        assertTrue(text.content.contains("&amp;"))
        assertFalse(text.content.contains("Cache & retry"))
    }
}
