package dev.jota.gitlabcockpit.ui

import com.intellij.openapi.application.EDT
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.absolutizeUploadLinks
import dev.jota.gitlabcockpit.core.findUploadImageRefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JEditorPane

/**
 * Renders a markdown [fragment] (already `stripBody`-ed HTML) into [pane] in two steps, fixing the
 * GLC-23 broken uploads:
 *
 * 1. **Sync** — non-image attachment links (`<a href="/uploads/…">`) are absolutized against
 *    [projectWebUrl] (skipped when null) so a click reaches the real authenticated GitLab URL, then
 *    the wrapped HTML is shown immediately (cheap, no network).
 * 2. **Async** — if the fragment embeds any upload image, a coroutine downloads + caches them and
 *    re-applies the HTML with the srcs rewritten to `file://` URLs (which the HTML editor kit can
 *    load). The re-apply is dropped when [isCurrent] no longer holds (the pane was re-rendered or the
 *    selection changed meanwhile), and skipped entirely when nothing changed.
 *
 * The caret is reset to the top on every apply, matching the panels' existing behavior.
 */
internal fun applyMarkdownUploads(
    pane: JEditorPane,
    fragment: String,
    service: CockpitProjectService,
    projectId: Long,
    projectWebUrl: String?,
    isCurrent: () -> Boolean,
) {
    val absolutized = if (projectWebUrl != null) absolutizeUploadLinks(fragment, projectWebUrl) else fragment
    val syncHtml = CockpitHtml.wrapHtml(absolutized)
    pane.text = syncHtml
    pane.caretPosition = 0
    if (findUploadImageRefs(syncHtml).isEmpty()) return
    service.coroutineScope.launch {
        val resolved = service.resolveUploadImages(projectId, syncHtml)
        if (resolved == syncHtml) return@launch
        withContext(Dispatchers.EDT) {
            if (!isCurrent()) return@withContext
            pane.text = resolved
            pane.caretPosition = 0
        }
    }
}
