package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure tests for [GitLabProjectResolver.parseRemoteUrl] and [GitLabProjectResolver.hostOf]. */
class GitLabProjectResolverTest {

    @Test
    fun `ssh scp-like url`() {
        val coords = GitLabProjectResolver.parseRemoteUrl("git@gitlab.com:group/repo.git")
        assertEquals(RemoteCoords("gitlab.com", "group/repo"), coords)
    }

    @Test
    fun `https url with dot git`() {
        val coords = GitLabProjectResolver.parseRemoteUrl("https://gitlab.com/group/repo.git")
        assertEquals(RemoteCoords("gitlab.com", "group/repo"), coords)
    }

    @Test
    fun `https url without dot git`() {
        val coords = GitLabProjectResolver.parseRemoteUrl("https://gitlab.com/group/repo")
        assertEquals(RemoteCoords("gitlab.com", "group/repo"), coords)
    }

    @Test
    fun `https url with credentials`() {
        val coords = GitLabProjectResolver.parseRemoteUrl(
            "https://oauth2:glpat-secret@gitlab.example.com/group/repo.git",
        )
        assertEquals(RemoteCoords("gitlab.example.com", "group/repo"), coords)
    }

    @Test
    fun `nested subgroups over ssh`() {
        val coords = GitLabProjectResolver.parseRemoteUrl("git@gitlab.example.com:group/sub1/sub2/repo.git")
        assertEquals(RemoteCoords("gitlab.example.com", "group/sub1/sub2/repo"), coords)
    }

    @Test
    fun `ssh scheme with port and nested subgroups`() {
        val coords = GitLabProjectResolver.parseRemoteUrl("ssh://git@gitlab.com:2222/group/sub/repo.git")
        assertEquals(RemoteCoords("gitlab.com", "group/sub/repo"), coords)
    }

    @Test
    fun `empty string is not a remote`() {
        assertNull(GitLabProjectResolver.parseRemoteUrl(""))
    }

    @Test
    fun `plain text is not a remote`() {
        assertNull(GitLabProjectResolver.parseRemoteUrl("not a url"))
    }

    @Test
    fun `unsupported scheme is rejected`() {
        assertNull(GitLabProjectResolver.parseRemoteUrl("ftp://gitlab.com/group/repo.git"))
    }

    @Test
    fun `url with no project path is rejected`() {
        assertNull(GitLabProjectResolver.parseRemoteUrl("https://gitlab.com"))
    }

    @Test
    fun `single segment path is rejected`() {
        assertNull(GitLabProjectResolver.parseRemoteUrl("https://gitlab.com/onlygroup"))
    }

    @Test
    fun `hostOf extracts host from api base url`() {
        assertEquals("gitlab.example.com", GitLabProjectResolver.hostOf("https://gitlab.example.com/api/v4"))
    }

    @Test
    fun `hostOf tolerates trailing slash and drops port`() {
        assertEquals("gitlab.com", GitLabProjectResolver.hostOf("https://gitlab.com/"))
        assertEquals("gitlab.com", GitLabProjectResolver.hostOf("https://gitlab.com:8443/api/v4"))
    }

    @Test
    fun `hostOf without scheme is null`() {
        assertNull(GitLabProjectResolver.hostOf("gitlab.com"))
    }
}
