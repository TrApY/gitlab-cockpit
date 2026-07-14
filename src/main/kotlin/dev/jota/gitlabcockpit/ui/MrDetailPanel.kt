package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
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
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
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
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * The detail pane shown below the MR list. Renders one merge request: header (`!iid title` +
 * DRAFT/conflicts badges + edit-title/description button), author/assignee row, reviewers row, and
 * the markdown description rendered to themed HTML. Editing is done through modal dialogs; every
 * network call (detail load, member load, update) runs on the service's coroutine scope and only
 * touches the EDT to render — never the other way around.
 *
 * @param onListReloadRequested called after a successful edit so the parent can silently refresh
 * the MR list.
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

    private val descriptionPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val href = event.url?.toExternalForm() ?: event.description
                if (!href.isNullOrBlank()) BrowserUtil.browse(href)
            }
        }
    }

    private val descriptionScroll = JBScrollPane(descriptionPane)

    init {
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

    /** EDT. Renders [mr] into the header + description layout. */
    private fun showMr(mr: GitLabMergeRequest) {
        currentIid = mr.iid
        removeAll()
        add(buildHeader(mr), BorderLayout.NORTH)
        setDescription(mr)
        add(descriptionScroll, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun setDescription(mr: GitLabMergeRequest) {
        val fragment = if (mr.description.isNullOrBlank()) {
            "<p><i>" + escapeHtml(CockpitBundle.message("detail.noDescription")) + "</i></p>"
        } else {
            stripBody(MarkdownRenderer.toHtml(mr.description))
        }
        descriptionPane.text = wrapHtml(fragment)
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

        return header
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

    private fun wrapHtml(inner: String): String {
        val fg = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val link = ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return buildString {
            append("<html><head><style>")
            append("body { color: ").append(fg).append("; font-family: sans-serif; }")
            append("a { color: ").append(link).append("; }")
            append("code, pre { font-family: monospace; }")
            append("</style></head><body>")
            append(inner)
            append("</body></html>")
        }
    }

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

        /** Removes the single wrapping `<body>…</body>` the markdown generator emits. */
        private fun stripBody(html: String): String {
            var s = html.trim()
            if (s.startsWith("<body>")) s = s.removePrefix("<body>")
            if (s.endsWith("</body>")) s = s.removeSuffix("</body>")
            return s
        }

        private fun escapeHtml(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
