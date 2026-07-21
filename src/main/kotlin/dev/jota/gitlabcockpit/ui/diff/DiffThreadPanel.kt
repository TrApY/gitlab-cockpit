package dev.jota.gitlabcockpit.ui.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.threadNeedsAttention
import dev.jota.gitlabcockpit.ui.CockpitHtml
import dev.jota.gitlabcockpit.ui.CockpitTheme
import dev.jota.gitlabcockpit.ui.MarkdownRenderer
import dev.jota.gitlabcockpit.ui.applyMarkdownUploads
import dev.jota.gitlabcockpit.ui.formatRelative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.MatteBorder

/**
 * One review thread embedded under its diff line. Collapsed it is a single header row —
 * `N comments — author: first line…` plus a "Resolved" badge — so it steals little vertical space
 * from the diff; a click expands it to the full thread:
 *
 * - every non-system note rendered as themed HTML (author + relative date meta line, markdown body
 *   via [MarkdownRenderer]/[CockpitHtml] — the same pipeline as the rest of the plugin);
 * - a **Reply** action revealing an inline reply box (send posts through
 *   [CockpitProjectService.replyToDiscussion]);
 * - a **Resolve/Unresolve** action (shown only for resolvable threads) toggling via
 *   [CockpitProjectService.setDiscussionResolved].
 *
 * Both actions run on [CockpitProjectService.coroutineScope] — never the EDT — and update this
 * panel in place on [Dispatchers.EDT] (hot refresh: the reply is appended and the badge flipped
 * without reopening the diff). [onContentChanged] tells the host inlay to re-measure after any
 * change that can alter the panel's height.
 */
