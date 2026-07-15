package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/**
 * A reference to a GitLab Markdown upload as it appears in rendered HTML: the 32-hex [secret] and the
 * single-segment [filename]. GitLab embeds attachments as `/uploads/<secret>/<filename>` — a path that
 * is both relative and authenticated, so Swing's HTML editor kit can neither resolve nor fetch it. The
 * [key] (`secret/filename`) identifies one upload across the download cache and the src-rewrite map.
 */
data class UploadRef(val secret: String, val filename: String) {
    val key: String get() = "$secret/$filename"
}

/** The web-path marker that separates a project's base URL from its MR route (`…/-/merge_requests/N`). */
internal const val MERGE_REQUESTS_MARKER = "/-/merge_requests/"

/** Matches an `<img>` whose `src` is an upload path; captures the 32-hex secret and the filename. */
private val UPLOAD_IMG_REGEX =
    Regex("""<img\b[^>]*\bsrc="/uploads/([0-9a-fA-F]{32})/([^"/]+)"[^>]*>""", RegexOption.IGNORE_CASE)

/** Matches an upload `src` attribute value on its own (used to rewrite the src to a `file://` URL). */
private val UPLOAD_SRC_REGEX =
    Regex("""src="/uploads/([0-9a-fA-F]{32})/([^"/]+)"""", RegexOption.IGNORE_CASE)

/** Matches an anchor `href` pointing at a relative `/uploads/…` attachment (non-image links). */
private val UPLOAD_HREF_REGEX =
    Regex("""href="(/uploads/[^"]*)"""", RegexOption.IGNORE_CASE)

/**
 * Extracts every upload image reference from [html] — the `<img src="/uploads/<32 hex>/<filename>">`
 * the markdown generator emits for `![alt](/uploads/…)`. A non-hex or wrong-length secret is ignored
 * (it is not an upload). The result is de-duplicated by [UploadRef.key] while preserving first-seen
 * order, so a description that embeds the same attachment twice is downloaded once. Pure and
 * platform-free.
 */
fun findUploadImageRefs(html: String): List<UploadRef> {
    val seen = LinkedHashMap<String, UploadRef>()
    for (match in UPLOAD_IMG_REGEX.findAll(html)) {
        val ref = UploadRef(match.groupValues[1], match.groupValues[2])
        seen.putIfAbsent(ref.key, ref)
    }
    return seen.values.toList()
}

/**
 * Rewrites the upload image srcs in [html] using [mapping] (key `secret/filename` → new URL, typically
 * a `file://` path to the downloaded copy). Only srcs whose key is present in [mapping] are rewritten;
 * any other src (including uploads that failed to download) is left exactly as-is. Pure and
 * platform-free.
 */
fun rewriteUploadImageSrcs(html: String, mapping: Map<String, String>): String =
    UPLOAD_SRC_REGEX.replace(html) { match ->
        val key = "${match.groupValues[1]}/${match.groupValues[2]}"
        mapping[key]?.let { "src=\"$it\"" } ?: match.value
    }

/**
 * The project's base web URL (everything before the `/-/merge_requests/` marker of [mr]'s web URL,
 * e.g. `https://gitlab.com/group/sub/project`), or null when the URL does not match that shape. Used
 * to absolutize relative `/uploads/…` attachment links so they open in the browser.
 */
fun projectWebUrlOf(mr: GitLabMergeRequest): String? {
    val index = mr.webUrl.indexOf(MERGE_REQUESTS_MARKER)
    if (index <= 0) return null
    return mr.webUrl.substring(0, index)
}

/**
 * Absolutizes relative `/uploads/…` attachment links in [html] by prefixing them with [projectWebUrl]
 * (so a click on a non-image attachment reaches the real, authenticated GitLab URL). Only `href`s that
 * start with `/uploads/` are touched — page anchors (`#…`), `http(s)`, `mailto:` and `file:` links are
 * left untouched. Pure and platform-free.
 */
fun absolutizeUploadLinks(html: String, projectWebUrl: String): String =
    UPLOAD_HREF_REGEX.replace(html) { match -> "href=\"$projectWebUrl${match.groupValues[1]}\"" }
