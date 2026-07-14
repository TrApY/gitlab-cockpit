package dev.jota.gitlabcockpit.core

/** Host + project path parsed out of a git remote URL. */
data class RemoteCoords(
    val host: String,
    val pathWithNamespace: String,
)

/**
 * Pure, platform-free parsing of git remote URLs into [RemoteCoords]. Kept separate from the
 * project service so it can be unit-tested without the IntelliJ platform.
 *
 * Supported shapes:
 *  - scp-like SSH: `git@host:group/sub/repo.git`
 *  - scheme URLs: `https://host/group/sub/repo.git`, `ssh://git@host:22/group/repo`, with or
 *    without `.git`, with or without user (and password) info, and with nested subgroups.
 */
object GitLabProjectResolver {

    private val ALLOWED_SCHEMES = setOf("http", "https", "ssh", "git")

    /** Parses a remote URL, or returns `null` if it is not a recognizable git remote. */
    fun parseRemoteUrl(remoteUrl: String): RemoteCoords? {
        val url = remoteUrl.trim()
        if (url.isEmpty()) return null

        return when {
            "://" in url -> parseWithScheme(url)
            // scp-like syntax requires an explicit user and a ':' before the path.
            "@" in url && ":" in url -> parseScpLike(url)
            else -> null
        }
    }

    /** Extracts just the host from an instance base URL (e.g. `https://gitlab.example.com/api/v4`). */
    fun hostOf(url: String): String? {
        val u = url.trim()
        if ("://" !in u) return null
        val afterUser = u.substringAfter("://").substringAfter('@')
        val hostPort = afterUser.substringBefore('/')
        return hostPort.substringBefore(':').ifEmpty { null }
    }

    private fun parseWithScheme(url: String): RemoteCoords? {
        val scheme = url.substringBefore("://").lowercase()
        if (scheme !in ALLOWED_SCHEMES) return null

        // Drop userinfo (`user` or `user:password`) up to the first '@'.
        val authorityAndPath = url.substringAfter("://").substringAfter('@')
        val slash = authorityAndPath.indexOf('/')
        if (slash < 0) return null

        val hostPort = authorityAndPath.substring(0, slash)
        val host = hostPort.substringBefore(':').ifEmpty { return null }
        val path = cleanPath(authorityAndPath.substring(slash + 1)) ?: return null
        return RemoteCoords(host, path)
    }

    private fun parseScpLike(url: String): RemoteCoords? {
        // Format: [user@]host:path — strip optional userinfo, split host from path at the ':'.
        val afterUser = url.substringAfter('@')
        val colon = afterUser.indexOf(':')
        if (colon <= 0) return null

        val host = afterUser.substring(0, colon).ifEmpty { return null }
        val path = cleanPath(afterUser.substring(colon + 1)) ?: return null
        return RemoteCoords(host, path)
    }

    /**
     * Normalizes a project path: trims surrounding slashes, drops a trailing `.git`, and requires
     * at least a `namespace/project` pair (a GitLab path always has one). Returns `null` otherwise.
     */
    private fun cleanPath(raw: String): String? {
        var path = raw.trim().trim('/')
        if (path.endsWith(".git")) path = path.removeSuffix(".git").trim('/')
        if (path.isEmpty() || '/' !in path) return null
        return path
    }
}
