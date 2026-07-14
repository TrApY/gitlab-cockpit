package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabNote
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.MergeRequestUpdate
import dev.jota.gitlabcockpit.core.CockpitProjectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The detail pane shown below the MR list. Renders one merge request inside a two-tab layout:
 *
 * - **Overview**: header (`!iid title` + DRAFT/conflicts badges + edit-title/description button),
 *   author/assignee row, reviewers row, an approvals row ("Approved by: …" + an Approve/Revoke
 *   button that reflects whether the current user already approved) and the markdown description.
 * - **Comments**: the MR's human notes (system notes filtered out) rendered as themed HTML, plus a
 *   text area + Comment button to post a new general comment. Notes load lazily the first time the
 *   tab is shown for each MR and again on every detail refresh; the tab title carries the count.
 *
 * Editing is done through modal dialogs; every network call (detail load, member load, update,
 * approvals, notes, approve/unapprove, comment) runs on the service's coroutine scope and only
 * touches the EDT to render. Stale results are dropped by re-checking [currentIid] on the EDT.
 *
 * @param onListReloadRequested called after a successful edit or approval change so the parent can
 * silently refresh the MR list (e.g. so the "reviewer, not approved" filter reflects the change).
 */
class MrDetailPanel(
    private val project: Project,
    private val service: CockpitProjectService,
    private val onListReloadRequested: () -> Unit,
) : JPanel(BorderLayout()) {

    /** iid of the MR currently displayed (or being loaded); null when showing the placeholder. */
    var currentIid: Long? = null
        private set

    private var detailJob: Job? = null
    private var notesJob: Job? = null

    /** The MR whose notes are loaded (or loading). Reset on every [showMr] so a refresh re-fetches. */
    private var notesLoadedForIid: Long? = null

    /** Recreated by [buildHeader]; updated by the async approvals load. */
    private var approvalsLabel: JBLabel? = null
    private var approvalButton: JButton? = null

    private val descriptionPane = CockpitHtml.createHtmlPane()
    private val descriptionScroll = JBScrollPane(descriptionPane)
    private val headerContainer = JPanel(BorderLayout()).apply { isOpaque = false }
    private val overviewPanel = JPanel(BorderLayout())

    private val notesPane = CockpitHtml.createHtmlPane()
    private val notesScroll = JBScrollPane(notesPane)
    private val commentArea = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = CockpitBundle.message("detail.comment.placeholder")
    }
    private val commentButton = JButton(CockpitBundle.message("detail.comment.button"))
    private val commentsPanel = JPanel(BorderLayout())

    private val tabbedPane = JBTabbedPane()

    init {
        overviewPanel.add(headerContainer, BorderLayout.NORTH)
        overviewPanel.add(descriptionScroll, BorderLayout.CENTER)

        commentsPanel.add(notesScroll, BorderLayout.CENTER)
        commentsPanel.add(buildCommentInput(), BorderLayout.SOUTH)

        tabbedPane.addTab(CockpitBundle.message("detail.tab.overview"), overviewPanel)
        tabbedPane.addTab(CockpitBundle.message("detail.tab.comments"), commentsPanel)
        tabbedPane.addChangeListener {
            if (tabbedPane.selectedIndex == COMMENTS_TAB_INDEX) {
                val iid = currentIid
                if (iid != null && notesLoadedForIid != iid) loadNotes(iid)
            }
        }
        commentButton.addActionListener { onSubmitComment() }

        showPlaceholder()
    }

    /** EDT. Shows the "select an MR" placeholder and forgets the current selection. */
    fun showPlaceholder() {
        currentIid = null
        setSingleMessage(CockpitBundle.message("detail.placeholder"))
    }

    /** EDT. Kicks off a background detail load for [iid] and renders the result when it arrives. */
    fun loadDetail(iid: Long) {
        currentIid = iid
        commentArea.text = ""
        setSingleMessage(CockpitBundle.message("detail.loading"))
        detailJob?.cancel()
        detailJob = service.coroutineScope.launch {
            val result = service.getMrDetail(iid)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                when (result) {
                    is GitLabResult.Success -> showMr(result.data)
                    else -> setSingleMessage(CockpitBundle.message("detail.error.load", describe(result)))
                }
            }
        }
    }

    /** EDT. Renders [mr] into the tabbed layout and kicks off the approvals (and lazy notes) loads. */
    private fun showMr(mr: GitLabMergeRequest) {
        currentIid = mr.iid

        headerContainer.removeAll()
        headerContainer.add(buildHeader(mr), BorderLayout.CENTER)
        setDescription(mr)

        // Reset the comment thread for the (possibly refreshed) MR; it reloads lazily / on demand.
        notesJob?.cancel()
        notesLoadedForIid = null
        notesPane.text = CockpitHtml.wrapHtml("")
        setCommentsTabTitle(null)

        if (tabbedPane.parent !== this) {
            removeAll()
            add(tabbedPane, BorderLayout.CENTER)
        }
        revalidate()
        repaint()

        loadApprovals(mr.iid)
        if (tabbedPane.selectedIndex == COMMENTS_TAB_INDEX) loadNotes(mr.iid)
    }

    private fun setDescription(mr: GitLabMergeRequest) {
        val fragment = if (mr.description.isNullOrBlank()) {
            "<p><i>" + CockpitHtml.escapeHtml(CockpitBundle.message("detail.noDescription")) + "</i></p>"
        } else {
            CockpitHtml.stripBody(MarkdownRenderer.toHtml(mr.description))
        }
        descriptionPane.text = CockpitHtml.wrapHtml(fragment)
        descriptionPane.caretPosition = 0
    }

    private fun buildHeader(mr: GitLabMergeRequest): JComponent {
        val header = JPanel(VerticalLayout(JBUI.scale(4)))
        header.isOpaque = false
        header.border = JBUI.Borders.empty(6, 8)

        val titleLine = flowLine()
        titleLine.add(JBLabel("!${mr.iid}  ${mr.title}").apply { font = font.deriveFont(Font.BOLD) })
        if (mr.draft) {
            titleLine.add(badge(CockpitBundle.message("toolwindow.mr.draft"), UIUtil.getContextHelpForeground()))
        }
        if (mr.hasConflicts) {
            titleLine.add(badge(CockpitBundle.message("toolwindow.mr.conflicts"), JBColor.RED))
        }
        titleLine.add(
            JButton(AllIcons.Actions.Edit).apply {
                toolTipText = CockpitBundle.message("detail.editTooltip")
                addActionListener { onEditTitleDescription(mr) }
            },
        )
        header.add(titleLine)

        val assignee = mr.assignees.firstOrNull()?.let(::displayName) ?: CockpitBundle.message("detail.none")
        val authorLine = flowLine()
        authorLine.add(
            JBLabel(
                CockpitBundle.message("detail.author") + " " + displayName(mr.author) +
                    "    " + CockpitBundle.message("detail.assignee") + " " + assignee,
            ),
        )
        authorLine.add(ActionLink(CockpitBundle.message("detail.edit")) { onEditAssignee(mr) })
        header.add(authorLine)

        val reviewers = mr.reviewers.joinToString(", ") { displayName(it) }
            .ifEmpty { CockpitBundle.message("detail.none") }
        val reviewersLine = flowLine()
        reviewersLine.add(JBLabel(CockpitBundle.message("detail.reviewers") + " " + reviewers))
        reviewersLine.add(ActionLink(CockpitBundle.message("detail.edit")) { onEditReviewers(mr) })
        header.add(reviewersLine)

        val approvalsLine = flowLine()
        val label = JBLabel(
            CockpitBundle.message("detail.approvedBy", CockpitBundle.message("detail.approvals.loading")),
        )
        val button = JButton(CockpitBundle.message("detail.approve")).apply { isEnabled = false }
        approvalsLine.add(label)
        approvalsLine.add(button)
        approvalsLabel = label
        approvalButton = button
        header.add(approvalsLine)

        return header
    }

    // --- Approvals ----------------------------------------------------------------------------

    /** Fetches the fresh approval state and updates the approvals row (guarded by [currentIid]). */
    private fun loadApprovals(iid: Long) {
        service.coroutineScope.launch {
            val result = service.getApprovalsFor(iid)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                when (result) {
                    is GitLabResult.Success -> renderApprovals(result.data)
                    else -> {
                        approvalsLabel?.text = CockpitBundle.message(
                            "detail.approvedBy",
                            CockpitBundle.message("detail.approvals.unavailable"),
                        )
                        approvalButton?.isEnabled = false
                    }
                }
            }
        }
    }

    /** EDT. Fills the approvals label and re-targets the Approve/Revoke button for [approvals]. */
    private fun renderApprovals(approvals: GitLabApprovals) {
        val names = approvals.approvedBy.joinToString(", ") { displayName(it.user) }
        val display = names.ifEmpty { CockpitBundle.message("detail.approvals.none") }
        approvalsLabel?.text = CockpitBundle.message("detail.approvedBy", display)

        val me = service.currentUser
        val approved = me != null && approvals.approvedBy.any { it.user.id == me.id }
        val button = approvalButton ?: return
        button.text = CockpitBundle.message(if (approved) "detail.revokeApproval" else "detail.approve")
        button.isEnabled = me != null
        button.actionListeners.toList().forEach { button.removeActionListener(it) }
        val iid = currentIid
        if (iid != null) button.addActionListener { onToggleApproval(iid, approved) }
    }

    /** Approves or revokes in the background, then refreshes approvals and the list on success. */
    private fun onToggleApproval(iid: Long, alreadyApproved: Boolean) {
        approvalButton?.isEnabled = false
        service.coroutineScope.launch {
            val result = if (alreadyApproved) service.unapprove(iid) else service.approve(iid)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        loadApprovals(iid)
                        onListReloadRequested()
                    }
                    else -> {
                        approvalButton?.isEnabled = true
                        showError("detail.error.approve", result)
                    }
                }
            }
        }
    }

    // --- Comments -----------------------------------------------------------------------------

    private fun buildCommentInput(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(6, 8)
        panel.add(JBScrollPane(commentArea), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        buttons.add(commentButton)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    /** Fetches the MR's notes in the background and renders them (guarded by [currentIid]). */
    private fun loadNotes(iid: Long) {
        notesLoadedForIid = iid
        notesPane.text = CockpitHtml.wrapHtml(
            "<p><i>" + CockpitHtml.escapeHtml(CockpitBundle.message("detail.comment.loading")) + "</i></p>",
        )
        setCommentsTabTitle(null)
        notesJob?.cancel()
        notesJob = service.coroutineScope.launch {
            val result = service.getNotes(iid)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                when (result) {
                    is GitLabResult.Success -> renderNotes(result.data)
                    else -> {
                        notesLoadedForIid = null
                        notesPane.text = CockpitHtml.wrapHtml(
                            "<p><i>" +
                                CockpitHtml.escapeHtml(CockpitBundle.message("detail.error.notes", describe(result))) +
                                "</i></p>",
                        )
                        setCommentsTabTitle(null)
                    }
                }
            }
        }
    }

    /** EDT. Renders the human notes as one themed HTML document and updates the tab counter. */
    private fun renderNotes(notes: List<GitLabNote>) {
        if (notes.isEmpty()) {
            notesPane.text = CockpitHtml.wrapHtml(
                "<p><i>" + CockpitHtml.escapeHtml(CockpitBundle.message("detail.comment.empty")) + "</i></p>",
            )
        } else {
            val metaColor = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
            val body = buildString {
                notes.forEachIndexed { index, note ->
                    append("<div style=\"color:").append(metaColor).append(";\">")
                    append(CockpitHtml.escapeHtml(displayName(note.author)))
                    append(" &middot; ")
                    append(CockpitHtml.escapeHtml(formatRelative(note.createdAt)))
                    append("</div>")
                    append(CockpitHtml.stripBody(MarkdownRenderer.toHtml(note.body)))
                    if (index < notes.lastIndex) append("<hr>")
                }
            }
            notesPane.text = CockpitHtml.wrapHtml(body)
        }
        notesPane.caretPosition = 0
        setCommentsTabTitle(notes.size)
    }

    /** Posts the text area's content as a new comment, then clears it and reloads the thread. */
    private fun onSubmitComment() {
        val iid = currentIid ?: return
        val text = commentArea.text.trim()
        if (text.isEmpty()) return
        commentButton.isEnabled = false
        service.coroutineScope.launch {
            val result = service.addNote(iid, text)
            withContext(Dispatchers.EDT) {
                commentButton.isEnabled = true
                if (currentIid != iid) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        commentArea.text = ""
                        loadNotes(iid)
                    }
                    else -> showError("detail.error.comment", result)
                }
            }
        }
    }

    private fun setCommentsTabTitle(count: Int?) {
        val title = if (count == null) {
            CockpitBundle.message("detail.tab.comments")
        } else {
            CockpitBundle.message("detail.tab.commentsCount", count)
        }
        tabbedPane.setTitleAt(COMMENTS_TAB_INDEX, title)
    }

    // --- Edit actions -------------------------------------------------------------------------

    /** No network before opening: title/description are already in [mr]. */
    private fun onEditTitleDescription(mr: GitLabMergeRequest) {
        val dialog = EditMrDialog(project, mr.title, mr.description.orEmpty())
        if (dialog.showAndGet()) {
            applyUpdate(mr.iid, MergeRequestUpdate(title = dialog.editedTitle, description = dialog.editedDescription))
        }
    }

    private fun onEditReviewers(mr: GitLabMergeRequest) {
        withMembers { members ->
            val dialog = EditReviewersDialog(project, members, mr.reviewers.map { it.id }.toSet())
            if (dialog.showAndGet()) {
                applyUpdate(mr.iid, MergeRequestUpdate(reviewerIds = dialog.selectedIds()))
            }
        }
    }

    private fun onEditAssignee(mr: GitLabMergeRequest) {
        withMembers { members ->
            val dialog = EditAssigneeDialog(project, members, mr.assignees.firstOrNull()?.id)
            if (dialog.showAndGet()) {
                applyUpdate(mr.iid, MergeRequestUpdate(assigneeIds = dialog.selectedIds()))
            }
        }
    }

    /** Loads members off the EDT (with a wait cursor), then runs [onLoaded] on the EDT. */
    private fun withMembers(onLoaded: (List<GitLabUser>) -> Unit) {
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        service.coroutineScope.launch {
            val result = service.getMembers()
            withContext(Dispatchers.EDT) {
                cursor = Cursor.getDefaultCursor()
                when (result) {
                    is GitLabResult.Success -> onLoaded(result.data)
                    else -> showError("detail.error.members", result)
                }
            }
        }
    }

    /** Runs the PUT off the EDT, then refreshes the detail and the list on success. */
    private fun applyUpdate(iid: Long, update: MergeRequestUpdate) {
        service.coroutineScope.launch {
            val result = service.updateMr(iid, update)
            withContext(Dispatchers.EDT) {
                when (result) {
                    is GitLabResult.Success -> {
                        if (currentIid == iid) showMr(result.data)
                        onListReloadRequested()
                    }
                    else -> showError("detail.error.update", result)
                }
            }
        }
    }

    // --- Small UI helpers ---------------------------------------------------------------------

    private fun setSingleMessage(text: String) {
        removeAll()
        val panel = JPanel(GridBagLayout()).apply { isOpaque = false }
        panel.add(JBLabel(text).apply { foreground = UIUtil.getInactiveTextColor() })
        add(panel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showError(key: String, result: GitLabResult<*>) {
        Messages.showErrorDialog(
            this,
            CockpitBundle.message(key, describe(result)),
            CockpitBundle.message("detail.error.title"),
        )
    }

    private fun badge(text: String, color: Color): JComponent =
        JBLabel(text).apply {
            foreground = color
            font = font.deriveFont(Font.BOLD)
        }

    private fun flowLine(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }

    // --- Edit dialogs -------------------------------------------------------------------------

    private class EditMrDialog(
        project: Project,
        initialTitle: String,
        initialDescription: String,
    ) : DialogWrapper(project) {

        private val titleField = JBTextField(initialTitle, 40)
        private val descriptionArea = JBTextArea(initialDescription, 14, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        init {
            title = CockpitBundle.message("dialog.editMr.title")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val scroll = JBScrollPane(descriptionArea).apply { preferredSize = JBUI.size(560, 300) }
            return FormBuilder.createFormBuilder()
                .addLabeledComponent(CockpitBundle.message("dialog.editMr.titleLabel"), titleField)
                .addLabeledComponentFillVertically(CockpitBundle.message("dialog.editMr.descriptionLabel"), scroll)
                .panel
        }

        override fun getPreferredFocusedComponent(): JComponent = titleField

        val editedTitle: String get() = titleField.text
        val editedDescription: String get() = descriptionArea.text
    }

    private class EditReviewersDialog(
        project: Project,
        members: List<GitLabUser>,
        currentReviewerIds: Set<Long>,
    ) : DialogWrapper(project) {

        private val checkList = CheckBoxList<GitLabUser>()

        init {
            title = CockpitBundle.message("dialog.editReviewers.title")
            members.forEach { member ->
                checkList.addItem(member, memberLabel(member), member.id in currentReviewerIds)
            }
            init()
        }

        override fun createCenterPanel(): JComponent =
            JBScrollPane(checkList).apply { preferredSize = JBUI.size(360, 320) }

        fun selectedIds(): List<Long> = checkList.checkedItems.map { it.id }
    }

    private class EditAssigneeDialog(
        project: Project,
        members: List<GitLabUser>,
        currentAssigneeId: Long?,
    ) : DialogWrapper(project) {

        private val combo = ComboBox<GitLabUser?>()

        init {
            title = CockpitBundle.message("dialog.editAssignee.title")
            val none = CockpitBundle.message("detail.none")
            combo.addItem(null)
            members.forEach { combo.addItem(it) }
            combo.renderer = SimpleListCellRenderer.create(none) { user -> user?.let(::memberLabel) ?: none }
            combo.selectedItem = members.firstOrNull { it.id == currentAssigneeId }
            init()
        }

        override fun createCenterPanel(): JComponent =
            FormBuilder.createFormBuilder()
                .addLabeledComponent(CockpitBundle.message("dialog.editAssignee.label"), combo)
                .panel

        fun selectedIds(): List<Long> =
            (combo.selectedItem as? GitLabUser)?.let { listOf(it.id) } ?: emptyList()
    }

    companion object {
        private const val COMMENTS_TAB_INDEX = 1

        /** For header rows: prefer the display name, fall back to the username. */
        private fun displayName(user: GitLabUser): String = user.name.ifBlank { user.username }

        /** For pickers: `Name (@username)`, disambiguating homonyms. */
        private fun memberLabel(user: GitLabUser): String {
            val name = user.name.ifBlank { user.username }
            return "$name (@${user.username})"
        }

        private fun describe(result: GitLabResult<*>): String = when (result) {
            is GitLabResult.HttpError -> "HTTP ${result.status}"
            is GitLabResult.NetworkError ->
                result.cause.message ?: result.cause.javaClass.simpleName
            is GitLabResult.Success<*> -> ""
        }
    }
}