internal class DiffThreadPanel(
    private val project: Project,
    private val service: CockpitProjectService,
    private val mrRef: MrRef,
    discussion: GitLabDiscussion,
    private val projectWebUrl: String?,
) : JPanel(BorderLayout()) {

    private val discussionId: String = discussion.id

    /** The thread's human notes; grows in place when a reply is posted successfully. */
    private val notes: MutableList<GitLabDiscussionNote> =
        discussion.notes.filterNot { it.system }.toMutableList()

    private val resolvable: Boolean = notes.firstOrNull()?.resolvable == true
    private var resolved: Boolean = notes.firstOrNull()?.resolved == true
    private var expanded = false
    private var actionJob: Job? = null

    /** Set by the renderer; invoked after any change that can alter this panel's height. */
    var onContentChanged: (() -> Unit)? = null

    private val arrowLabel = JBLabel(AllIcons.General.ArrowRight)
    private val summaryLabel = JBLabel()
    private val resolvedLabel = JBLabel(CockpitBundle.message("diff.thread.resolved")).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val htmlPane = CockpitHtml.createHtmlPane()
    private val replyLink = ActionLink(CockpitBundle.message("diff.thread.reply")) { showReplyBox(true) }
    private val resolveLink = ActionLink("") { onToggleResolve() }

    private val replyArea = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = CockpitBundle.message("diff.thread.reply.placeholder")
    }
    private val sendButton = JButton(CockpitBundle.message("diff.thread.send")).apply {
        addActionListener { onSend() }
    }
    private val cancelButton = JButton(CockpitBundle.message("diff.thread.cancel")).apply {
        addActionListener { showReplyBox(false) }
    }
    private val replyBox = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        isOpaque = false
        isVisible = false
    }
    private val bodyPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
    }

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        updateAccentBorder()

        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)

        updateSummary()
        updateResolveUi()
        renderNotes()
    }

    /**
     * Sets the panel off from the surrounding code: a 3px left accent bar — amber while the thread
     * needs attention, green once resolved — over the panel background, wrapped in an outer line
     * border and inner padding. Recomputed whenever the resolution state changes.
     */
    private fun updateAccentBorder() {
        val accent = if (threadNeedsAttention(notes)) CockpitTheme.warning else CockpitTheme.success
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            BorderFactory.createCompoundBorder(
                MatteBorder(0, JBUI.scale(3), 0, 0, accent),
                CockpitTheme.panelBorder(),
            ),
        )
    }

    /** Cancels any in-flight reply/resolve; called when the hosting diff viewer is disposed. */
    fun cancelPendingAction() {
        actionJob?.cancel()
    }

    // --- UI assembly ----------------------------------------------------------------------------

    private fun buildHeader(): JComponent {
        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }
        header.add(arrowLabel, BorderLayout.WEST)
        header.add(summaryLabel, BorderLayout.CENTER)
        header.add(resolvedLabel, BorderLayout.EAST)
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val toggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = setExpanded(!expanded)
        }
        // Swing dispatches clicks to the deepest component, so the labels need the listener too.
        header.addMouseListener(toggle)
        arrowLabel.addMouseListener(toggle)
        summaryLabel.addMouseListener(toggle)
        resolvedLabel.addMouseListener(toggle)
        return header
    }

    private fun buildBody(): JComponent {
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false }
        actions.add(replyLink)
        actions.add(resolveLink)

        replyBox.add(JBScrollPane(replyArea), BorderLayout.CENTER)
        val replyButtons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        replyButtons.add(cancelButton)
        replyButtons.add(sendButton)
        replyBox.add(replyButtons, BorderLayout.SOUTH)

        val south = JPanel(VerticalLayout(JBUI.scale(4))).apply { isOpaque = false }
        south.add(actions)
        south.add(replyBox)

        bodyPanel.border = JBUI.Borders.emptyTop(6)
        bodyPanel.add(htmlPane, BorderLayout.CENTER)
        bodyPanel.add(south, BorderLayout.SOUTH)
        return bodyPanel
    }

    // --- Rendering ------------------------------------------------------------------------------

    private fun setExpanded(value: Boolean) {
        expanded = value
        arrowLabel.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        bodyPanel.isVisible = value
        notifyContentChanged()
    }

    private fun notifyContentChanged() {
        revalidate()
        repaint()
        onContentChanged?.invoke()
    }

    /** `N comments — author: first line…` for the collapsed header row. */
    private fun updateSummary() {
        val first = notes.firstOrNull()
        summaryLabel.text = CockpitBundle.message(
            "diff.thread.summary",
            CockpitBundle.message("diff.thread.replies", notes.size),
            first?.let { displayName(it.author) }.orEmpty(),
            first?.body?.lineSequence()?.firstOrNull()?.trim().orEmpty().take(60),
        )
    }

    private fun updateResolveUi() {
        resolvedLabel.isVisible = resolved
        resolveLink.isVisible = resolvable
        resolveLink.text = CockpitBundle.message(
            if (resolved) "diff.thread.unresolve" else "diff.thread.resolve",
        )
    }

    /** Re-renders every note as one themed HTML document (meta line + markdown body per note). */
    private fun renderNotes() {
        val metaColor = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val body = buildString {
            notes.forEachIndexed { index, note ->
                append("<div style=\"color:").append(metaColor).append(";\">")
                append(CockpitHtml.escapeHtml(noteMeta(note)))
                append("</div>")
                append(CockpitHtml.stripBody(MarkdownRenderer.toHtml(note.body)))
                if (index < notes.lastIndex) append("<hr>")
            }
        }
        // The async image re-apply is dropped once the thread grows (a reply re-renders it).
        val renderedNoteCount = notes.size
        applyMarkdownUploads(
            pane = htmlPane,
            fragment = body,
            service = service,
            projectId = mrRef.projectId,
            projectWebUrl = projectWebUrl,
            isCurrent = { notes.size == renderedNoteCount },
        )
    }

    /** `author · relative date` meta line for one note. */
    private fun noteMeta(note: GitLabDiscussionNote): String =
        displayName(note.author) + " · " + formatRelative(note.createdAt)

    private fun showReplyBox(visible: Boolean) {
        replyBox.isVisible = visible
        if (!visible) replyArea.text = ""
        notifyContentChanged()
        if (visible) replyArea.requestFocusInWindow()
    }

    // --- Actions --------------------------------------------------------------------------------

    /** Posts the reply off the EDT; on success the note is appended in place (no refetch). */
    private fun onSend() {
        val text = replyArea.text.trim()
        if (text.isEmpty()) return
        sendButton.isEnabled = false
        actionJob?.cancel()
        actionJob = service.coroutineScope.launch {
            val result = service.replyToDiscussion(mrRef, discussionId, text)
            withContext(Dispatchers.EDT) {
                sendButton.isEnabled = true
                when (result) {
                    is GitLabResult.Success -> {
                        notes.add(result.data)
                        renderNotes()
                        updateSummary()
                        showReplyBox(false)
                    }
                    else -> Messages.showErrorDialog(
                        project,
                        CockpitBundle.message("diff.thread.error.reply", describe(result)),
                        CockpitBundle.message("detail.error.title"),
                    )
                }
            }
        }
    }

    /** Toggles the thread's resolution off the EDT; on success the badge flips in place. */
    private fun onToggleResolve() {
        if (!resolvable) return
        val target = !resolved
        resolveLink.isEnabled = false
        actionJob?.cancel()
        actionJob = service.coroutineScope.launch {
            val result = service.setDiscussionResolved(mrRef, discussionId, target)
            withContext(Dispatchers.EDT) {
                resolveLink.isEnabled = true
                when (result) {
                    is GitLabResult.Success -> {
                        resolved = target
                        // Flip the resolvable notes so threadNeedsAttention (the accent's source of
                        // truth) reflects the new state without refetching the thread.
                        notes.replaceAll { if (it.resolvable) it.copy(resolved = target) else it }
                        updateResolveUi()
                        updateAccentBorder()
                        notifyContentChanged()
                    }
                    else -> Messages.showErrorDialog(
                        project,
                        CockpitBundle.message("diff.thread.error.resolve", describe(result)),
                        CockpitBundle.message("detail.error.title"),
                    )
                }
            }
        }
    }

    companion object {
        private fun displayName(user: GitLabUser): String = user.name.ifBlank { user.username }

        private fun describe(result: GitLabResult<*>): String = when (result) {
            is GitLabResult.HttpError -> "HTTP ${result.status}"
            is GitLabResult.NetworkError -> result.cause.message ?: result.cause.javaClass.simpleName
            is GitLabResult.Success<*> -> ""
        }
    }
}
