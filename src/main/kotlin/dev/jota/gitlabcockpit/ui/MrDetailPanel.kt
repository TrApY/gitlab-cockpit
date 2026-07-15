package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
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
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.MergeRequestUpdate
import dev.jota.gitlabcockpit.core.ApprovalsHealth
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.CommentThread
import dev.jota.gitlabcockpit.core.MergeAction
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.ReviewerSelectionModel
import dev.jota.gitlabcockpit.core.approvalsHealth
import dev.jota.gitlabcockpit.core.commentThreads
import dev.jota.gitlabcockpit.core.filterMembers
import dev.jota.gitlabcockpit.core.mergeButtonState
import dev.jota.gitlabcockpit.core.projectWebUrlOf
import dev.jota.gitlabcockpit.core.threadAnchorLabel
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The detail pane shown below the MR list. Renders one merge request inside a two-tab layout:
 *
 * - **Overview**: header (`!iid title` + DRAFT/conflicts badges + edit-title/description button),
 *   author/assignee row, reviewers row, an approvals row ("Approved by: …" + an Approve/Revoke
 *   button that reflects whether the current user already approved) and the markdown description.
 * - **Comments**: the MR's discussion threads (system notes filtered out) rendered as themed HTML —
 *   each thread's first note at root level, replies indented, with "Resolved" and diff-anchor tags —
 *   plus a text area whose button posts a new general comment or, in reply mode (entered from a
 *   thread's Reply link), a reply to that thread. Threads load lazily the first time the tab is shown
 *   for each MR and again on every detail refresh; the tab title carries the total note count.
 *
 * Editing is done through modal dialogs; every network call (detail load, member load, update,
 * approvals, notes, approve/unapprove, comment) runs on the service's coroutine scope and only
 * touches the EDT to render. Stale results are dropped by re-checking [currentRef] on the EDT.
 *
 * @param onListReloadRequested called after a successful edit or approval change so the parent can
 * silently refresh the MR list (e.g. so the "reviewer, not approved" filter reflects the change).
 */
class MrDetailPanel(
    private val project: Project,
    private val service: CockpitProjectService,
    private val onListReloadRequested: () -> Unit,
) : JPanel(BorderLayout()) {

    /** Ref of the MR currently displayed (or being loaded); null when showing the placeholder. */
    var currentRef: MrRef? = null
        private set

    /** The MR currently displayed; kept so the async upload load knows its project's base web URL. */
    private var currentMr: GitLabMergeRequest? = null

    private var detailJob: Job? = null
    private var notesJob: Job? = null

    /** The MR whose notes are loaded (or loading). Reset on every [showMr] so a refresh re-fetches. */
    private var notesLoadedForRef: MrRef? = null

    /**
     * Bumped on every notes (re)load and MR switch. The async upload re-apply carries the epoch it was
     * started under and is dropped once the epoch moves on, so a slow image download from a superseded
     * load never clobbers freshly rendered notes.
     */
    private var notesEpoch: Int = 0

    /** Recreated by [buildHeader]; updated by the async approvals load. */
    private var approvalsLabel: JBLabel? = null
    private var approvalButton: JButton? = null

    /** Recreated by [buildHeader]; its state is fully derived from the MR (no async load needed). */
    private var mergeButton: JButton? = null

    private val descriptionPane = CockpitHtml.createHtmlPane()
    private val descriptionScroll = JBScrollPane(descriptionPane)
    private val headerContainer = JPanel(BorderLayout()).apply { isOpaque = false }
    private val overviewPanel = JPanel(BorderLayout())

    private val notesPane = CockpitHtml.createHtmlPane { handleNotesLink(it) }
    private val notesScroll = JBScrollPane(notesPane)
    private val commentArea = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = CockpitBundle.message("detail.comment.placeholder")
    }
    private val commentButton = JButton(CockpitBundle.message("detail.comment.button"))

    /** The threads currently rendered in the Comments tab; used to resolve a Reply link to its author. */
    private var loadedThreads: List<CommentThread> = emptyList()

    /** The discussion id being replied to, or null in the general-comment mode. */
    private var replyingToDiscussionId: String? = null

    /** Label of the reply-context row ("Replying to <author>"), filled by [enterReplyMode]. */
    private val replyContextLabel = JBLabel()

    /** Row shown above the comment box while replying to a thread; hidden in general-comment mode. */
    private val replyContextPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        isVisible = false
        add(replyContextLabel)
        add(ActionLink(CockpitBundle.message("detail.comment.reply.cancel")) { exitReplyMode() })
    }

    /** Banner shown atop the Comments tab when the MR has pending draft notes; hidden otherwise. */
    private val draftBanner = JBLabel().apply {
        icon = AllIcons.General.Balloon
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }
    private val commentsPanel = JPanel(BorderLayout())

    private val tabbedPane = JBTabbedPane()

    private val pipelinesPanel = PipelinesPanel(project, service)

    private val changesPanel = ChangesPanel(
        project,
        service,
        onFileCountChanged = { count -> setChangesTabTitle(count) },
        onReviewSubmitted = { onReviewSubmitted() },
    )

    init {
        overviewPanel.add(headerContainer, BorderLayout.NORTH)
        overviewPanel.add(descriptionScroll, BorderLayout.CENTER)

        commentsPanel.add(draftBanner, BorderLayout.NORTH)
        commentsPanel.add(notesScroll, BorderLayout.CENTER)
        commentsPanel.add(buildCommentInput(), BorderLayout.SOUTH)

        tabbedPane.addTab(CockpitBundle.message("detail.tab.overview"), overviewPanel)
        tabbedPane.addTab(CockpitBundle.message("detail.tab.comments"), commentsPanel)
        tabbedPane.addTab(CockpitBundle.message("pipelines.tab"), pipelinesPanel)
        tabbedPane.addTab(CockpitBundle.message("changes.tab"), changesPanel)
        tabbedPane.addChangeListener {
            when (tabbedPane.selectedIndex) {
                COMMENTS_TAB_INDEX -> {
                    val ref = currentRef
                    if (ref != null && notesLoadedForRef != ref) loadNotes(ref)
                }
                PIPELINES_TAB_INDEX -> pipelinesPanel.onTabSelected()
                CHANGES_TAB_INDEX -> changesPanel.onTabSelected()
            }
        }
        commentButton.addActionListener { onSubmitComment() }

        showPlaceholder()
    }

    /** EDT. Shows the "select an MR" placeholder and forgets the current selection. */
    fun showPlaceholder() {
        currentRef = null
        currentMr = null
        pipelinesPanel.clear()
        changesPanel.clear()
        setSingleMessage(CockpitBundle.message("detail.placeholder"))
    }

    /** EDT. Kicks off a background detail load for [ref] and renders the result when it arrives. */
    fun loadDetail(ref: MrRef) {
        currentRef = ref
        commentArea.text = ""
        setSingleMessage(CockpitBundle.message("detail.loading"))
        detailJob?.cancel()
        detailJob = service.coroutineScope.launch {
            val result = service.getMrDetail(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> showMr(result.data)
                    else -> setSingleMessage(CockpitBundle.message("detail.error.load", describe(result)))
                }
            }
        }
    }

    /** EDT. Renders [mr] into the tabbed layout and kicks off the approvals (and lazy notes) loads. */
    private fun showMr(mr: GitLabMergeRequest) {
        val ref = MrRef(mr.projectId, mr.iid)
        currentRef = ref
        currentMr = mr

        headerContainer.removeAll()
        headerContainer.add(buildHeader(mr), BorderLayout.CENTER)
        setDescription(mr)

        // Reset the comment thread for the (possibly refreshed) MR; it reloads lazily / on demand.
        notesJob?.cancel()
        notesEpoch++
        notesLoadedForRef = null
        loadedThreads = emptyList()
        exitReplyMode()
        notesPane.text = CockpitHtml.wrapHtml("")
        draftBanner.isVisible = false
        setCommentsTabTitle(null)

        // Rebind the Pipelines tab; it reloads lazily when shown (or now, if already selected).
        pipelinesPanel.setMr(ref, mr.sourceBranch, mr.headPipeline)

        // Rebind the Changes tab; it reloads lazily when shown (or now, if already selected).
        changesPanel.setMr(ref, mr.diffRefs, projectWebUrlOf(mr))

        if (tabbedPane.parent !== this) {
            removeAll()
            add(tabbedPane, BorderLayout.CENTER)
        }
        revalidate()
        repaint()

        loadApprovals(ref)
        if (tabbedPane.selectedIndex == COMMENTS_TAB_INDEX) loadNotes(ref)
        if (tabbedPane.selectedIndex == PIPELINES_TAB_INDEX) pipelinesPanel.onTabSelected()
        if (tabbedPane.selectedIndex == CHANGES_TAB_INDEX) changesPanel.onTabSelected()
    }

    private fun setDescription(mr: GitLabMergeRequest) {
        val fragment = if (mr.description.isNullOrBlank()) {
            "<p><i>" + CockpitHtml.escapeHtml(CockpitBundle.message("detail.noDescription")) + "</i></p>"
        } else {
            CockpitHtml.stripBody(MarkdownRenderer.toHtml(mr.description))
        }
        val ref = MrRef(mr.projectId, mr.iid)
        applyMarkdownUploads(
            pane = descriptionPane,
            fragment = fragment,
            service = service,
            projectId = mr.projectId,
            projectWebUrl = projectWebUrlOf(mr),
            isCurrent = { currentRef == ref },
        )
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

        buildDatesLine(mr)?.let { header.add(it) }

        val approvalsLine = flowLine()
        val label = JBLabel(
            CockpitBundle.message("detail.approvedBy", CockpitBundle.message("detail.approvals.loading")),
        )
        val button = JButton(CockpitBundle.message("detail.approve")).apply { isEnabled = false }
        approvalsLine.add(label)
        approvalsLine.add(button)
        approvalsLabel = label
        approvalButton = button
        approvalsLine.add(buildMergeButton(mr))
        header.add(approvalsLine)

        return header
    }

    /**
     * The gray "Created … · Merged/Closed …" line, or null when the MR carries none of those
     * timestamps (so no empty row is added). Merged and Closed are mutually exclusive in practice.
     */
    private fun buildDatesLine(mr: GitLabMergeRequest): JComponent? {
        val parts = buildList {
            mr.createdAt?.let { add(CockpitBundle.message("detail.dates.created", formatRelative(it))) }
            mr.mergedAt?.let { add(CockpitBundle.message("detail.dates.merged", formatRelative(it))) }
            mr.closedAt?.let { add(CockpitBundle.message("detail.dates.closed", formatRelative(it))) }
        }
        if (parts.isEmpty()) return null
        val line = flowLine()
        line.add(
            JBLabel(parts.joinToString(DATE_SEPARATOR)).apply {
                foreground = UIUtil.getContextHelpForeground()
            },
        )
        return line
    }

    /**
     * Builds the Merge button for [mr] from [mergeButtonState]: enabled for a mergeable MR (or a
     * "when pipeline succeeds" variant), otherwise disabled with the blocker reason as a tooltip (no
     * tooltip when there is no reason, e.g. an already merged/closed MR).
     */
    private fun buildMergeButton(mr: GitLabMergeRequest): JButton {
        val button = JButton(CockpitBundle.message("detail.merge"))
        val ref = MrRef(mr.projectId, mr.iid)
        val state = mergeButtonState(mr.state, mr.detailedMergeStatus)
        when (state.action) {
            MergeAction.MERGE -> {
                button.isEnabled = true
                button.addActionListener { onMerge(ref, mr, mergeWhenPipelineSucceeds = false) }
            }
            MergeAction.MERGE_WHEN_PIPELINE_SUCCEEDS -> {
                button.text = CockpitBundle.message("detail.merge.whenPipeline")
                button.isEnabled = true
                button.addActionListener { onMerge(ref, mr, mergeWhenPipelineSucceeds = true) }
            }
            MergeAction.DISABLED -> {
                button.isEnabled = false
                button.toolTipText = state.reasonKey?.let { CockpitBundle.message(it) }
            }
        }
        mergeButton = button
        return button
    }

    /**
     * Opens the [MergeMrDialog] pre-checked from the remembered settings (falling back to the MR's own
     * squash / remove-source-branch defaults), then merges in the background. On success the remembered
     * options are updated (when the user opted in), the detail is reloaded and the list is refreshed.
     */
    private fun onMerge(ref: MrRef, mr: GitLabMergeRequest, mergeWhenPipelineSucceeds: Boolean) {
        val settings = GitLabCockpitSettings.getInstance()
        val squashDefault = settings.mergeSquash ?: mr.squash
        val deleteDefault = settings.mergeDeleteSourceBranch ?: (mr.forceRemoveSourceBranch ?: false)
        val dialog = MergeMrDialog(
            project,
            mr.sourceBranch,
            mr.targetBranch,
            squashDefault,
            deleteDefault,
            mergeWhenPipelineSucceeds,
        )
        if (!dialog.showAndGet()) return

        val squash = dialog.squash
        val removeSourceBranch = dialog.deleteSourceBranch
        if (dialog.rememberOptions) {
            settings.mergeSquash = squash
            settings.mergeDeleteSourceBranch = removeSourceBranch
        }

        mergeButton?.isEnabled = false
        service.coroutineScope.launch {
            val result = service.merge(ref, squash, removeSourceBranch, mergeWhenPipelineSucceeds)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        loadDetail(ref)
                        onListReloadRequested()
                    }
                    else -> {
                        mergeButton?.isEnabled = true
                        showError("detail.error.merge", result)
                    }
                }
            }
        }
    }

    // --- Approvals ----------------------------------------------------------------------------

    /** Fetches the fresh approval state and updates the approvals row (guarded by [currentRef]). */
    private fun loadApprovals(ref: MrRef) {
        service.coroutineScope.launch {
            val result = service.getApprovalsFor(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
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
        val left = approvals.approvalsLeft ?: 0
        val display = buildString {
            append(names.ifEmpty { CockpitBundle.message("detail.approvals.none") })
            if (left > 0) append(" ").append(CockpitBundle.message("detail.approvals.left", left))
        }
        approvalsLabel?.let { label ->
            label.text = CockpitBundle.message("detail.approvedBy", display)
            label.foreground = when (approvalsHealth(approvals)) {
                ApprovalsHealth.SATISFIED -> APPROVALS_SATISFIED_COLOR
                ApprovalsHealth.PENDING -> APPROVALS_PENDING_COLOR
                ApprovalsHealth.UNKNOWN -> UIUtil.getLabelForeground()
            }
        }

        val me = service.currentUser
        val approved = me != null && approvals.approvedBy.any { it.user.id == me.id }
        val button = approvalButton ?: return
        button.text = CockpitBundle.message(if (approved) "detail.revokeApproval" else "detail.approve")
        button.isEnabled = me != null
        button.actionListeners.toList().forEach { button.removeActionListener(it) }
        val ref = currentRef
        if (ref != null) button.addActionListener { onToggleApproval(ref, approved) }
    }

    /** Approves or revokes in the background, then refreshes approvals and the list on success. */
    private fun onToggleApproval(ref: MrRef, alreadyApproved: Boolean) {
        approvalButton?.isEnabled = false
        service.coroutineScope.launch {
            val result = if (alreadyApproved) service.unapprove(ref) else service.approve(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        loadApprovals(ref)
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
        panel.add(replyContextPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(commentArea), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        buttons.add(commentButton)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    /**
     * Fetches the MR's notes in the background and renders them (guarded by [currentRef]). The pending
     * draft count is loaded in the same cycle (in parallel, non-blocking and non-fatal) to drive the
     * "pending draft notes" banner.
     */
    private fun loadNotes(ref: MrRef) {
        notesLoadedForRef = ref
        notesEpoch++
        notesPane.text = CockpitHtml.wrapHtml(
            "<p><i>" + CockpitHtml.escapeHtml(CockpitBundle.message("detail.comment.loading")) + "</i></p>",
        )
        setCommentsTabTitle(null)
        notesJob?.cancel()
        notesJob = service.coroutineScope.launch {
            val (discussionsResult, draftsResult) = coroutineScope {
                val discussions = async { service.getMrDiscussions(ref) }
                val drafts = async { service.getDraftNotes(ref) }
                discussions.await() to drafts.await()
            }
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                renderDraftBanner((draftsResult as? GitLabResult.Success)?.data?.size ?: 0)
                when (discussionsResult) {
                    is GitLabResult.Success -> renderThreads(commentThreads(discussionsResult.data))
                    else -> {
                        notesLoadedForRef = null
                        loadedThreads = emptyList()
                        notesPane.text = CockpitHtml.wrapHtml(
                            "<p><i>" +
                                CockpitHtml.escapeHtml(
                                    CockpitBundle.message("detail.error.notes", describe(discussionsResult)),
                                ) +
                                "</i></p>",
                        )
                        setCommentsTabTitle(null)
                    }
                }
            }
        }
    }

    /** EDT. Shows or hides the pending-drafts banner based on [count]. */
    private fun renderDraftBanner(count: Int) {
        if (count > 0) {
            draftBanner.text = CockpitBundle.message("comments.draftBanner", count)
            draftBanner.isVisible = true
        } else {
            draftBanner.isVisible = false
        }
    }

    /**
     * Called by the Changes tab after a successful "Submit review": the published drafts are now
     * regular notes and the banner is stale. Clears the banner and invalidates the notes so the
     * Comments tab re-fetches (immediately if it is the visible tab, otherwise lazily on next show).
     */
    private fun onReviewSubmitted() {
        draftBanner.isVisible = false
        val ref = currentRef ?: return
        notesLoadedForRef = null
        if (tabbedPane.selectedIndex == COMMENTS_TAB_INDEX) loadNotes(ref)
    }

    /**
     * EDT. Renders the MR's discussion [threads] as one themed HTML document and updates the tab
     * counter (total number of notes across every thread). Each thread shows its first note at root
     * level and its replies indented; the thread's meta line carries a "Resolved" tag when resolved
     * and a `file:line` tag when diff-anchored, and every thread ends with a `Reply` link.
     */
    private fun renderThreads(threads: List<CommentThread>) {
        loadedThreads = threads
        val noteCount = threads.sumOf { it.notes.size }
        if (threads.isEmpty()) {
            notesPane.text = CockpitHtml.wrapHtml(
                "<p><i>" + CockpitHtml.escapeHtml(CockpitBundle.message("detail.comment.empty")) + "</i></p>",
            )
            notesPane.caretPosition = 0
            setCommentsTabTitle(noteCount)
            return
        }
        val metaColor = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val body = buildString {
            threads.forEachIndexed { index, thread ->
                appendThread(thread, metaColor)
                if (index < threads.lastIndex) append("<hr>")
            }
        }
        val ref = currentRef
        val epoch = notesEpoch
        applyMarkdownUploads(
            pane = notesPane,
            fragment = body,
            service = service,
            projectId = ref?.projectId ?: 0L,
            projectWebUrl = currentMr?.let(::projectWebUrlOf),
            isCurrent = { currentRef == ref && notesEpoch == epoch },
        )
        setCommentsTabTitle(noteCount)
    }

    /** Appends one thread's HTML: root note, indented replies, resolved/anchor tags and Reply link. */
    private fun StringBuilder.appendThread(thread: CommentThread, metaColor: String) {
        val notes = thread.notes
        val first = notes.first()
        append("<div style=\"color:").append(metaColor).append(";\">")
        append(CockpitHtml.escapeHtml(displayName(first.author)))
        append(" &middot; ")
        append(CockpitHtml.escapeHtml(formatRelative(first.createdAt)))
        if (thread.resolved) {
            append("&nbsp;&nbsp;[")
                .append(CockpitHtml.escapeHtml(CockpitBundle.message("detail.comment.thread.resolved")))
                .append("]")
        }
        threadAnchorLabel(thread)?.let { anchor ->
            append("&nbsp;&nbsp;[").append(CockpitHtml.escapeHtml(anchor)).append("]")
        }
        append("</div>")
        append(CockpitHtml.stripBody(MarkdownRenderer.toHtml(first.body)))
        if (notes.size > 1) {
            append("<blockquote>")
            for (reply in notes.drop(1)) {
                append("<div style=\"color:").append(metaColor).append(";\">")
                append(CockpitHtml.escapeHtml(displayName(reply.author)))
                append(" &middot; ")
                append(CockpitHtml.escapeHtml(formatRelative(reply.createdAt)))
                append("</div>")
                append(CockpitHtml.stripBody(MarkdownRenderer.toHtml(reply.body)))
            }
            append("</blockquote>")
        }
        append("<p><a href=\"").append(REPLY_LINK_PREFIX).append(thread.discussionId).append("\">")
        append(CockpitHtml.escapeHtml(CockpitBundle.message("detail.comment.thread.reply")))
        append("</a></p>")
    }

    /**
     * Posts the comment box's content: a reply to the active thread when in reply mode
     * ([replyingToDiscussionId] set), otherwise a new general note. On success the box is cleared,
     * reply mode is left and the thread is reloaded.
     */
    private fun onSubmitComment() {
        val ref = currentRef ?: return
        val text = commentArea.text.trim()
        if (text.isEmpty()) return
        val discussionId = replyingToDiscussionId
        commentButton.isEnabled = false
        service.coroutineScope.launch {
            val result: GitLabResult<*> =
                if (discussionId != null) service.replyToDiscussion(ref, discussionId, text)
                else service.addNote(ref, text)
            withContext(Dispatchers.EDT) {
                commentButton.isEnabled = true
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        commentArea.text = ""
                        exitReplyMode()
                        loadNotes(ref)
                    }
                    else -> showError("detail.error.comment", result)
                }
            }
        }
    }

    // --- Reply mode ---------------------------------------------------------------------------

    /**
     * Notes-pane hyperlink handler: a `cockpit:reply:<id>` link switches the comment box into reply
     * mode for that thread (consumed, returns true); any other href is left to the default browser
     * handling (returns false).
     */
    private fun handleNotesLink(href: String): Boolean {
        if (!href.startsWith(REPLY_LINK_PREFIX)) return false
        enterReplyMode(href.removePrefix(REPLY_LINK_PREFIX))
        return true
    }

    /** EDT. Switches the shared comment box to reply-to-thread mode for [discussionId]. */
    private fun enterReplyMode(discussionId: String) {
        val thread = loadedThreads.firstOrNull { it.discussionId == discussionId } ?: return
        replyingToDiscussionId = discussionId
        val author = thread.notes.firstOrNull()?.author?.let(::displayName).orEmpty()
        replyContextLabel.text = CockpitBundle.message("detail.comment.replyingTo", author)
        replyContextPanel.isVisible = true
        commentButton.text = CockpitBundle.message("detail.comment.reply.button")
        commentArea.emptyText.text = CockpitBundle.message("detail.comment.reply.placeholder")
        commentArea.requestFocusInWindow()
        revalidate()
        repaint()
    }

    /** EDT. Returns the comment box to general-comment mode (banner hidden, button back to Comment). */
    private fun exitReplyMode() {
        replyingToDiscussionId = null
        replyContextPanel.isVisible = false
        commentButton.text = CockpitBundle.message("detail.comment.button")
        commentArea.emptyText.text = CockpitBundle.message("detail.comment.placeholder")
        revalidate()
        repaint()
    }

    private fun setCommentsTabTitle(count: Int?) {
        val title = if (count == null) {
            CockpitBundle.message("detail.tab.comments")
        } else {
            CockpitBundle.message("detail.tab.commentsCount", count)
        }
        tabbedPane.setTitleAt(COMMENTS_TAB_INDEX, title)
    }

    /**
     * Updates the Changes tab title with the loaded file count (null → the plain "Changes"). Guarded
     * against being called before the tab is added (the [ChangesPanel] callback can fire during its
     * own construction, which happens before [tabbedPane] is populated).
     */
    private fun setChangesTabTitle(count: Int?) {
        if (tabbedPane.tabCount <= CHANGES_TAB_INDEX) return
        val title = if (count == null) {
            CockpitBundle.message("changes.tab")
        } else {
            CockpitBundle.message("changes.tabCount", count)
        }
        tabbedPane.setTitleAt(CHANGES_TAB_INDEX, title)
    }

    // --- Edit actions -------------------------------------------------------------------------

    /** No network before opening: title/description are already in [mr]. */
    private fun onEditTitleDescription(mr: GitLabMergeRequest) {
        val dialog = EditMrDialog(project, mr.title, mr.description.orEmpty())
        if (dialog.showAndGet()) {
            applyUpdate(
                MrRef(mr.projectId, mr.iid),
                MergeRequestUpdate(title = dialog.editedTitle, description = dialog.editedDescription),
            )
        }
    }

    private fun onEditReviewers(mr: GitLabMergeRequest) {
        withMembers(mr.projectId) { members ->
            val dialog = EditReviewersDialog(project, members, mr.reviewers.map { it.id }.toSet())
            if (dialog.showAndGet()) {
                applyUpdate(MrRef(mr.projectId, mr.iid), MergeRequestUpdate(reviewerIds = dialog.selectedIds()))
            }
        }
    }

    private fun onEditAssignee(mr: GitLabMergeRequest) {
        withMembers(mr.projectId) { members ->
            val dialog = EditAssigneeDialog(project, members, mr.assignees.firstOrNull()?.id)
            if (dialog.showAndGet()) {
                applyUpdate(MrRef(mr.projectId, mr.iid), MergeRequestUpdate(assigneeIds = dialog.selectedIds()))
            }
        }
    }

    /**
     * Loads [projectId]'s members off the EDT (with a wait cursor), then runs [onLoaded] on the EDT.
     * The MR's own project id is used so, in the "All projects" mode, the picker lists the members of
     * the MR's project rather than the git-resolved one.
     */
    private fun withMembers(projectId: Long, onLoaded: (List<GitLabUser>) -> Unit) {
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        service.coroutineScope.launch {
            val result = service.getMembers(projectId)
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
    private fun applyUpdate(ref: MrRef, update: MergeRequestUpdate) {
        service.coroutineScope.launch {
            val result = service.updateMr(ref, update)
            withContext(Dispatchers.EDT) {
                when (result) {
                    is GitLabResult.Success -> {
                        if (currentRef == ref) showMr(result.data)
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

    /**
     * Reviewer picker with an incremental search field over a [CheckBoxList]. The selection state
     * lives in a pure [ReviewerSelectionModel], so a member checked while unfiltered stays checked
     * even after a search hides it — the dialog is glue that repopulates the visible rows on each
     * keystroke and forwards toggles to the model. [selectedIds] just reads the model.
     */
    private class EditReviewersDialog(
        project: Project,
        members: List<GitLabUser>,
        currentReviewerIds: Set<Long>,
    ) : DialogWrapper(project) {

        private val model = ReviewerSelectionModel(members, currentReviewerIds)
        private val searchField = SearchTextField()
        private val checkList = CheckBoxList<GitLabUser>()

        init {
            title = CockpitBundle.message("dialog.editReviewers.title")
            checkList.setCheckBoxListListener { index, value ->
                checkList.getItemAt(index)?.let { model.setChecked(it.id, value) }
            }
            searchField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = repopulate()
            })
            repopulate()
            init()
        }

        /** EDT. Rebuilds the visible rows for the current query, checking each from the model. */
        private fun repopulate() {
            checkList.clear()
            model.visibleItems(searchField.text).forEach { member ->
                checkList.addItem(member, memberLabel(member), model.isChecked(member.id))
            }
        }

        override fun createCenterPanel(): JComponent {
            val scroll = JBScrollPane(checkList).apply { preferredSize = JBUI.size(360, 320) }
            return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(searchField, BorderLayout.NORTH)
                add(scroll, BorderLayout.CENTER)
            }
        }

        override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

        fun selectedIds(): List<Long> = model.selectedIds()
    }

    /**
     * Assignee picker: an incremental search field over a single-selection [JBList] whose first row
     * is always the fixed "None" option (null), kept visible regardless of the filter. Typing filters
     * the members below it via [filterMembers]; the current assignee is preselected, a double click
     * confirms, and [selectedIds] returns the picked user's id (empty for "None"). The `selectedIds`
     * contract is unchanged from the previous combo-based dialog.
     */
    private class EditAssigneeDialog(
        project: Project,
        private val members: List<GitLabUser>,
        currentAssigneeId: Long?,
    ) : DialogWrapper(project) {

        private val noneLabel = CockpitBundle.message("detail.none")
        private val searchField = SearchTextField()
        private val listModel = CollectionListModel<GitLabUser?>()
        private val userList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SimpleListCellRenderer.create(noneLabel) { user -> user?.let(::memberLabel) ?: noneLabel }
        }

        init {
            title = CockpitBundle.message("dialog.editAssignee.title")
            searchField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = repopulate()
            })
            object : DoubleClickListener() {
                override fun onDoubleClick(event: MouseEvent): Boolean {
                    if (userList.selectedIndex < 0) return false
                    doOKAction()
                    return true
                }
            }.installOn(userList)
            repopulate()
            // Preselect the current assignee, or the "None" row (index 0) when there is none.
            val preselect = members.firstOrNull { it.id == currentAssigneeId }
            if (preselect != null) userList.setSelectedValue(preselect, true) else userList.selectedIndex = 0
            init()
        }

        /**
         * EDT. Rebuilds the list — "None" first, then the members matching the current query. The
         * prior selection is kept when it survives the filter; otherwise the fixed "None" row is
         * selected (Swing's `setSelectedValue(null, …)` clears the selection, so it is set explicitly).
         */
        private fun repopulate() {
            val previous = userList.selectedValue
            val items = buildList<GitLabUser?> {
                add(null)
                addAll(filterMembers(members, searchField.text))
            }
            listModel.replaceAll(items)
            if (previous != null && items.contains(previous)) {
                userList.setSelectedValue(previous, true)
            } else {
                userList.selectedIndex = 0
            }
        }

        override fun createCenterPanel(): JComponent {
            val scroll = JBScrollPane(userList).apply { preferredSize = JBUI.size(360, 320) }
            return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(searchField, BorderLayout.NORTH)
                add(scroll, BorderLayout.CENTER)
            }
        }

        override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

        fun selectedIds(): List<Long> =
            userList.selectedValue?.let { listOf(it.id) } ?: emptyList()
    }

    /**
     * Merge confirmation dialog: a gray `source → target` summary and three checkboxes — Squash and
     * Delete source branch (pre-checked by the caller) plus "Remember these options". For the
     * merge-when-pipeline-succeeds action the OK button reads "Merge when pipeline succeeds"; the
     * caller reads [squash] / [deleteSourceBranch] / [rememberOptions] after a confirmation.
     */
    private class MergeMrDialog(
        project: Project,
        private val sourceBranch: String,
        private val targetBranch: String,
        squashDefault: Boolean,
        deleteDefault: Boolean,
        mergeWhenPipelineSucceeds: Boolean,
    ) : DialogWrapper(project) {

        private val squashCheck = JBCheckBox(CockpitBundle.message("dialog.merge.squash"), squashDefault)
        private val deleteCheck = JBCheckBox(CockpitBundle.message("dialog.merge.deleteSource"), deleteDefault)
        private val rememberCheck = JBCheckBox(CockpitBundle.message("dialog.merge.remember"), false)

        init {
            title = CockpitBundle.message("dialog.merge.title")
            init()
            if (mergeWhenPipelineSucceeds) setOKButtonText(CockpitBundle.message("detail.merge.whenPipeline"))
        }

        override fun createCenterPanel(): JComponent {
            val summary = JBLabel(CockpitBundle.message("dialog.merge.summary", sourceBranch, targetBranch)).apply {
                foreground = UIUtil.getContextHelpForeground()
            }
            return FormBuilder.createFormBuilder()
                .addComponent(summary)
                .addComponent(squashCheck)
                .addComponent(deleteCheck)
                .addComponent(rememberCheck)
                .panel
        }

        val squash: Boolean get() = squashCheck.isSelected
        val deleteSourceBranch: Boolean get() = deleteCheck.isSelected
        val rememberOptions: Boolean get() = rememberCheck.isSelected
    }

    companion object {
        private const val COMMENTS_TAB_INDEX = 1
        private const val PIPELINES_TAB_INDEX = 2
        private const val CHANGES_TAB_INDEX = 3

        /** Separator between the Overview date parts (a spaced middle dot U+00B7). */
        private const val DATE_SEPARATOR = " · "

        /** Green (light/dark) for a satisfied approvals line. */
        private val APPROVALS_SATISFIED_COLOR = JBColor(0x2E7D32, 0x499C54)

        /** Amber (light/dark) for a pending approvals line. */
        private val APPROVALS_PENDING_COLOR = JBColor(0xB07800, 0xD6A243)

        /** Href scheme of a thread's Reply link; the discussion id follows the prefix. */
        private const val REPLY_LINK_PREFIX = "cockpit:reply:"

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
