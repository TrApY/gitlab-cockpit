package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the notification-scope helpers: [mrScope] (author/assignee/reviewer membership) and
 * [detectMrEvents] (the first-pass memorize, and the NewMr / StateChanged / NewPush / NewComments
 * diffs plus their null-guards and combinations).
 */
class MrEventsTest {

    private val me = 1L
    private val other = 99L
    private val projectId = 500L

    private fun user(id: Long) = GitLabUser(id = id, username = "u$id", name = "U$id")

    private fun mr(
        iid: Long,
        state: String = "opened",
        sha: String? = "sha-$iid",
        notesCount: Int? = 0,
        authorId: Long = other,
        assigneeIds: List<Long> = emptyList(),
        reviewerIds: List<Long> = emptyList(),
    ): GitLabMergeRequest = GitLabMergeRequest(
        iid = iid,
        projectId = projectId,
        title = "MR $iid",
        state = state,
        sourceBranch = "feature/$iid",
        targetBranch = "main",
        webUrl = "https://gitlab.com/g/r/-/merge_requests/$iid",
        updatedAt = "2026-07-15T10:00:00.000Z",
        author = user(authorId),
        assignees = assigneeIds.map { user(it) },
        reviewers = reviewerIds.map { user(it) },
        sha = sha,
        userNotesCount = notesCount,
    )

    private fun ref(iid: Long) = MrRef(projectId, iid)

    // --- mrScope ------------------------------------------------------------------------------

    @Test
    fun `mrScope keeps MRs where I am author, assignee or reviewer and drops the rest`() {
        val mine = mr(1, authorId = me)
        val assigned = mr(2, assigneeIds = listOf(me))
        val reviewing = mr(3, reviewerIds = listOf(me))
        val notMine = mr(4, authorId = other, assigneeIds = listOf(other), reviewerIds = listOf(other))

        val scope = mrScope(listOf(mine, assigned, reviewing, notMine), me)

        assertEquals(listOf(mine, assigned, reviewing), scope)
    }

    // --- notificationScope --------------------------------------------------------------------

    @Test
    fun `notificationScope keeps the role scope when nothing is watched`() {
        val mine = mr(1, authorId = me)
        val reviewing = mr(2, reviewerIds = listOf(me))
        val notMine = mr(3, authorId = other)

        val scope = notificationScope(
            listOf(mine, reviewing, notMine),
            me,
            watched = emptySet(),
            includeAllFiltered = false,
        )

        assertEquals(listOf(mine, reviewing), scope)
    }

    @Test
    fun `notificationScope adds a watched MR that is outside the role scope`() {
        val mine = mr(1, authorId = me)
        val watchedOutOfRole = mr(2, authorId = other)
        val ignored = mr(3, authorId = other)

        val scope = notificationScope(
            listOf(mine, watchedOutOfRole, ignored),
            me,
            watched = setOf(ref(2)),
            includeAllFiltered = false,
        )

        assertEquals(listOf(mine, watchedOutOfRole), scope)
    }

    @Test
    fun `notificationScope with includeAllFiltered returns every MR of the filter`() {
        val a = mr(1, authorId = other)
        val b = mr(2, authorId = other)

        val scope = notificationScope(
            listOf(a, b),
            me,
            watched = emptySet(),
            includeAllFiltered = true,
        )

        assertEquals(listOf(a, b), scope)
    }

    @Test
    fun `notificationScope does not duplicate an MR that is both in role scope and watched`() {
        val mineAndWatched = mr(1, authorId = me)
        val watchedOutOfRole = mr(2, authorId = other)

        val scope = notificationScope(
            listOf(mineAndWatched, watchedOutOfRole),
            me,
            watched = setOf(ref(1), ref(2)),
            includeAllFiltered = false,
        )

        assertEquals(listOf(mineAndWatched, watchedOutOfRole), scope)
    }

    // --- detectMrEvents: first pass -----------------------------------------------------------

    @Test
    fun `the first pass (null previous) only memorizes and emits no events`() {
        val mrs = listOf(mr(1, state = "opened"), mr(2, state = "merged"))

        val (events, snapshot) = detectMrEvents(null, mrs)

        assertTrue(events.isEmpty())
        assertEquals(MrSnapshot("opened", "sha-1", 0), snapshot[ref(1)])
        assertEquals(MrSnapshot("merged", "sha-2", 0), snapshot[ref(2)])
    }

