package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabNote
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for the Events & Discussions timeline model: [buildTimeline] (system→event mapping,
 * thread→discussion mapping, chronological order asc/desc, the three filters, and the first-note
 * anchor for a multi-note thread) and [eventIconKey] (phrase classification).
 */
class MrTimelineTest {

    private val author = GitLabUser(id = 1, username = "jota", name = "Jo Ta")

    private fun note(id: Long, createdAt: String, system: Boolean, body: String = "body $id") =
        GitLabNote(id = id, body = body, system = system, author = author, createdAt = createdAt)

    private fun discussionNote(id: Long, createdAt: String) =
        GitLabDiscussionNote(id = id, body = "body $id", system = false, author = author, createdAt = createdAt)

    private fun thread(id: String, vararg notes: GitLabDiscussionNote) =
        commentThreads(listOf(GitLabDiscussion(id = id, notes = notes.toList()))).single()

    // --- buildTimeline: merge & order ---------------------------------------------------------

    @Test
    fun `system notes become events and threads become discussions`() {
        val notes = listOf(note(1, "2026-07-14T09:00:00Z", system = true))
        val threads = listOf(thread("d1", discussionNote(2, "2026-07-14T10:00:00Z")))
        val timeline = buildTimeline(notes, threads, TimelineFilter.ALL, ascending = true)
        assertEquals(2, timeline.size)
        assertEquals(TimelineItem.EventItem(notes.single()), timeline[0])
        assertEquals(TimelineItem.DiscussionItem(threads.single()), timeline[1])
    }

    @Test
    fun `human notes are not turned into events`() {
        // A non-system note in the notes list must be ignored (it already reaches the UI as a thread).
        val notes = listOf(
            note(1, "2026-07-14T09:00:00Z", system = true),
            note(2, "2026-07-14T09:30:00Z", system = false),
        )
        val timeline = buildTimeline(notes, threads = emptyList(), TimelineFilter.ALL, ascending = true)
        assertEquals(listOf(1L), timeline.map { (it as TimelineItem.EventItem).note.id })
    }

    @Test
    fun `items are ordered ascending by createdAt`() {
        val notes = listOf(
            note(1, "2026-07-14T12:00:00Z", system = true),
            note(2, "2026-07-14T08:00:00Z", system = true),
        )
        val threads = listOf(thread("d1", discussionNote(3, "2026-07-14T10:00:00Z")))
        val timeline = buildTimeline(notes, threads, TimelineFilter.ALL, ascending = true)
        assertEquals(listOf("2026-07-14T08:00:00Z", "2026-07-14T10:00:00Z", "2026-07-14T12:00:00Z"), timeline.map { it.createdAt })
    }

    @Test
    fun `items are ordered descending by createdAt`() {
        val notes = listOf(
            note(1, "2026-07-14T12:00:00Z", system = true),
            note(2, "2026-07-14T08:00:00Z", system = true),
        )
        val threads = listOf(thread("d1", discussionNote(3, "2026-07-14T10:00:00Z")))
        val timeline = buildTimeline(notes, threads, TimelineFilter.ALL, ascending = false)
        assertEquals(listOf("2026-07-14T12:00:00Z", "2026-07-14T10:00:00Z", "2026-07-14T08:00:00Z"), timeline.map { it.createdAt })
    }

    // --- buildTimeline: filters ---------------------------------------------------------------

    @Test
    fun `EVENTS filter keeps only system events`() {
        val notes = listOf(note(1, "2026-07-14T09:00:00Z", system = true))
        val threads = listOf(thread("d1", discussionNote(2, "2026-07-14T10:00:00Z")))
        val timeline = buildTimeline(notes, threads, TimelineFilter.EVENTS, ascending = true)
        assertEquals(1, timeline.size)
        assertEquals(true, timeline.single() is TimelineItem.EventItem)
    }

    @Test
    fun `DISCUSSIONS filter keeps only threads`() {
        val notes = listOf(note(1, "2026-07-14T09:00:00Z", system = true))
        val threads = listOf(thread("d1", discussionNote(2, "2026-07-14T10:00:00Z")))
        val timeline = buildTimeline(notes, threads, TimelineFilter.DISCUSSIONS, ascending = true)
        assertEquals(1, timeline.size)
        assertEquals(true, timeline.single() is TimelineItem.DiscussionItem)
    }

    @Test
    fun `a thread with replies sorts on its first note`() {
        // The opening note (id 2) is old; a later reply (id 3) must not move the thread's position.
        val notes = listOf(note(1, "2026-07-14T09:30:00Z", system = true))
        val threads = listOf(
            thread(
                "d1",
                discussionNote(2, "2026-07-14T09:00:00Z"),
                discussionNote(3, "2026-07-14T18:00:00Z"),
            ),
        )
        val timeline = buildTimeline(notes, threads, TimelineFilter.ALL, ascending = true)
        // Thread (first note 09:00) comes before the event (09:30) despite the 18:00 reply.
        assertEquals(true, timeline[0] is TimelineItem.DiscussionItem)
        assertEquals("2026-07-14T09:00:00Z", timeline[0].createdAt)
        assertEquals(true, timeline[1] is TimelineItem.EventItem)
    }

    // --- eventIconKey -------------------------------------------------------------------------

    @Test
    fun `eventIconKey classifies commit notes`() {
        assertEquals("commit", eventIconKey("added 3 commits"))
    }

    @Test
    fun `eventIconKey classifies assign notes`() {
        assertEquals("assign", eventIconKey("assigned to @jota"))
        assertEquals("assign", eventIconKey("unassigned @jota"))
    }

    @Test
    fun `eventIconKey classifies review requests`() {
        assertEquals("review", eventIconKey("requested review from @jota"))
    }

    @Test
    fun `eventIconKey classifies approvals ahead of the merge phrase`() {
        // "approved this merge request" also contains "merge" — approve must win.
        assertEquals("approve", eventIconKey("approved this merge request"))
    }

    @Test
    fun `eventIconKey classifies merge notes`() {
        assertEquals("merge", eventIconKey("merged"))
    }

    @Test
    fun `eventIconKey classifies state changes`() {
        assertEquals("state", eventIconKey("closed"))
        assertEquals("state", eventIconKey("reopened"))
        assertEquals("state", eventIconKey("marked this merge request as draft"))
    }

    @Test
    fun `eventIconKey falls back to generic`() {
        assertEquals("generic", eventIconKey("changed the description"))
    }

    @Test
    fun `eventIconKey is case insensitive`() {
        assertEquals("commit", eventIconKey("Added 1 COMMIT"))
    }
}
