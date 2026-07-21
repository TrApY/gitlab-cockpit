package dev.jota.gitlabcockpit.ui

import com.intellij.ide.BrowserUtil
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
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.MergeRequestUpdate
import dev.jota.gitlabcockpit.core.ApprovalsHealth
import dev.jota.gitlabcockpit.core.Closing
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.CommentThread
import dev.jota.gitlabcockpit.core.MergeAction
import dev.jota.gitlabcockpit.core.MergeLinePresentation
import dev.jota.gitlabcockpit.core.MergeLineState
import dev.jota.gitlabcockpit.core.MrHeaderPresentation
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.ReviewerSelectionModel
import dev.jota.gitlabcockpit.core.approvalsHealth
import dev.jota.gitlabcockpit.core.commentThreads
import dev.jota.gitlabcockpit.core.filterMembers
import dev.jota.gitlabcockpit.core.mergeButtonState
import dev.jota.gitlabcockpit.core.mrHeaderPresentation
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The detail pane shown below the MR list. Renders one merge request inside a two-tab layout:
 *
 * - **Overview**: a native "Info"-style header — the title (+ a DRAFT badge), a `source → target`
 *   branch line, a muted `!iid · created … by …` meta line (with a `· merged/closed …` suffix once
 *   the MR is), the head pipeline's status (clickable, jumps to the Pipelines tab), a
 *   merge-readiness line ("Ready to merge" / "Merge blocked: …") with the "Approved by: …" line
 *   below it, and an action row of platform [ActionLink]s (Approve/Revoke, Merge, Open in browser,
 *   Watch/Unwatch, Edit reviewers/assignee) plus the edit-title/description button — followed by the
 *   markdown description.
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
    private var approvalLink: ActionLink? = null

    /** Recreated by [buildHeader]; its state is fully derived from the MR (no async load needed). */
    private var mergeLink: ActionLink? = null

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

    /** EDT. Moves keyboard focus into the detail's tab area (e.g. after opening an MR from the list). */
    fun focusContent() {
        tabbedPane.requestFocusInWindow()
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
        val pres = mrHeaderPresentation(
            mr = mr,
            authorName = displayName(mr.author),
            createdRelative = mr.createdAt?.let(::formatRelative),
            mergedRelative = mr.mergedAt?.let(::formatRelative),
            closedRelative = mr.closedAt?.let(::formatRelative),
        )

        val header = JPanel(VerticalLayout(JBUI.scale(4)))
        header.isOpaque = false
        header.border = CockpitTheme.panelBorder()

        // 1. Title + DRAFT badge.
        val titleLine = flowLine()
        titleLine.add(JBLabel(pres.title).apply { font = JBFont.h4() })
        if (pres.draft) {
            titleLine.add(badge(CockpitBundle.message("toolwindow.mr.draft"), CockpitTheme.warning))
        }
        header.add(titleLine)

        // 2. Branch line: source → target (target muted).
        val branchLine = flowLine()
        branchLine.add(JBLabel(pres.sourceBranch).apply { icon = AllIcons.Vcs.Branch })
        branchLine.add(
            JBLabel(CockpitBundle.message("detail.branch.arrow", pres.targetBranch)).apply {
                foreground = CockpitTheme.muted()
            },
        )
        header.add(branchLine)

        // 3. Meta line: !iid · created … by … (· merged/closed …).
        header.add(buildMetaLine(pres))

        // 4. Pipeline line (omitted when the MR has no head pipeline).
        buildPipelineLine(pres.pipelineStatus)?.let { header.add(it) }

        // 5. Merge-readiness line (omitted for a non-open MR) + the "Approved by: …" line below it.
        buildMergeLine(pres.merge)?.let { header.add(it) }
        header.add(buildApprovalsLine())

        // 6. Action row.
        header.add(buildActionsRow(mr))

        return header
    }

    /** The muted `!iid · created <relative> by <author>` meta line, with a merged/closed suffix. */
    private fun buildMetaLine(pres: MrHeaderPresentation): JComponent {
        val who = if (pres.createdRelative != null) {
            CockpitBundle.message("detail.meta.createdBy", pres.createdRelative, pres.authorName)
        } else {
            CockpitBundle.message("detail.meta.by", pres.authorName)
        }
        val parts = buildList {
            add(pres.reference)
            add(who)
            pres.closingRelative?.let { relative ->
                val key = if (pres.closing == Closing.MERGED) "detail.meta.merged" else "detail.meta.closed"
                add(CockpitBundle.message(key, relative))
            }
        }
        val line = flowLine()
        line.add(JBLabel(parts.joinToString(DATE_SEPARATOR)).apply { foreground = CockpitTheme.muted() })
        return line
    }

    /**
     * The head pipeline line — `Pipeline status:` followed by the status (colored via
     * [CockpitTheme.statusColor] with its [CockpitIcons] icon), clickable to jump to the Pipelines
     * tab. Null when [status] is null (the MR has no head pipeline), so no empty row is added.
     */
    private fun buildPipelineLine(status: String?): JComponent? {
        if (status == null) return null
        val line = flowLine()
        line.add(JBLabel(CockpitBundle.message("detail.pipeline.status")))
        val statusLabel = JBLabel(pipelineStatusText(status)).apply {
            icon = CockpitIcons.status(status)
            foreground = CockpitTheme.statusColor(status)
            toolTipText = CockpitBundle.message("detail.pipeline.tooltip")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        statusLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                tabbedPane.selectedIndex = PIPELINES_TAB_INDEX
            }
        })
        line.add(statusLabel)
        return line
    }

    /**
     * The merge-readiness line derived from [merge]: "Ready to merge" (success) or "Merge blocked:
     * <reason>" (warning). Null for a [MergeLineState.HIDDEN] presentation (a non-open MR), so no row
     * is added.
     */
    private fun buildMergeLine(merge: MergeLinePresentation): JComponent? {
        val (text, color) = when (merge.state) {
            MergeLineState.READY ->
                CockpitBundle.message("detail.merge.ready") to CockpitTheme.success
            MergeLineState.BLOCKED -> {
                val reason = CockpitBundle.message(merge.reasonKey ?: "merge.status.generic")
                CockpitBundle.message("detail.merge.blocked", reason) to CockpitTheme.warning
            }
            MergeLineState.HIDDEN -> return null
        }
        val line = flowLine()
        line.add(JBLabel(text).apply { foreground = color })
        return line
    }

    /** The "Approved by: …" line (label only); the async approvals load fills and colors it. */
    private fun buildApprovalsLine(): JComponent {
        val line = flowLine()
        val label = JBLabel(
            CockpitBundle.message("detail.approvedBy", CockpitBundle.message("detail.approvals.loading")),
        )
        approvalsLabel = label
        line.add(label)
        return line
    }

    /**
     * The action row: platform [ActionLink]s for Approve/Revoke, Merge, Open in browser,
     * Watch/Unwatch and the reviewer/assignee edit dialogs, plus the icon Edit button for the
     * title/description.
     */
    private fun buildActionsRow(mr: GitLabMergeRequest): JComponent {
        val row = flowLine()
        row.add(buildApproveLink())
        row.add(buildMergeLink(mr))
        row.add(ActionLink(CockpitBundle.message("detail.openInBrowser")) { BrowserUtil.browse(mr.webUrl) })
        row.add(buildWatchLink(mr))
        row.add(ActionLink(CockpitBundle.message("detail.editReviewers")) { onEditReviewers(mr) })
        row.add(ActionLink(CockpitBundle.message("detail.editAssignee")) { onEditAssignee(mr) })
        row.add(
            JButton(AllIcons.Actions.Edit).apply {
                toolTipText = CockpitBundle.message("detail.editTooltip")
                addActionListener { onEditTitleDescription(mr) }
            },
        )
        return row
    }

    /**
     * The Approve/Revoke action link, disabled until the async approvals load re-targets it in
     * [renderApprovals]. [ActionLink.autoHideOnDisable] is turned off so the disabled link stays
     * visible (greyed) while loading instead of vanishing.
     */
    private fun buildApproveLink(): ActionLink {
        val link = ActionLink(CockpitBundle.message("detail.approve")).apply {
            autoHideOnDisable = false
            isEnabled = false
        }
        approvalLink = link
        return link
    }

    /**
     * Builds the Watch / Unwatch toggle (GLC-28) as an [ActionLink]. Clicking flips this MR's
     * membership of the project's watch list — a purely local, synchronous persistence (no network) —
     * so a watched MR joins the notification scope even when it is outside the user's role scope. The
     * label reflects the current state both when the header is built and after each toggle.
     */
    private fun buildWatchLink(mr: GitLabMergeRequest): ActionLink {
        val ref = MrRef(mr.projectId, mr.iid)
        val link = ActionLink().apply { toolTipText = CockpitBundle.message("detail.watch.tooltip") }
        fun refreshText() {
            link.text = CockpitBundle.message(
                if (service.isWatched(ref)) "detail.unwatch" else "detail.watch",
            )
        }
        refreshText()
        link.addActionListener {
            service.setWatched(ref, !service.isWatched(ref))
            refreshText()
        }
        return link
    }

    /**
     * Builds the Merge action link for [mr] from [mergeButtonState]: enabled for a mergeable MR (or a
     * "when pipeline succeeds" variant), otherwise disabled with the blocker reason as a tooltip (no
     * tooltip when there is no reason, e.g. an already merged/closed MR). [ActionLink.autoHideOnDisable]
     * is turned off so a blocked Merge link stays visible (greyed) with its tooltip.
     */
    private fun buildMergeLink(mr: GitLabMergeRequest): ActionLink {
        val link = ActionLink(CockpitBundle.message("detail.merge")).apply { autoHideOnDisable = false }
        val ref = MrRef(mr.projectId, mr.iid)
        val state = mergeButtonState(mr.state, mr.detailedMergeStatus)
        when (state.action) {
            MergeAction.MERGE -> {
                link.isEnabled = true
                link.addActionListener { onMerge(ref, mr, mergeWhenPipelineSucceeds = false) }
            }
            MergeAction.MERGE_WHEN_PIPELINE_SUCCEEDS -> {
                link.text = CockpitBundle.message("detail.merge.whenPipeline")
                link.isEnabled = true
                link.addActionListener { onMerge(ref, mr, mergeWhenPipelineSucceeds = true) }
            }
            MergeAction.DISABLED -> {
                link.isEnabled = false
                link.toolTipText = state.reasonKey?.let { CockpitBundle.message(it) }
            }
        }
        mergeLink = link
        return link
    }

    /** `success` → `Success`: the head pipeline status with its first letter capitalized. */
    private fun pipelineStatusText(status: String): String =
        status.replaceFirstChar { it.uppercase() }

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

        mergeLink?.isEnabled = false
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
                        mergeLink?.isEnabled = true
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
                        approvalLink?.isEnabled = false
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
                ApprovalsHealth.SATISFIED -> CockpitTheme.success
                ApprovalsHealth.PENDING -> CockpitTheme.warning
                ApprovalsHealth.UNKNOWN -> UIUtil.getLabelForeground()
            }
        }

        val me = service.currentUser
        val approved = me != null && approvals.approvedBy.any { it.user.id == me.id }
        val link = approvalLink ?: return
        link.text = CockpitBundle.message(if (approved) "detail.revokeApproval" else "detail.approve")
        link.isEnabled = me != null
        link.actionListeners.toList().forEach { link.removeActionListener(it) }
        val ref = currentRef
        if (ref != null) link.addActionListener { onToggleApproval(ref, approved) }
    }

    /** Approves or revokes in the background, then refreshes approvals and the list on success. */
    private fun onToggleApproval(ref: MrRef, alreadyApproved: Boolean) {
        approvalLink?.isEnabled = false
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
                        approvalLink?.isEnabled = true
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
        panel.border = CockpitTheme.panelBorder()
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
            append("&nbsp;&nbsp;[<a href=\"").append(GOTO_LINK_PREFIX).append(thread.discussionId).append("\">")
                .append(CockpitHtml.escapeHtml(anchor)).append("</a>]")
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
     * Notes-pane hyperlink handler. A `cockpit:reply:<id>` link switches the comment box into reply
     * mode for that thread; a `cockpit:goto:<id>` link (a diff-anchored thread's `[file:line]` tag)
     * jumps to that thread inside the Changes tab's diff. Both are consumed (return true); any other
     * href is left to the default browser handling (returns false).
     */
    private fun handleNotesLink(href: String): Boolean {
        if (href.startsWith(REPLY_LINK_PREFIX)) {
            enterReplyMode(href.removePrefix(REPLY_LINK_PREFIX))
            return true
        }
        if (href.startsWith(GOTO_LINK_PREFIX)) {
            gotoDiscussionInChanges(href.removePrefix(GOTO_LINK_PREFIX))
            return true
        }
        return false
    }

    /**
     * Jumps from a Comments-tab thread anchor to that thread inside the diff: selects the Changes tab
     * (firing its lazy load when first shown) and asks it to reveal the discussion — select its file,
     * open the diff and scroll to the thread. An unknown or non-positioned id is a silent no-op.
     */
    private fun gotoDiscussionInChanges(discussionId: String) {
        tabbedPane.selectedIndex = CHANGES_TAB_INDEX
        changesPanel.revealDiscussion(discussionId)
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

        override fun createCenterPanel(): JComponent = panel {
            row(CockpitBundle.message("dialog.editMr.titleLabel")) {
                cell(titleField).align(AlignX.FILL)
            }
            row(CockpitBundle.message("dialog.editMr.descriptionLabel")) {
                cell(JBScrollPane(descriptionArea).apply { preferredSize = CockpitTheme.EDIT_MR_DIALOG_SIZE })
                    .align(Align.FILL)
            }.resizableRow()
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

        override fun createCenterPanel(): JComponent = panel {
            row {
                cell(searchField).align(AlignX.FILL)
            }
            row {
                cell(JBScrollPane(checkList).apply { preferredSize = CockpitTheme.REVIEWERS_DIALOG_SIZE })
                    .align(Align.FILL)
            }.resizableRow()
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
            cellRenderer = textCellRenderer<GitLabUser>(noneLabel) { user -> memberLabel(user) }
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

        override fun createCenterPanel(): JComponent = panel {
            row {
                cell(searchField).align(AlignX.FILL)
            }
            row {
                cell(JBScrollPane(userList).apply { preferredSize = CockpitTheme.REVIEWERS_DIALOG_SIZE })
                    .align(Align.FILL)
            }.resizableRow()
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

        override fun createCenterPanel(): JComponent = panel {
            row {
                cell(
                    JBLabel(CockpitBundle.message("dialog.merge.summary", sourceBranch, targetBranch)).apply {
                        foreground = UIUtil.getContextHelpForeground()
                    },
                )
            }
            row { cell(squashCheck) }
            row { cell(deleteCheck) }
            row { cell(rememberCheck) }
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

        /** Href scheme of a thread's Reply link; the discussion id follows the prefix. */
        private const val REPLY_LINK_PREFIX = "cockpit:reply:"

        /** Href scheme of a diff-anchored thread's "jump to diff" link; the discussion id follows. */
        private const val GOTO_LINK_PREFIX = "cockpit:goto:"

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