    // --- detectMrEvents: single event types ---------------------------------------------------

    @Test
    fun `an MR with no previous entry is a NewMr`() {
        val previous = mapOf(ref(1) to MrSnapshot("opened", "sha-1", 0))
        val mrs = listOf(mr(1), mr(2))

        val (events, _) = detectMrEvents(previous, mrs)

        assertEquals(listOf<MrEvent>(MrEvent.NewMr(mr(2))), events)
    }

    @Test
    fun `a changed state is a StateChanged carrying old and new (merged and closed)`() {
        val previous = mapOf(
            ref(1) to MrSnapshot("opened", "sha-1", 0),
            ref(2) to MrSnapshot("opened", "sha-2", 0),
        )
        val merged = mr(1, state = "merged")
        val closed = mr(2, state = "closed")

        val (events, _) = detectMrEvents(previous, listOf(merged, closed))

        assertEquals(
            listOf<MrEvent>(
                MrEvent.StateChanged(merged, "opened", "merged"),
                MrEvent.StateChanged(closed, "opened", "closed"),
            ),
            events,
        )
    }

    @Test
    fun `a changed sha is a NewPush`() {
        val previous = mapOf(ref(1) to MrSnapshot("opened", "old-sha", 0))
        val pushed = mr(1, sha = "new-sha")

        val (events, _) = detectMrEvents(previous, listOf(pushed))

        assertEquals(listOf<MrEvent>(MrEvent.NewPush(pushed)), events)
    }

    @Test
    fun `a grown note count is a NewComments with the delta`() {
        val previous = mapOf(ref(1) to MrSnapshot("opened", "sha-1", 2))
        val commented = mr(1, notesCount = 5)

        val (events, _) = detectMrEvents(previous, listOf(commented))

        assertEquals(listOf<MrEvent>(MrEvent.NewComments(commented, 3)), events)
    }

    @Test
    fun `a shrunk or equal note count is not a NewComments`() {
        val previous = mapOf(
            ref(1) to MrSnapshot("opened", "sha-1", 5),
            ref(2) to MrSnapshot("opened", "sha-2", 5),
        )
        val same = mr(1, notesCount = 5)
        val fewer = mr(2, notesCount = 3)

        val (events, _) = detectMrEvents(previous, listOf(same, fewer))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `null sha or note count on either side never fires push or comments`() {
        val previous = mapOf(
            ref(1) to MrSnapshot("opened", null, null),
            ref(2) to MrSnapshot("opened", "sha-2", 1),
        )
        val nowKnown = mr(1, sha = "sha-1", notesCount = 9)
        val nowUnknown = mr(2, sha = null, notesCount = null)

        val (events, _) = detectMrEvents(previous, listOf(nowKnown, nowUnknown))

        assertTrue(events.isEmpty())
    }

    // --- detectMrEvents: combinations & disappearance -----------------------------------------

    @Test
    fun `a single MR can emit several events at once`() {
        val previous = mapOf(ref(1) to MrSnapshot("opened", "old-sha", 1))
        val changed = mr(1, state = "merged", sha = "new-sha", notesCount = 4)

        val (events, _) = detectMrEvents(previous, listOf(changed))

        assertEquals(
            listOf<MrEvent>(
                MrEvent.StateChanged(changed, "opened", "merged"),
                MrEvent.NewPush(changed),
                MrEvent.NewComments(changed, 3),
            ),
            events,
        )
    }

    @Test
    fun `an MR that disappears from the list drops from the snapshot with no event`() {
        val previous = mapOf(
            ref(1) to MrSnapshot("opened", "sha-1", 0),
            ref(2) to MrSnapshot("opened", "sha-2", 0),
        )
        // MR 2 is gone this pass; MR 1 unchanged.
        val (events, snapshot) = detectMrEvents(previous, listOf(mr(1)))

        assertTrue(events.isEmpty())
        assertTrue(snapshot.containsKey(ref(1)))
        assertTrue(!snapshot.containsKey(ref(2)))
    }

    @Test
    fun `an unchanged MR emits no event`() {
        val previous = mapOf(ref(1) to MrSnapshot("opened", "sha-1", 0))

        val (events, _) = detectMrEvents(previous, listOf(mr(1)))

        assertTrue(events.isEmpty())
    }
}
