package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabPipeline
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for [mrHeaderPresentation]: the head pipeline status is exposed only when present, the
 * draft flag and reference are carried through, the merged/closed lifecycle is resolved from the
 * supplied relative dates (merged winning over closed), and the merge-readiness line reflects the MR's
 * mergeability.
 */
class MrHeaderPresentationTest {

    private fun mr(
        iid: Long = 42,
        title: String = "Add feature",
        draft: Boolean = false,
        state: String = "opened",
        detailedMergeStatus: String? = "mergeable",
        pipeline: GitLabPipeline? = null,
    ) = GitLabMergeRequest(
        iid = iid,
        projectId = 1,
        title = title,
        state = state,
        sourceBranch = "feature",
        targetBranch = "main",
        webUrl = "https://gitlab.example/mr/$iid",
        updatedAt = "2024-01-01T00:00:00Z",
        draft = draft,
        author = GitLabUser(id = 1, username = "alice", name = "Alice"),
        headPipeline = pipeline,
        detailedMergeStatus = detailedMergeStatus,
    )

    private val runningPipeline = GitLabPipeline(
        id = 7,
        status = "running",
        sha = "abc",
        webUrl = "https://gitlab.example/p/7",
    )

    @Test
    fun `a present head pipeline exposes its status`() {
        val pres = mrHeaderPresentation(mr(pipeline = runningPipeline), "Alice", "2h ago", null, null)
        assertEquals("running", pres.pipelineStatus)
    }

    @Test
    fun `an absent head pipeline yields no pipeline status`() {
        val pres = mrHeaderPresentation(mr(pipeline = null), "Alice", "2h ago", null, null)
        assertNull(pres.pipelineStatus)
    }

    @Test
    fun `the draft flag and title are carried through`() {
        val draft = mrHeaderPresentation(mr(draft = true, title = "WIP"), "Alice", null, null, null)
        assertEquals(true, draft.draft)
        assertEquals("WIP", draft.title)
        assertEquals(false, mrHeaderPresentation(mr(draft = false), "Alice", null, null, null).draft)
    }

    @Test
    fun `the reference is the iid`() {
        assertEquals("!42", mrHeaderPresentation(mr(iid = 42), "Alice", null, null, null).reference)
    }

    @Test
    fun `a merged date makes the header closing MERGED`() {
        val pres = mrHeaderPresentation(mr(state = "merged"), "Alice", "3d ago", "1d ago", null)
        assertEquals(Closing.MERGED, pres.closing)
        assertEquals("1d ago", pres.closingRelative)
    }

    @Test
    fun `a closed date makes the header closing CLOSED`() {
        val pres = mrHeaderPresentation(mr(state = "closed"), "Alice", "3d ago", null, "1d ago")
        assertEquals(Closing.CLOSED, pres.closing)
        assertEquals("1d ago", pres.closingRelative)
    }

    @Test
    fun `a merged date wins over a closed date`() {
        val pres = mrHeaderPresentation(mr(state = "merged"), "Alice", "3d ago", "1d ago", "1d ago")
        assertEquals(Closing.MERGED, pres.closing)
    }

    @Test
    fun `an open MR has no closing suffix`() {
        val pres = mrHeaderPresentation(mr(), "Alice", "2h ago", null, null)
        assertEquals(Closing.OPEN, pres.closing)
        assertNull(pres.closingRelative)
    }

    @Test
    fun `a mergeable MR presents a ready merge line`() {
        val pres = mrHeaderPresentation(mr(detailedMergeStatus = "mergeable"), "Alice", null, null, null)
        assertEquals(MergeLineState.READY, pres.merge.state)
    }

    @Test
    fun `a blocked MR presents a blocked merge line with its reason`() {
        val pres = mrHeaderPresentation(mr(detailedMergeStatus = "conflict"), "Alice", null, null, null)
        assertEquals(MergeLineState.BLOCKED, pres.merge.state)
        assertEquals("merge.status.conflict", pres.merge.reasonKey)
    }
}
