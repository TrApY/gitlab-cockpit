package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the GLC-23 markdown-upload helpers: image-ref extraction, partial src rewriting,
 * attachment-link absolutization and the project base-URL derivation. No platform, no network.
 */
class MarkdownUploadsTest {

    private val secretA = "0123456789abcdef0123456789abcdef"
    private val secretB = "fedcba9876543210fedcba9876543210"

    private fun mr(webUrl: String): GitLabMergeRequest =
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
        )

    // --- findUploadImageRefs ------------------------------------------------------------------

    @Test
    fun `no upload image yields an empty list`() {
        val html = """<p>hi <img src="https://cdn.example.com/logo.png"></p>"""
        assertEquals(emptyList<UploadRef>(), findUploadImageRefs(html))
    }

    @Test
    fun `a single upload image is extracted with its key`() {
        val refs = findUploadImageRefs("""<img src="/uploads/$secretA/pic.png">""")
        assertEquals(listOf(UploadRef(secretA, "pic.png")), refs)
        assertEquals("$secretA/pic.png", refs.single().key)
    }

    @Test
    fun `several distinct upload images preserve first-seen order`() {
        val html = """<img src="/uploads/$secretA/a.png"> text <img src="/uploads/$secretB/b.jpg">"""
        assertEquals(
            listOf(UploadRef(secretA, "a.png"), UploadRef(secretB, "b.jpg")),
            findUploadImageRefs(html),
        )
    }

    @Test
    fun `a repeated upload image is de-duplicated by key`() {
        val html = """<img src="/uploads/$secretA/a.png"><br><img src="/uploads/$secretA/a.png">"""
        assertEquals(listOf(UploadRef(secretA, "a.png")), findUploadImageRefs(html))
    }

    @Test
    fun `a non-hex secret is ignored`() {
        val html = """<img src="/uploads/zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz/a.png">"""
        assertEquals(emptyList<UploadRef>(), findUploadImageRefs(html))
    }

    // --- rewriteUploadImageSrcs ---------------------------------------------------------------

    @Test
    fun `rewrite replaces only mapped srcs and leaves the rest untouched`() {
        val html = """<img src="/uploads/$secretA/a.png"><img src="/uploads/$secretB/b.png">"""
        val out = rewriteUploadImageSrcs(html, mapOf("$secretA/a.png" to "file:///tmp/a.png"))
        assertEquals(
            """<img src="file:///tmp/a.png"><img src="/uploads/$secretB/b.png">""",
            out,
        )
    }

    // --- absolutizeUploadLinks ----------------------------------------------------------------

    @Test
    fun `absolutize rewrites relative upload hrefs but respects http and anchors`() {
        val html =
            """<a href="/uploads/$secretA/doc.pdf">doc</a>""" +
                """<a href="https://example.com/x">ext</a>""" +
                """<a href="#section">anchor</a>"""
        val out = absolutizeUploadLinks(html, "https://gitlab.com/group/project")
        assertEquals(
            """<a href="https://gitlab.com/group/project/uploads/$secretA/doc.pdf">doc</a>""" +
                """<a href="https://example.com/x">ext</a>""" +
                """<a href="#section">anchor</a>""",
            out,
        )
    }

    @Test
    fun `absolutize does not touch image srcs`() {
        val html = """<img src="/uploads/$secretA/a.png">"""
        assertEquals(html, absolutizeUploadLinks(html, "https://gitlab.com/group/project"))
    }

    // --- projectWebUrlOf ----------------------------------------------------------------------

    @Test
    fun `projectWebUrlOf returns the base before the merge_requests marker`() {
        assertEquals(
            "https://gitlab.com/group/project",
            projectWebUrlOf(mr("https://gitlab.com/group/project/-/merge_requests/42")),
        )
    }

    @Test
    fun `projectWebUrlOf keeps nested subgroups`() {
        assertEquals(
            "https://gitlab.example.com/group/sub/project",
            projectWebUrlOf(mr("https://gitlab.example.com/group/sub/project/-/merge_requests/7")),
        )
    }

    @Test
    fun `projectWebUrlOf is null when the url has no marker`() {
        assertNull(projectWebUrlOf(mr("https://gitlab.com/no-marker-here")))
    }
}
