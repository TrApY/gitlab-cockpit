package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabReferences
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure tests for [projectLabelOf]: references first, then a web-URL fallback, else null. */
class MrProjectLabelTest {

    private fun mr(webUrl: String, references: GitLabReferences?): GitLabMergeRequest =
        GitLabMergeRequest(
            iid = 42,
            projectId = 7,
            title = "T",
            state = "opened",
            sourceBranch = "s",
            targetBranch = "t",
            webUrl = webUrl,
            updatedAt = "2026-07-15T10:00:00Z",
            author = GitLabUser(id = 1, username = "jota", name = "Jo Ta"),
            references = references,
        )

    @Test
    fun `references full yields the part before the bang`() {
        val label = projectLabelOf(
            mr(
                webUrl = "https://gitlab.com/group/project/-/merge_requests/42",
                references = GitLabReferences(full = "group/project!42"),
            ),
        )
        assertEquals("group/project", label)
    }

    @Test
    fun `falls back to the web url path when references are absent`() {
        val label = projectLabelOf(
            mr(webUrl = "https://gitlab.com/group/project/-/merge_requests/42", references = null),
        )
        assertEquals("group/project", label)
    }

    @Test
    fun `web url fallback keeps nested subgroups`() {
        val label = projectLabelOf(
            mr(webUrl = "https://gitlab.example.com/group/sub/project/-/merge_requests/7", references = null),
        )
        assertEquals("group/sub/project", label)
    }

    @Test
    fun `returns null when neither source yields a label`() {
        val label = projectLabelOf(
            mr(webUrl = "https://gitlab.com/no-marker-here", references = null),
        )
        assertNull(label)
    }
}
