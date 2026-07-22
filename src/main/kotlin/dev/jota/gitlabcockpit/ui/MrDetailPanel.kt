package dev.jota.gitlabcockpit.ui

import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
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
import dev.jota.gitlabcockpit.api.GitLabAward
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabLabel
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabNote
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.MergeRequestUpdate
import dev.jota.gitlabcockpit.core.ApprovalsHealth
import dev.jota.gitlabcockpit.core.AwardEmoji
import dev.jota.gitlabcockpit.core.COCKPIT_NOTIFICATION_GROUP
import dev.jota.gitlabcockpit.core.CheckoutTarget
import dev.jota.gitlabcockpit.core.Closing
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.CommentThread
import dev.jota.gitlabcockpit.core.LabelSelectionModel
import dev.jota.gitlabcockpit.core.MarkdownMarker
import dev.jota.gitlabcockpit.core.MergeAction
import dev.jota.gitlabcockpit.core.MergeLinePresentation
import dev.jota.gitlabcockpit.core.MergeLineState
import dev.jota.gitlabcockpit.core.MrHeaderPresentation
import dev.jota.gitlabcockpit.core.MrParticipant
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.MrRole
import dev.jota.gitlabcockpit.core.TimelineFilter
import dev.jota.gitlabcockpit.core.TimelineItem
import dev.jota.gitlabcockpit.core.applyDraftState
import dev.jota.gitlabcockpit.core.approvalsHealth
import dev.jota.gitlabcockpit.core.buildTimeline
import dev.jota.gitlabcockpit.core.commentThreads
import dev.jota.gitlabcockpit.core.eventIconKey
import dev.jota.gitlabcockpit.core.filterMembers
import dev.jota.gitlabcockpit.core.filterTimeline
import dev.jota.gitlabcockpit.core.mergeButtonState
import dev.jota.gitlabcockpit.core.mrHeaderPresentation
import dev.jota.gitlabcockpit.core.mrParticipants
import dev.jota.gitlabcockpit.core.projectWebUrlOf
import dev.jota.gitlabcockpit.core.stripDraftPrefix
import dev.jota.gitlabcockpit.core.threadAnchorLabel
import dev.jota.gitlabcockpit.core.wrapMarkdown
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import git4idea.branch.GitBrancher
import git4idea.fetch.GitFetchSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The detail pane shown below the MR list. Renders one merge request inside a two-tab layout:
 *
 * - **Info**: a native header — a people row of circular avatars (author, assignees, reviewers) above
 *   the title (+ a DRAFT badge), a `source → target` branch line, a muted `!iid · created … · by …`
 *   meta line (with a `· merged/closed …` suffix once the MR is), the head pipeline's status
 *   (clickable, jumps to the Pipelines card), a two-tone merge-readiness line ("Ready to merge" /
 *   "Merge blocked: <reason>" with a contextual Merge / Set auto-merge link) and an "Approved by: …"
 *   line, then the markdown description. A single pencil (top-right) opens the unified Edit dialog
 *   (title, description, reviewers, assignee); every other MR action lives on the vertical toolbar.
 * - **Events & Discussions**: a chronological timeline (GLC-34) merging the MR's GitLab system notes
 *   and its user discussion threads, rendered as **native cards** (GLC-38 / iter3 B) — rounded,
 *   subtly-shaded [RoundedCardPanel]s with a type icon per event and per-thread Reply / Resolve
 *   [ActionLink]s. A toolbar filters (All / Events / Discussions), toggles the sort order and offers a
 *   "+" that opens the [ComposerDialog] popup for a new general comment; a thread's Reply link opens the
 *   same popup in reply mode. The timeline loads lazily the first time the tab is shown for each MR and
 *   again on every detail refresh; the tab title carries the human-note count.
 *
 * Editing is done through dialogs; every network call runs on the service's coroutine scope and only
 * touches the EDT to render. Stale results are dropped by re-checking [currentRef] on the EDT.
 *
 * @param onListReloadRequested called after a successful edit or approval change so the parent can
 * silently refresh the MR list (e.g. so the "reviewer, not approved" filter reflects the change).
 *
 * As a per-MR tool-window tab (GLC-35) each panel is its own [Disposable]: the tab's content disposer
 * is this panel, so closing the tab (or the project) cancels its in-flight loads and those of its
 * embedded Pipelines/Changes tabs.
 */
class MrDetailPanel(
    private val project: Project,
    private val service: CockpitProjectService,
    private val onListReloadRequested: () -> Unit,
) : JPanel(BorderLayout()), Disposable {

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
     * Bumped on every notes (re)load and MR switch. Each card's async upload re-apply carries the epoch
     * it was started under and is dropped once the epoch moves on, so a slow image download from a
     * superseded load never clobbers freshly rendered cards.
     */
    private var notesEpoch: Int = 0

    /** Recreated by [buildHeader]; the async approvals load fills them (two-tone: label + muted detail). */
    private var approvalsPrefixLabel: JBLabel? = null
    private var approvalsDetailLabel: JBLabel? = null

    /**
     * The Info header's "Pipeline status" value label (GLC-43 B), recreated by [buildPipelineLine]. The
     * pipelines card's live poll updates it through [updatePipelineStatusLine] when the head pipeline's
     * aggregate status changes; null when the MR has no head pipeline (no line was built).
     */
    private var pipelineStatusLabel: JBLabel? = null

    /** The header's read-only label-chips line (GLC-42); recreated by [buildHeader], re-colored async. */
    private var labelsLine: JPanel? = null

    /**
     * Label name → `#rrggbb` color, cached per project (GLC-42). The MR payload carries only label
     * *names*, so their colors are fetched once per project (label colors rarely change) and reused to
     * paint the read-only chips' color dots on both the Info header and later MRs of the same project.
     */
    private val labelColorsByProject = ConcurrentHashMap<Long, Map<String, String>>()

    /** The contextual Merge / Set auto-merge link on the merge-readiness line; disabled while merging. */
    private var mergeLink: ActionLink? = null

    private val descriptionPane = CockpitHtml.createHtmlPane()
    private val descriptionScroll = JBScrollPane(descriptionPane)
    private val headerContainer = JPanel(BorderLayout()).apply { isOpaque = false }
    private val overviewPanel = JPanel(BorderLayout())

    /** Shared circular-avatar cache; feeds the header people row (author, assignees, reviewers). */
    private val avatarCache = AvatarCache.getInstance()

    /** The timeline's native card stack (GLC-38 / iter3 B); one [RoundedCardPanel] per event/thread. */
    private val timelineContainer = JPanel(VerticalLayout(JBUI.scale(TIMELINE_CARD_GAP))).apply {
        border = JBUI.Borders.empty(4, 8)
    }
    private val notesScroll = JBScrollPane(timelineContainer)

    /** The threads currently rendered in the timeline; used to resolve a Reply link to its author. */
    private var loadedThreads: List<CommentThread> = emptyList()

    /** All notes of the MR incl. GitLab system ones — the timeline's event source; reloaded with threads. */
    private var loadedTimelineNotes: List<GitLabNote> = emptyList()

    /**
     * Lazily-loaded emoji reactions per note (GLC-40), keyed by note id. Discussion notes don't carry
     * their awards, so each card's reaction row is filled in the background (bounded by
     * [awardsSemaphore]) and cached here, keyed also by the note's `updated_at` so a re-edited note
     * re-fetches. Cleared on every MR switch ([showMr]).
     */
    private val awardsCache = ConcurrentHashMap<Long, CachedAwards>()

    /** Bounds the background award-emoji fetches to 4 concurrent requests (GLC-40, avoids an N+1 storm). */
    private val awardsSemaphore = Semaphore(4)

    /** Session-only timeline filter (All / Events / Discussions); not persisted across restarts. */
    private var timelineFilter: TimelineFilter = TimelineFilter.ALL

    /** Session-only sort direction; true = oldest first (the GitLab web default). */
    private var timelineAscending: Boolean = true

    /** Toolbar filter combo (All / Events / Discussions); re-renders the loaded timeline on change. */
    private val timelineFilterCombo = ComboBox(TimelineFilter.entries.toTypedArray()).apply {
        renderer = textCellRenderer<TimelineFilter>("") { timelineFilterLabel(it) }
        selectedItem = TimelineFilter.ALL
        toolTipText = CockpitBundle.message("detail.timeline.filter.tooltip")
        addActionListener {
            val choice = selectedItem as? TimelineFilter ?: return@addActionListener
            if (choice == timelineFilter) return@addActionListener
            timelineFilter = choice
            renderTimeline(loadedThreads)
        }
    }

    /** Toolbar sort toggle; flips [timelineAscending] and re-renders the loaded timeline. */
    private val timelineOrderButton = JButton(AllIcons.General.ArrowDown).apply {
        toolTipText = CockpitBundle.message("detail.timeline.order.oldest")
        addActionListener {
            timelineAscending = !timelineAscending
            refreshOrderButton()
            renderTimeline(loadedThreads)
        }
    }

    /** Session-only free-text timeline filter (GLC-40); empty = no filtering. */
    private var timelineQuery: String = ""

    /**
     * Toolbar search field (GLC-40): an in-memory, case-insensitive filter over the loaded timeline —
     * event/note bodies and authors (see [filterTimeline]). Re-renders the loaded timeline on each
     * keystroke; never hits the network.
     */
    private val timelineSearchField = SearchTextField().apply {
        toolTipText = CockpitBundle.message("detail.timeline.search.tooltip")
        textEditor.columns = 14
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (text == timelineQuery) return
                timelineQuery = text
                renderTimeline(loadedThreads)
            }
        })
    }

    /** Toolbar "+" that opens the composer popup for a new general comment (GLC-38 / iter3 F13). */
    private val addCommentButton = JButton(AllIcons.General.Add).apply {
        toolTipText = CockpitBundle.message("detail.composer.add.tooltip")
        addActionListener { openComposer(replyToDiscussionId = null) }
    }

    /** Banner shown atop the timeline when the MR has pending draft notes; hidden otherwise. */
    private val draftBanner = JBLabel().apply {
        icon = AllIcons.General.Balloon
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }
    private val commentsPanel = JPanel(BorderLayout())

    /** Whether the current user has approved this MR; drives the toolbar Approve/Revoke toggle. */
    private var approvedByMe: Boolean = false

    /** The right-hand "main" card: a small tabbed pane with Info and Events & Discussions. */
    private val mainTabbedPane = JBTabbedPane()

    /**
     * The Events & Discussions tab's own title component (GLC-38 / iter3 E). A plain [JBLabel] is used
     * as the tab component (via `setTabComponentAt`) so the literal `&` in "Events & Discussions" is
     * painted verbatim — a mnemonic-processing tab title would otherwise swallow it. A click selects the
     * tab (a bare label does not forward clicks to the tabbed pane on its own).
     */
    private val timelineTabLabel = JBLabel(CockpitBundle.message("detail.tab.timeline")).apply {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                mainTabbedPane.selectedIndex = TIMELINE_TAB_INDEX
            }
        })
    }

    /** Swaps the right side between the "main" card and the drill-in "pipelines" card. */
    private val cardLayout = CardLayout()
    private val rightCards = JPanel(cardLayout)

    /** The MR tab's horizontal splitter: the changes tree on the left, the cards on the right. */
    private val splitter = OnePixelSplitter(false, MRTAB_SPLITTER_KEY, 0.55f)

    /** The whole MR view (WEST toolbar + CENTER splitter); swapped in for the placeholder in [showMr]. */
    private val contentPanel = JPanel(BorderLayout())

    /** The MR tab's vertical action toolbar; kept so approve/watch toggles can refresh its state. */
    private var mrToolbar: ActionToolbar? = null

    private val pipelinesPanel = PipelinesPanel(project, service) { status ->
        updatePipelineStatusLine(status)
    }

    private val changesPanel = ChangesPanel(
        project,
        service,
        onFileCountChanged = { },
        onReviewSubmitted = { onReviewSubmitted() },
    )

    init {
        // Info card content: the header (+ top-right edit pencil) + the markdown description.
        overviewPanel.add(headerContainer, BorderLayout.NORTH)
        overviewPanel.add(descriptionScroll, BorderLayout.CENTER)

        // Events & Discussions card: filter/sort/+ toolbar + draft banner, then the native card stack.
        val commentsNorth = JPanel(BorderLayout()).apply { isOpaque = false }
        commentsNorth.add(buildTimelineToolbar(), BorderLayout.NORTH)
        commentsNorth.add(draftBanner, BorderLayout.SOUTH)
        commentsPanel.add(commentsNorth, BorderLayout.NORTH)
        commentsPanel.add(notesScroll, BorderLayout.CENTER)

        // The "main" card: a small tabbed pane with Info | Events & Discussions.
        mainTabbedPane.addTab(CockpitBundle.message("detail.tab.overview"), overviewPanel)
        mainTabbedPane.addTab(CockpitBundle.message("detail.tab.timeline"), commentsPanel)
        mainTabbedPane.setTabComponentAt(TIMELINE_TAB_INDEX, timelineTabLabel)
        mainTabbedPane.addChangeListener {
            if (mainTabbedPane.selectedIndex == TIMELINE_TAB_INDEX) {
                val ref = currentRef
                if (ref != null && notesLoadedForRef != ref) loadNotes(ref)
            }
        }

        // The "pipelines" drill-in card: a "← Back" link atop the full PipelinesPanel.
        val pipelinesCard = JPanel(BorderLayout())
        val backRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        backRow.add(ActionLink(CockpitBundle.message("detail.pipelines.back")) { showMainCard() })
        pipelinesCard.add(backRow, BorderLayout.NORTH)
        pipelinesCard.add(pipelinesPanel, BorderLayout.CENTER)

        rightCards.add(mainTabbedPane, CARD_MAIN)
        rightCards.add(pipelinesCard, CARD_PIPELINES)

        // Left: the changes tree (+ counter, + pending review). Right: the cards.
        splitter.firstComponent = changesPanel
        splitter.secondComponent = rightCards
        // GLC-38 / iter3 D — root cause: OnePixelSplitter is born with myHonorMinimumSize = true, so its
        // divider is clamped to keep both sides above their minimum width. The right side is a CardLayout
        // whose minimum is the MAX over ALL cards (java.awt.CardLayout aggregates every child, even the
        // hidden ones). Once the Pipelines card is shown and its combo/tree/stage-strip get populated,
        // that card's minimum width balloons, so the CardLayout's aggregate minimum (read even while the
        // main card is visible) balloons too and the splitter freezes the divider — the "wide dead band"
        // JoTa saw, undraggable after entering Pipelines. Ignoring component minimums makes the divider
        // freely draggable in every card and after Back, while the persisted proportion still applies.
        splitter.setHonorComponentsMinimumSize(false)

        contentPanel.add(buildToolbar(), BorderLayout.WEST)
        contentPanel.add(splitter, BorderLayout.CENTER)

        showPlaceholder()
    }

    /** Shows the "main" card (Info | Events & Discussions), stopping the pipelines card's live poll. */
    private fun showMainCard() {
        pipelinesPanel.onCardHidden()
        cardLayout.show(rightCards, CARD_MAIN)
    }

    /**
     * Shows the "pipelines" drill-in card, loading its pipelines the first time it is shown and starting
     * its live-status poll (GLC-43 B) while it stays visible.
     */
    private fun showPipelinesCard() {
        cardLayout.show(rightCards, CARD_PIPELINES)
        pipelinesPanel.onTabSelected()
        pipelinesPanel.onCardShown()
    }

    // --- Action toolbar (WEST) ----------------------------------------------------------------

    /**
     * The MR tab's single vertical action toolbar (GLC-37 / iter3 A4, G20). The MR-level actions come
     * first — Approve/Revoke, Request changes, Watch/Unwatch, Refresh, then Close / Copy link / Open in
     * browser — followed by the changed-file tree actions folded in from [ChangesPanel.treeActions]. The
     * [splitter] is the toolbar's target component; each action's own [AnAction.update] drives its state.
     */
    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(approveAction())
            add(requestChangesAction())
            add(watchAction())
            add(refreshAction())
            add(checkoutAction())
            addSeparator()
            add(closeAction())
            add(copyLinkAction())
            add(openInBrowserAction())
            addSeparator()
            changesPanel.treeActions().forEach { add(it) }
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(MR_TOOLBAR_PLACE, group, false)
        toolbar.targetComponent = splitter
        mrToolbar = toolbar
        return toolbar.component
    }

    /**
     * Approve / Revoke toggle (iter3 A4 / ADENDA 2). Reuses [onToggleApproval] and reflects the current
     * [approvedByMe] state with the new-UI circled green check ([AllIcons.Status.Success]): pressed
     * ([Toggleable] SELECTED) with a "Revoke approval" tooltip once approved, unpressed to approve.
     * Disabled until the current user and MR are known.
     */
    private fun approveAction(): AnAction = object : AnAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentRef != null && service.currentUser != null
            e.presentation.icon = AllIcons.Status.Success
            e.presentation.text = CockpitBundle.message(
                if (approvedByMe) "detail.revokeApproval" else "detail.approve",
            )
            Toggleable.setSelected(e.presentation, approvedByMe)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val ref = currentRef ?: return
            onToggleApproval(ref, approvedByMe)
        }
    }

    /**
     * Request changes (iter3 A4 / ADENDA 2; icon [AllIcons.Vcs.History] — GLC-43 C12). GitLab has no
     * native "request changes" review state, so this is a convenience: if the MR is currently approved
     * by me it first revokes that approval (an approval and a change-request are contradictory), then
     * jumps to the Events & Discussions tab and opens the composer popup so the reviewer can spell out
     * the changes.
     *
     * The icon is the platform's monochrome history/clock (`vcs/history.svg`, New UI
     * `expui/general/history.svg`) — the "waiting for updates" semantics of the reference — deliberately
     * **not** the old circled-red [AllIcons.General.Error], which read as an error and was
     * indistinguishable from the circled-red Close action's icon.
     */
    private fun requestChangesAction(): AnAction = object : AnAction(
        CockpitBundle.message("detail.toolbar.requestChanges"),
        null,
        AllIcons.Vcs.History,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentRef != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val ref = currentRef ?: return
            if (approvedByMe) onToggleApproval(ref, alreadyApproved = true)
            showMainCard()
            mainTabbedPane.selectedIndex = TIMELINE_TAB_INDEX
            openComposer(replyToDiscussionId = null)
        }
    }

    /**
     * Watch / Unwatch toggle (GLC-28 moved to the toolbar in iter3 G20). Clicking flips this MR's
     * membership of the project's watch list — a purely local, synchronous persistence (no network) — so
     * a watched MR joins the notification scope even outside the user's role scope. An eye icon
     * ([AllIcons.Actions.Show]) reads pressed ([Toggleable] SELECTED) while watched.
     */
    private fun watchAction(): AnAction = object : AnAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val ref = currentRef
            e.presentation.isEnabled = ref != null
            val watched = ref != null && service.isWatched(ref)
            e.presentation.icon = AllIcons.Actions.Show
            e.presentation.text = CockpitBundle.message(if (watched) "detail.unwatch" else "detail.watch")
            Toggleable.setSelected(e.presentation, watched)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val ref = currentRef ?: return
            service.setWatched(ref, !service.isWatched(ref))
            mrToolbar?.updateActionsAsync()
        }
    }

    /** Refreshes the whole MR (detail, changes tree and timeline) by re-loading its detail. */
    private fun refreshAction(): AnAction = object : AnAction(
        CockpitBundle.message("detail.toolbar.refresh"),
        null,
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentRef != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            currentRef?.let { loadDetail(it) }
        }
    }

    /**
     * Checkout the MR's source branch locally (GLC-42, icon [AllIcons.Actions.CheckOut] — the platform's
     * diagonal checkout arrow; the field is spelled `CheckOut` with a capital O in 2025.2, and
     * `AllIcons.Actions.Checkout` / `AllIcons.Vcs.CherryPick` do not exist). Enabled only for a
     * **same-project** MR (`source_project_id == project_id`) whose
     * git repository + remote the plugin can resolve ([CockpitProjectService.resolveCheckoutTarget]);
     * for a fork MR, an MR with no `source_project_id`, or an unresolvable remote it is disabled with a
     * "same-project only" tooltip. Clicking fetches the resolved remote and checks the branch out (see
     * [onCheckout]).
     */
    private fun checkoutAction(): AnAction = object : AnAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.CheckOut
            // Non-null exactly when the action is enabled: a same-project MR with a resolvable checkout
            // target. Its value is the source branch, used in the "Checkout '<branch>'" tooltip.
            val branch = currentMr
                ?.takeIf { it.sourceProjectId != null && it.sourceProjectId == it.projectId }
                ?.takeIf { service.resolveCheckoutTarget() != null }
                ?.sourceBranch
            e.presentation.isEnabled = branch != null
            e.presentation.text = branch
                ?.let { CockpitBundle.message("detail.toolbar.checkout", it) }
                ?: CockpitBundle.message("detail.toolbar.checkout.disabled")
        }

        override fun actionPerformed(e: AnActionEvent) = onCheckout()
    }

    /**
     * Fetches the resolved remote and checks the MR's source branch out, both via Git4Idea's own
     * machinery (GLC-42). The fetch runs in a platform [Task.Backgroundable] (its progress bar is
     * Git4Idea's); on a failed fetch [GitFetchResult.showNotificationIfFailed] already fires the error
     * notification and the checkout is skipped. On a successful fetch the checkout is scheduled on the
     * EDT — [GitBrancher] then runs its own background task with progress and error notifications (see
     * [doCheckout]). A no-longer-resolvable target (the action was enabled, then the repo changed) is a
     * silent no-op.
     */
    private fun onCheckout() {
        val mr = currentMr ?: return
        val target = service.resolveCheckoutTarget() ?: return
        val branch = mr.sourceBranch
        object : Task.Backgroundable(
            project,
            CockpitBundle.message("detail.checkout.progress", branch),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val fetched = GitFetchSupport.fetchSupport(project)
                    .fetch(target.repository, target.remote)
                    .showNotificationIfFailed()
                if (!fetched) return
                ApplicationManager.getApplication().invokeLater(
                    { doCheckout(target, branch) },
                    ModalityState.nonModal(),
                )
            }
        }.queue()
    }

    /**
     * EDT. Checks [branch] out into [target]'s repository via [GitBrancher] (which schedules its own
     * progress-bearing background task and notifies on failure). If a local branch of that name already
     * exists it is simply checked out; otherwise a new local branch is created tracking the remote
     * branch `<remote>/<branch>` (the standard `git checkout -b <branch> <remote>/<branch>` flow, which
     * sets up tracking automatically).
     */
    private fun doCheckout(target: CheckoutTarget, branch: String) {
        val brancher = GitBrancher.getInstance(project)
        val repos = listOf(target.repository)
        val local = target.repository.branches.findLocalBranch(branch)
        if (local != null) {
            brancher.checkout(branch, false, repos, null)
        } else {
            brancher.checkoutNewBranchStartingFrom(branch, "${target.remote.name}/$branch", repos, null)
        }
    }

    /**
     * Close merge request (iter3 A4 / ADENDA 2, icon [AllIcons.RunConfigurations.TestError] — the circled
     * red X, since the platform has no `AllIcons.Status.Error` in 2025.2). Enabled only while the MR is
     * open; asks for confirmation, then sends a `state_event=close` update.
     */
    private fun closeAction(): AnAction = object : AnAction(
        CockpitBundle.message("detail.toolbar.close"),
        null,
        AllIcons.RunConfigurations.TestError,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentMr?.state == "opened"
        }

        override fun actionPerformed(e: AnActionEvent) = onCloseMr()
    }

    /** Copies the MR's web URL to the clipboard (icon [CockpitIcons.copyLink], the chain glyph — GLC-43
     * C13) and confirms with a balloon. */
    private fun copyLinkAction(): AnAction = object : AnAction(
        CockpitBundle.message("detail.toolbar.copyLink"),
        null,
        CockpitIcons.copyLink,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentMr != null
        }

        override fun actionPerformed(e: AnActionEvent) = onCopyLink()
    }

    /** Opens the MR's GitLab page in the external browser. */
    private fun openInBrowserAction(): AnAction = object : AnAction(
        CockpitBundle.message("detail.openInBrowser"),
        null,
        AllIcons.General.Web,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentMr != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            currentMr?.let { BrowserUtil.browse(it.webUrl) }
        }
    }

    /** Confirms and closes the MR (`state_event=close`), then refreshes the detail and the list. */
    private fun onCloseMr() {
        val mr = currentMr ?: return
        if (!ConfirmCloseDialog(project, mr.iid).showAndGet()) return
        applyUpdate(MrRef(mr.projectId, mr.iid), MergeRequestUpdate(stateEvent = "close"))
    }

    /** Copies the current MR's web URL to the clipboard and fires a "Link copied" balloon. */
    private fun onCopyLink() {
        val mr = currentMr ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(mr.webUrl))
        NotificationGroupManager.getInstance()
            .getNotificationGroup(COCKPIT_NOTIFICATION_GROUP)
            .createNotification(
                CockpitBundle.message("detail.toolbar.copyLink.done"),
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    /** EDT. Shows the "select an MR" placeholder and forgets the current selection. */
    fun showPlaceholder() {
        currentRef = null
        currentMr = null
        pipelinesPanel.clear()
        changesPanel.clear()
        setSingleMessage(CockpitBundle.message("detail.placeholder"))
    }

    /**
     * Cancels this panel's in-flight loads and those of its embedded tabs when its tab (or the project)
     * is closed. [ChangesPanel.clear] / [PipelinesPanel.clear] cancel their own coroutine jobs.
     */
    override fun dispose() {
        detailJob?.cancel()
        notesJob?.cancel()
        pipelinesPanel.clear()
        changesPanel.clear()
    }

    /** EDT. Kicks off a background detail load for [ref] and renders the result when it arrives. */
    fun loadDetail(ref: MrRef) {
        currentRef = ref
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
        headerContainer.add(buildEditCorner(mr), BorderLayout.EAST)
        setDescription(mr)

        // Reset the approval state until the async approvals load re-derives it (drives the toolbar toggle).
        approvedByMe = false

        // Reset the timeline for the (possibly refreshed) MR; it reloads lazily / on demand.
        notesJob?.cancel()
        notesEpoch++
        notesLoadedForRef = null
        loadedThreads = emptyList()
        loadedTimelineNotes = emptyList()
        awardsCache.clear()
        timelineQuery = ""
        timelineSearchField.text = ""
        showTimelineMessage(null)
        draftBanner.isVisible = false
        setTimelineTabTitle(null)

        // Rebind the pipelines drill-in; it reloads lazily the first time its card is shown.
        pipelinesPanel.setMr(ref, mr.sourceBranch, mr.headPipeline)

        // Rebind the changes tree; it is always visible now, so it is (re)loaded eagerly below.
        changesPanel.setMr(ref, mr.diffRefs, projectWebUrlOf(mr))

        // A (re)load always lands on the main card, never lingering on the previous MR's pipelines.
        showMainCard()

        if (contentPanel.parent !== this) {
            removeAll()
            add(contentPanel, BorderLayout.CENTER)
        }
        revalidate()
        repaint()

        loadApprovals(ref)
        // The chips are already painted from the per-project color cache when present; only fetch (once
        // per project) when the colors are not cached yet.
        if (mr.labels.isNotEmpty() && !labelColorsByProject.containsKey(mr.projectId)) loadLabelColors(ref, mr)
        changesPanel.onTabSelected()
        mrToolbar?.updateActionsAsync()
        if (mainTabbedPane.selectedIndex == TIMELINE_TAB_INDEX) loadNotes(ref)
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

        // 0. People row: circular avatars (author, assignees, reviewers) with a name tooltip, no text.
        header.add(buildAvatarsRow(mr))

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

        // 3. Meta line: !iid · created … · by … (· merged/closed …) — single muted label.
        header.add(buildMetaLine(pres))

        // 3b. Labels line (read-only chips; GLC-42) — omitted when the MR has no labels. The chips show
        // immediately with any cached colors; the async load then fills in / corrects them.
        if (mr.labels.isNotEmpty()) {
            val line = flowLine()
            labelsLine = line
            renderLabelChips(line, mr.labels, labelColorsByProject[mr.projectId].orEmpty())
            header.add(line)
        } else {
            labelsLine = null
        }

        // 4. Pipeline line (omitted when the MR has no head pipeline).
        buildPipelineLine(pres.pipelineStatus)?.let { header.add(it) }

        // 5. Merge-readiness line (two-tone + contextual Merge link) + the "Approved by: …" line below.
        buildMergeLine(mr, pres.merge)?.let { header.add(it) }
        header.add(buildApprovalsLine())

        return header
    }

    /**
     * The top-right edit affordance of the Info panel (iter3 G20): a single pencil that opens the unified
     * [EditMrDialog] (title, description, reviewers, assignee). Every other MR action now lives on the
     * vertical toolbar, so the header carries no action-link row anymore.
     */
    private fun buildEditCorner(mr: GitLabMergeRequest): JComponent {
        val button = JButton(AllIcons.Actions.Edit).apply {
            toolTipText = CockpitBundle.message("detail.editTooltip")
            addActionListener { onEditMr(mr) }
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 4)
            add(button, BorderLayout.NORTH)
        }
    }

    /**
     * The people row (GLC-37): one circular [HEADER_AVATAR_SIZE]px avatar per user — deduplicated, so a
     * user who is both, say, author and reviewer appears once — with their combined roles in the
     * tooltip («Name (Author, Reviewer)»). The author comes first, then the rest ordered by their first
     * role (see [mrParticipants]); no text, so the participants read at a glance.
     */
    private fun buildAvatarsRow(mr: GitLabMergeRequest): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AVATAR_ROW_GAP), 0)).apply { isOpaque = false }
        for (participant in mrParticipants(mr.author, mr.assignees, mr.reviewers)) {
            row.add(headerAvatarLabel(participant.user, participantTooltip(participant)))
        }
        return row
    }

    /** «Name (Author, Reviewer)» — the display name and the user's combined roles for the avatar tooltip. */
    private fun participantTooltip(participant: MrParticipant): String {
        val roles = participant.roles.joinToString(", ") { roleLabel(it) }
        return CockpitBundle.message("detail.people.tooltip", displayName(participant.user), roles)
    }

    /** The localized label for one [MrRole]. */
    private fun roleLabel(role: MrRole): String = when (role) {
        MrRole.AUTHOR -> CockpitBundle.message("detail.role.author")
        MrRole.ASSIGNEE -> CockpitBundle.message("detail.role.assignee")
        MrRole.REVIEWER -> CockpitBundle.message("detail.role.reviewer")
    }

    /**
     * A [HEADER_AVATAR_SIZE]px circular avatar for [user] with [tooltip] as its tooltip. Shows
     * [AvatarCache]'s placeholder immediately and swaps in the real image (repainting the header) once
     * the background load lands.
     */
    private fun headerAvatarLabel(user: GitLabUser, tooltip: String): JBLabel {
        val label = JBLabel().apply { toolTipText = tooltip }
        label.icon = avatarCache.icon(user, HEADER_AVATAR_SIZE) {
            label.icon = avatarCache.icon(user, HEADER_AVATAR_SIZE) {}
            headerContainer.repaint()
        }
        return label
    }

    /**
     * The muted `!iid · created <relative> · by <author>` meta line, with a `· merged/closed
     * <relative>` suffix once the MR is (GLC-36: back to a single label — the author avatar moved to
     * the people row above the title).
     */
    private fun buildMetaLine(pres: MrHeaderPresentation): JComponent {
        val line = flowLine()
        val parts = buildList {
            add(pres.reference)
            pres.createdRelative?.let { add(CockpitBundle.message("detail.meta.created", it)) }
            add(CockpitBundle.message("detail.meta.by", pres.authorName))
            pres.closingRelative?.let { relative ->
                val key = if (pres.closing == Closing.MERGED) "detail.meta.merged" else "detail.meta.closed"
                add(CockpitBundle.message(key, relative))
            }
        }
        line.add(JBLabel(parts.joinToString(DATE_SEPARATOR)).apply { foreground = CockpitTheme.muted() })
        return line
    }

    /**
     * The head pipeline line — `Pipeline status:` followed by the status (colored via
     * [CockpitTheme.statusColor] with its [CockpitIcons] icon), clickable to drill into the pipelines
     * card. Null when [status] is null (the MR has no head pipeline), so no empty row is added.
     */
    private fun buildPipelineLine(status: String?): JComponent? {
        pipelineStatusLabel = null
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
                showPipelinesCard()
            }
        })
        pipelineStatusLabel = statusLabel
        line.add(statusLabel)
        return line
    }

    /**
     * EDT. Updates the Info header's "Pipeline status" value to [status] (GLC-43 B) — text, status icon
     * and color — when the pipelines card's live poll reports the head pipeline's aggregate status
     * changed. A no-op when the MR has no head-pipeline line ([pipelineStatusLabel] null).
     */
    private fun updatePipelineStatusLine(status: String) {
        val label = pipelineStatusLabel ?: return
        label.text = pipelineStatusText(status)
        label.icon = CockpitIcons.status(status)
        label.foreground = CockpitTheme.statusColor(status)
    }

    /**
     * The merge-readiness line (iter3 G20/G21). Two-tone: "Ready to merge" in [CockpitTheme.success], or
     * a "Merge blocked:" label in [CockpitTheme.warning] followed by the muted reason. A contextual
     * [ActionLink] closes the line — "Merge" when the MR is mergeable, "Set auto-merge" while the pipeline
     * still runs — in the platform link color; other blockers show no link. Null for a
     * [MergeLineState.HIDDEN] presentation (a non-open MR), so no row is added.
     */
    private fun buildMergeLine(mr: GitLabMergeRequest, merge: MergeLinePresentation): JComponent? {
        if (merge.state == MergeLineState.HIDDEN) return null
        val line = flowLine()
        when (merge.state) {
            MergeLineState.READY ->
                line.add(JBLabel(CockpitBundle.message("detail.merge.ready")).apply { foreground = CockpitTheme.success })
            MergeLineState.BLOCKED -> {
                line.add(
                    JBLabel(CockpitBundle.message("detail.merge.blocked.label")).apply { foreground = CockpitTheme.warning },
                )
                val reason = CockpitBundle.message(merge.reasonKey ?: "merge.status.generic")
                line.add(JBLabel(reason).apply { foreground = CockpitTheme.muted() })
            }
            MergeLineState.HIDDEN -> return null
        }
        buildMergeActionLink(mr)?.let { line.add(it) }
        return line
    }

    /**
     * The contextual Merge action link for the merge-readiness line (iter3 G20). Enabled for a mergeable
     * MR ("Merge") or a "when pipeline succeeds" variant ("Set auto-merge"); null for every other state
     * (already merged/closed, or blocked by conflicts/approvals/…) so no link is shown.
     */
    private fun buildMergeActionLink(mr: GitLabMergeRequest): ActionLink? {
        val ref = MrRef(mr.projectId, mr.iid)
        val state = mergeButtonState(mr.state, mr.detailedMergeStatus)
        val link = when (state.action) {
            MergeAction.MERGE ->
                ActionLink(CockpitBundle.message("detail.merge")) { onMerge(ref, mr, mergeWhenPipelineSucceeds = false) }
            MergeAction.MERGE_WHEN_PIPELINE_SUCCEEDS ->
                ActionLink(CockpitBundle.message("detail.merge.setAutoMerge")) {
                    onMerge(ref, mr, mergeWhenPipelineSucceeds = true)
                }
            MergeAction.DISABLED -> return null
        }
        mergeLink = link
        return link
    }

    /**
     * The "Approved by: …" line (two labels, iter3 G21): a "Approved by:" prefix colored by approval
     * health (green/amber) and a muted detail (the approver names, `(none)`, `(N more required)`) that
     * the async approvals load fills.
     */
    private fun buildApprovalsLine(): JComponent {
        val line = flowLine()
        val prefix = JBLabel(CockpitBundle.message("detail.approvedBy.label"))
        val detail = JBLabel(CockpitBundle.message("detail.approvals.loading")).apply { foreground = CockpitTheme.muted() }
        approvalsPrefixLabel = prefix
        approvalsDetailLabel = detail
        line.add(prefix)
        line.add(detail)
        return line
    }

    /**
     * Fetches [mr]'s project labels in the background (GLC-42) to learn each label's color, caches them
     * per project ([labelColorsByProject]) and re-paints the header's [labelsLine] chips with the right
     * color dots. Guarded by [currentRef] so a slow load from a superseded MR never re-colors the wrong
     * header; a failed fetch leaves the neutral dots in place.
     */
    private fun loadLabelColors(ref: MrRef, mr: GitLabMergeRequest) {
        service.coroutineScope.launch {
            val result = service.getProjectLabels(mr.projectId)
            if (result is GitLabResult.Success) {
                val colors = result.data.associate { it.name to it.color }
                withContext(Dispatchers.EDT) {
                    labelColorsByProject[mr.projectId] = colors
                    if (currentRef != ref) return@withContext
                    val line = labelsLine ?: return@withContext
                    renderLabelChips(line, mr.labels, colors)
                    line.revalidate()
                    line.repaint()
                }
            }
        }
    }

    /** EDT. Rebuilds [line]'s read-only label chips (GLC-42), one per name with its [colors] color dot. */
    private fun renderLabelChips(line: JPanel, names: List<String>, colors: Map<String, String>) {
        line.removeAll()
        for (name in names) line.add(labelChip(name, colors[name]))
    }

    /** A read-only label chip (GLC-42): the label name on a [CockpitTheme.chipBackground] pill with a
     * leading color dot ([colorHex] parsed, or a neutral dot when the color is unknown). */
    private fun labelChip(name: String, colorHex: String?): JComponent =
        LabelChip(name).apply {
            icon = ColorDotIcon(parseLabelColor(colorHex), JBUI.scale(LABEL_DOT_DIAMETER))
            iconTextGap = JBUI.scale(4)
            toolTipText = name
        }

    /** Parses a GitLab `#rrggbb` label color, falling back to the muted foreground for an unknown one. */
    private fun parseLabelColor(colorHex: String?): Color =
        colorHex?.let { runCatching { Color.decode(it.trim()) }.getOrNull() } ?: CockpitTheme.muted()

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
                        approvedByMe = false
                        approvalsPrefixLabel?.foreground = UIUtil.getLabelForeground()
                        approvalsDetailLabel?.text = CockpitBundle.message("detail.approvals.unavailable")
                        mrToolbar?.updateActionsAsync()
                    }
                }
            }
        }
    }

    /** EDT. Fills the two-tone approvals line and re-derives [approvedByMe] for the toolbar toggle. */
    private fun renderApprovals(approvals: GitLabApprovals) {
        val names = approvals.approvedBy.joinToString(", ") { displayName(it.user) }
        val left = approvals.approvalsLeft ?: 0
        val display = buildString {
            append(names.ifEmpty { CockpitBundle.message("detail.approvals.none") })
            if (left > 0) append(" ").append(CockpitBundle.message("detail.approvals.left", left))
        }
        approvalsDetailLabel?.text = display
        approvalsPrefixLabel?.foreground = when (approvalsHealth(approvals)) {
            ApprovalsHealth.SATISFIED -> CockpitTheme.success
            ApprovalsHealth.PENDING -> CockpitTheme.warning
            ApprovalsHealth.UNKNOWN -> UIUtil.getLabelForeground()
        }

        val me = service.currentUser
        approvedByMe = me != null && approvals.approvedBy.any { it.user.id == me.id }
        mrToolbar?.updateActionsAsync()
    }

    /** Approves or revokes in the background, then refreshes approvals and the list on success. */
    private fun onToggleApproval(ref: MrRef, alreadyApproved: Boolean) {
        service.coroutineScope.launch {
            val result = if (alreadyApproved) service.unapprove(ref) else service.approve(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        loadApprovals(ref)
                        onListReloadRequested()
                    }
                    else -> showError("detail.error.approve", result)
                }
            }
        }
    }

    // --- Timeline (Events & Discussions) ------------------------------------------------------

    /** The timeline toolbar (iter3 F13): [filter combo][sort toggle] | [+ create general comment]. */
    private fun buildTimelineToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply { isOpaque = false }
        toolbar.add(timelineFilterCombo)
        toolbar.add(timelineOrderButton)
        toolbar.add(timelineSearchField)
        toolbar.add(
            JSeparator(SwingConstants.VERTICAL).apply { preferredSize = JBUI.size(6, 20) },
        )
        toolbar.add(addCommentButton)
        return toolbar
    }

    /** Syncs the sort toggle's icon and tooltip with the current [timelineAscending] direction. */
    private fun refreshOrderButton() {
        timelineOrderButton.icon = if (timelineAscending) AllIcons.General.ArrowDown else AllIcons.General.ArrowUp
        timelineOrderButton.toolTipText = CockpitBundle.message(
            if (timelineAscending) "detail.timeline.order.oldest" else "detail.timeline.order.newest",
        )
    }

    /**
     * Fetches the MR's discussions and its raw notes (system notes included) in the background and
     * renders them as the timeline (guarded by [currentRef]). The pending draft count is loaded in the
     * same cycle (all three in parallel); a failed notes/drafts fetch is non-fatal — the timeline then
     * simply carries no events / no draft banner, while a failed discussions fetch shows the error.
     */
    private fun loadNotes(ref: MrRef) {
        notesLoadedForRef = ref
        notesEpoch++
        showTimelineMessage(CockpitBundle.message("detail.comment.loading"))
        setTimelineTabTitle(null)
        notesJob?.cancel()
        notesJob = service.coroutineScope.launch {
            val (discussionsResult, notesResult, draftsResult) = coroutineScope {
                val discussions = async { service.getMrDiscussions(ref) }
                val notes = async { service.getTimelineNotes(ref) }
                val drafts = async { service.getDraftNotes(ref) }
                Triple(discussions.await(), notes.await(), drafts.await())
            }
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                renderDraftBanner((draftsResult as? GitLabResult.Success)?.data?.size ?: 0)
                when (discussionsResult) {
                    is GitLabResult.Success -> {
                        loadedTimelineNotes = (notesResult as? GitLabResult.Success)?.data ?: emptyList()
                        renderTimeline(commentThreads(discussionsResult.data))
                    }
                    else -> {
                        notesLoadedForRef = null
                        loadedThreads = emptyList()
                        loadedTimelineNotes = emptyList()
                        showTimelineMessage(CockpitBundle.message("detail.error.notes", describe(discussionsResult)))
                        setTimelineTabTitle(null)
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
     * timeline re-fetches (immediately if it is the visible tab, otherwise lazily on next show).
     */
    private fun onReviewSubmitted() {
        draftBanner.isVisible = false
        val ref = currentRef ?: return
        notesLoadedForRef = null
        if (mainTabbedPane.selectedIndex == TIMELINE_TAB_INDEX) loadNotes(ref)
    }

    /** EDT. Clears the timeline and shows a single muted [text] (loading/error/empty), or nothing if null. */
    private fun showTimelineMessage(text: String?) {
        timelineContainer.removeAll()
        if (text != null) {
            timelineContainer.add(
                JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() },
            )
        }
        timelineContainer.revalidate()
        timelineContainer.repaint()
    }

    /**
     * EDT. Rebuilds the native card stack for the given user [threads] and the already-loaded
     * [loadedTimelineNotes] (its event source), honoring the current [timelineFilter] and
     * [timelineAscending] toggle, and updates the tab counter (total number of human notes). System
     * notes render as compact [buildEventCard] rows; discussion threads render as [buildDiscussionCard]s.
     */
    private fun renderTimeline(threads: List<CommentThread>) {
        loadedThreads = threads
        val noteCount = threads.sumOf { it.notes.size }
        val items = filterTimeline(
            buildTimeline(loadedTimelineNotes, threads, timelineFilter, timelineAscending),
            timelineQuery,
        )
        timelineContainer.removeAll()
        if (items.isEmpty()) {
            timelineContainer.add(
                JBLabel(CockpitBundle.message("detail.comment.empty")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
        } else {
            for (item in items) {
                val card = when (item) {
                    is TimelineItem.EventItem -> buildEventCard(item.note)
                    is TimelineItem.DiscussionItem -> buildDiscussionCard(item.thread)
                }
                timelineContainer.add(card)
            }
        }
        timelineContainer.revalidate()
        timelineContainer.repaint()
        notesScroll.verticalScrollBar.value = 0
        setTimelineTabTitle(noteCount)
    }

    /**
     * A compact event card (iter3 B6): the event's type icon, then the author (bold) and the inlined,
     * one-line markdown body (links preserved via a mini [CockpitHtml] pane), with the muted relative
     * date on the right.
     */
    private fun buildEventCard(note: GitLabNote): JComponent {
        val card = RoundedCardPanel(BorderLayout(JBUI.scale(6), 0))

        val left = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }
        left.add(JBLabel(CockpitIcons.event(eventIconKey(note.body))), BorderLayout.WEST)
        val bodyPane = CockpitHtml.createHtmlPane().apply { border = JBUI.Borders.empty() }
        bodyPane.text = CockpitHtml.wrapHtml(
            "<b>" + CockpitHtml.escapeHtml(displayName(note.author)) + "</b> " + inlineMarkdown(note.body),
        )
        bodyPane.caretPosition = 0
        left.add(bodyPane, BorderLayout.CENTER)

        card.add(left, BorderLayout.CENTER)
        card.add(dateLabel(note.createdAt), BorderLayout.EAST)
        return card
    }

    /**
     * A discussion card (iter3 B7): a header row with the root author (bold), a `Resolved` tag and a
     * `file:line` jump link (when diff-anchored) on the left and the muted date on the right; a per-card
     * [CockpitHtml] body pane with the root markdown and the indented, muted replies; and an actions row
     * of real [ActionLink]s — Reply (opens the composer in reply mode) and Resolve/Unresolve (for
     * resolvable threads). The old `cockpit:reply:` / `cockpit:goto:` HTML pseudo-links are gone; normal
     * links in the body still open in the browser.
     */
    private fun buildDiscussionCard(thread: CommentThread): JComponent {
        val card = RoundedCardPanel(VerticalLayout(JBUI.scale(4)))
        val first = thread.notes.first()

        // Reactions row (GLC-40): chips filled lazily; hidden until the note has awards.
        val reactionsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            isVisible = false
        }

        // Hover actions (GLC-40): 😊 react (always), ✏ edit / 🗑 delete (own note only), 🔗 copy link;
        // hidden until the mouse enters the card.
        val hoverActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            isVisible = false
        }
        val hoverListener = cardHoverListener(card, hoverActions)
        fillCardHoverActions(hoverActions, first) { renderReactionsRow(reactionsRow, first, notesEpoch, hoverListener) }

        // Header: author + tags on the left; hover actions then the date on the right.
        val header = JPanel(BorderLayout()).apply { isOpaque = false }
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        headerLeft.add(JBLabel(displayName(first.author)).apply { font = font.deriveFont(Font.BOLD) })
        if (thread.resolved) {
            headerLeft.add(
                JBLabel("[" + CockpitBundle.message("detail.comment.thread.resolved") + "]").apply {
                    foreground = CockpitTheme.muted()
                },
            )
        }
        threadAnchorLabel(thread)?.let { anchor ->
            headerLeft.add(
                ActionLink("[$anchor]") { gotoDiscussionInChanges(thread.discussionId) },
            )
        }
        val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        headerRight.add(hoverActions)
        headerRight.add(dateLabel(first.createdAt))
        header.add(headerLeft, BorderLayout.WEST)
        header.add(headerRight, BorderLayout.EAST)
        card.add(header)

        // Body: root markdown + indented muted replies, in one CockpitHtml pane (upload images resolved).
        val bodyPane = CockpitHtml.createHtmlPane().apply { border = JBUI.Borders.empty() }
        val ref = currentRef
        val epoch = notesEpoch
        applyMarkdownUploads(
            pane = bodyPane,
            fragment = discussionBodyHtml(thread),
            service = service,
            projectId = ref?.projectId ?: 0L,
            projectWebUrl = currentMr?.let(::projectWebUrlOf),
            isCurrent = { currentRef == ref && notesEpoch == epoch },
        )
        card.add(bodyPane)

        // Reactions row sits between the body and the thread actions (the GitLab web layout).
        card.add(reactionsRow)

        // Actions: Reply · Resolve/Unresolve.
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false }
        actions.add(
            ActionLink(CockpitBundle.message("detail.comment.thread.reply")) {
                openComposer(replyToDiscussionId = thread.discussionId)
            },
        )
        if (thread.notes.any { it.resolvable }) {
            val key = if (thread.resolved) "diff.thread.unresolve" else "diff.thread.resolve"
            actions.add(ActionLink(CockpitBundle.message(key)) { onToggleResolve(thread) })
        }
        card.add(actions)

        // Reveal the hover actions while the mouse is anywhere over the card (children included).
        addHoverListenerRecursively(card, hoverListener)
        // Fill the reactions row from cache or schedule its background load.
        renderReactionsRow(reactionsRow, first, notesEpoch, hoverListener)
        return card
    }

    // --- Card hover actions & emoji reactions (GLC-40) ----------------------------------------

    /**
     * Populates a discussion card's top-right [panel] hover actions for its root [note]: 😊 add a
     * reaction (always), ✏ edit and 🗑 delete (only when the note is the current user's own), and 🔗
     * copy a permalink to the comment. [onReactionChanged] re-renders the card's reaction row after a
     * reaction toggle from the emoji popup.
     */
    private fun fillCardHoverActions(panel: JPanel, note: GitLabDiscussionNote, onReactionChanged: () -> Unit) {
        panel.add(
            cardIconButton(CockpitIcons.addEmoji, CockpitIcons.addEmojiHovered, "detail.comment.action.react") {
                showReactionPopup(panel, note, onReactionChanged)
            },
        )
        val me = service.currentUser
        if (me != null && note.author.id == me.id) {
            panel.add(cardIconButton(AllIcons.Actions.Edit, null, "detail.comment.action.edit") { openEditComposer(note) })
            panel.add(cardIconButton(AllIcons.Actions.GC, null, "detail.comment.action.delete") { onDeleteNote(note) })
        }
        panel.add(cardIconButton(CockpitIcons.copyLink, null, "detail.comment.action.copyLink") { onCopyCommentLink(note) })
    }

    /**
     * A borderless clickable icon "button" (a [JBLabel]) for a card's hover row. When [hoverIcon] is
     * non-null the icon swaps to it while the mouse is over the label (the AddEmoji resting/hover pair).
     */
    private fun cardIconButton(icon: Icon, hoverIcon: Icon?, tooltipKey: String, onClick: () -> Unit): JComponent {
        val label = JBLabel(icon).apply {
            toolTipText = CockpitBundle.message(tooltipKey)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 2)
        }
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
            override fun mouseEntered(e: MouseEvent) { if (hoverIcon != null) label.icon = hoverIcon }
            override fun mouseExited(e: MouseEvent) { if (hoverIcon != null) label.icon = icon }
        })
        return label
    }

    /** A [MouseListener] that shows [hoverActions] while the pointer is anywhere within [card]. */
    private fun cardHoverListener(card: JComponent, hoverActions: JComponent): MouseListener =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hoverActions.isVisible = true
            }

            override fun mouseExited(e: MouseEvent) {
                // Moving onto a child fires exit on the parent; keep it shown while still inside the card.
                val p = SwingUtilities.convertPoint(e.component, e.point, card)
                if (!card.contains(p)) hoverActions.isVisible = false
            }
        }

    /** Adds [listener] to [component] and every descendant so the card-hover fires over any child. */
    private fun addHoverListenerRecursively(component: Component, listener: MouseListener) {
        component.addMouseListener(listener)
        if (component is Container) {
            for (child in component.components) addHoverListenerRecursively(child, listener)
        }
    }

    /**
     * Renders [note]'s reaction chips into [container] from [awardsCache], or schedules a background
     * load (bounded by [awardsSemaphore]) when the cache is missing/stale, repainting the row once it
     * lands. The [epoch] guards against a superseded timeline; [hoverListener] is re-attached to each
     * chip so hovering a chip still reveals the card's hover actions.
     */
    private fun renderReactionsRow(
        container: JPanel,
        note: GitLabDiscussionNote,
        epoch: Int,
        hoverListener: MouseListener,
    ) {
        val ref = currentRef ?: return
        val cached = awardsCache[note.id]
        if (cached != null && cached.updatedAt == note.updatedAt) {
            populateReactionChips(container, note, cached.awards, hoverListener)
            return
        }
        container.removeAll()
        container.isVisible = false
        container.revalidate()
        container.repaint()
        service.coroutineScope.launch {
            awardsSemaphore.withPermit {
                if (currentRef != ref || notesEpoch != epoch) return@withPermit
                val result = service.getNoteAwards(ref, note.id)
                if (result is GitLabResult.Success) {
                    withContext(Dispatchers.EDT) {
                        if (currentRef != ref || notesEpoch != epoch) return@withContext
                        awardsCache[note.id] = CachedAwards(note.updatedAt, result.data)
                        populateReactionChips(container, note, result.data, hoverListener)
                    }
                }
            }
        }
    }

    /** EDT. Rebuilds [container]'s reaction chips (one per emoji name, mine highlighted) for [awards]. */
    private fun populateReactionChips(
        container: JPanel,
        note: GitLabDiscussionNote,
        awards: List<GitLabAward>,
        hoverListener: MouseListener,
    ) {
        container.removeAll()
        val me = service.currentUser
        val refresh = { renderReactionsRow(container, note, notesEpoch, hoverListener) }
        awards.groupBy { it.name }.forEach { (name, list) ->
            val mine = me != null && list.any { it.user.id == me.id }
            val chip = reactionChip(name, list.size, mine) { toggleReaction(note, name, mine, refresh) }
            chip.addMouseListener(hoverListener)
            container.add(chip)
        }
        container.isVisible = awards.isNotEmpty()
        container.revalidate()
        container.repaint()
    }

    /** A rounded `emoji count` reaction chip; [highlighted] when the current user is among the reactors. */
    private fun reactionChip(name: String, count: Int, highlighted: Boolean, onClick: () -> Unit): JComponent {
        val chip = ReactionChip("${AwardEmoji.display(name)} $count", highlighted).apply {
            toolTipText = CockpitBundle.message("detail.comment.reaction.tooltip")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        chip.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
        return chip
    }

    /**
     * Toggles the current user's [name] reaction on [note]: removes it when already mine (looking up my
     * award id in the cache), otherwise adds it. On success the cache entry is dropped and [onChanged]
     * re-renders the row (which re-fetches the fresh awards).
     */
    private fun toggleReaction(note: GitLabDiscussionNote, name: String, mine: Boolean, onChanged: () -> Unit) {
        val ref = currentRef ?: return
        val me = service.currentUser
        val myAwardId = if (mine && me != null) {
            awardsCache[note.id]?.awards?.firstOrNull { it.name == name && it.user.id == me.id }?.id
        } else {
            null
        }
        service.coroutineScope.launch {
            val result: GitLabResult<*> = if (myAwardId != null) {
                service.deleteNoteAward(ref, note.id, myAwardId)
            } else {
                service.addNoteAward(ref, note.id, name)
            }
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        awardsCache.remove(note.id)
                        onChanged()
                    }
                    else -> showError("detail.error.reaction", result)
                }
            }
        }
    }

    /**
     * Shows the standard GitLab quick-reaction popup (👍 👎 😄 🎉 😕 ❤️ 🚀 👀) under [anchor]; picking one
     * toggles that reaction on [note] and [onChanged] refreshes the row.
     */
    private fun showReactionPopup(anchor: JComponent, note: GitLabDiscussionNote, onChanged: () -> Unit) {
        val me = service.currentUser
        val mineNames = awardsCache[note.id]?.awards
            ?.filter { me != null && it.user.id == me.id }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        val row = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), JBUI.scale(2))).apply { isOpaque = false }
        lateinit var popup: JBPopup
        for ((name, emoji) in AwardEmoji.STANDARD) {
            val mine = name in mineNames
            row.add(
                JButton(emoji).apply {
                    toolTipText = name
                    margin = JBUI.insets(2)
                    addActionListener {
                        popup.closeOk(null)
                        toggleReaction(note, name, mine, onChanged)
                    }
                },
            )
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(row, row)
            .setRequestFocus(true)
            .createPopup()
        popup.showUnderneathOf(anchor)
    }

    /** Opens the composer in edit mode (GLC-40) pre-filled with [note]'s body; Submit calls [updateNote]. */
    private fun openEditComposer(note: GitLabDiscussionNote) {
        val ref = currentRef ?: return
        ComposerDialog(
            project,
            CockpitBundle.message("detail.composer.title.edit"),
            onSubmit = { text, _ -> submitEdit(ref, note.id, text) },
            onSaveDraft = {},
            initialText = note.body,
            editMode = true,
        ).show()
    }

    /** Edits a note off the EDT (GLC-40), then reloads the timeline on success. */
    private fun submitEdit(ref: MrRef, noteId: Long, text: String) {
        service.coroutineScope.launch {
            val result = service.updateNote(ref, noteId, text)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadNotes(ref)
                    else -> showError("detail.error.comment", result)
                }
            }
        }
    }

    /** Confirms, then deletes [note] off the EDT (GLC-40) and reloads the timeline on success. */
    private fun onDeleteNote(note: GitLabDiscussionNote) {
        val ref = currentRef ?: return
        val confirmed = Messages.showYesNoDialog(
            this,
            CockpitBundle.message("detail.comment.delete.confirm"),
            CockpitBundle.message("detail.comment.delete.title"),
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return
        service.coroutineScope.launch {
            val result = service.deleteNote(ref, note.id)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadNotes(ref)
                    else -> showError("detail.error.deleteComment", result)
                }
            }
        }
    }

    /** Copies `<mr.webUrl>#note_<noteId>` to the clipboard (GLC-40) and confirms with a balloon. */
    private fun onCopyCommentLink(note: GitLabDiscussionNote) {
        val mr = currentMr ?: return
        CopyPasteManager.getInstance().setContents(StringSelection("${mr.webUrl}#note_${note.id}"))
        NotificationGroupManager.getInstance()
            .getNotificationGroup(COCKPIT_NOTIFICATION_GROUP)
            .createNotification(
                CockpitBundle.message("detail.comment.copyLink.done"),
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    /** Builds the root-markdown-plus-indented-replies HTML fragment for a discussion card body. */
    private fun discussionBodyHtml(thread: CommentThread): String {
        val notes = thread.notes
        val metaColor = ColorUtil.toHtmlColor(CockpitTheme.muted())
        return buildString {
            append(CockpitHtml.stripBody(MarkdownRenderer.toHtml(notes.first().body)))
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
        }
    }

    /** A right-aligned, muted relative-date label for a card header. */
    private fun dateLabel(createdAt: String): JComponent =
        JBLabel(formatRelative(createdAt)).apply {
            foreground = CockpitTheme.muted()
            verticalAlignment = SwingConstants.TOP
        }

    /**
     * Renders a system note [body] as inline HTML: a single-paragraph markdown result is unwrapped
     * from its `<p>…</p>` so the event stays on one line; multi-paragraph bodies (rare) keep their
     * block markup. Preserves the markdown's links and emphasis (commit links, `@mentions`, …).
     */
    private fun inlineMarkdown(body: String): String {
        val html = CockpitHtml.stripBody(MarkdownRenderer.toHtml(body)).trim()
        return if (html.startsWith("<p>") && html.endsWith("</p>") && html.indexOf("<p>", 1) == -1) {
            html.removePrefix("<p>").removeSuffix("</p>")
        } else {
            html
        }
    }

    /** Toggles a timeline thread's resolution off the EDT, then reloads the timeline on success. */
    private fun onToggleResolve(thread: CommentThread) {
        val ref = currentRef ?: return
        service.coroutineScope.launch {
            val result = service.setDiscussionResolved(ref, thread.discussionId, !thread.resolved)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadNotes(ref)
                    else -> showError("detail.error.comment", result)
                }
            }
        }
    }

    /**
     * Jumps from a timeline thread anchor to that thread inside the diff: the changes tree is always
     * visible, so it just asks it to reveal the discussion — select its file, open the diff and scroll
     * to the thread. An unknown or non-positioned id is a silent no-op.
     */
    private fun gotoDiscussionInChanges(discussionId: String) {
        changesPanel.revealDiscussion(discussionId)
    }

    // --- Composer popup (iter3 F) -------------------------------------------------------------

    /**
     * Opens the non-modal [ComposerDialog] popup. With [replyToDiscussionId] null it composes a new
     * general comment; otherwise it replies to that thread (its title names the thread's author). The
     * three exits map to the documented semantics — Submit publishes directly, Submit with "Start
     * review" or Save Draft creates a draft note — via [submitComposer] / [saveDraftComposer].
     */
    private fun openComposer(replyToDiscussionId: String?) {
        val ref = currentRef ?: return
        val dialogTitle = if (replyToDiscussionId != null) {
            val author = loadedThreads.firstOrNull { it.discussionId == replyToDiscussionId }
                ?.notes?.firstOrNull()?.author?.let(::displayName).orEmpty()
            CockpitBundle.message("detail.composer.title.reply", author)
        } else {
            CockpitBundle.message("detail.composer.title.general")
        }
        ComposerDialog(
            project,
            dialogTitle,
            onSubmit = { text, startReview -> submitComposer(ref, replyToDiscussionId, text, startReview) },
            onSaveDraft = { text -> saveDraftComposer(ref, replyToDiscussionId, text) },
        ).show()
    }

    /**
     * Submit path of the composer. Without "Start review": publishes directly — a reply to the thread in
     * reply mode ([replyToDiscussion]), otherwise a new general note ([addNote]). With "Start review":
     * creates a draft note that begins the review ([createDraftNote]) — in reply mode the draft is
     * threaded into the discussion via [discussionId], otherwise it is a general draft. On success the
     * timeline reloads.
     */
    private fun submitComposer(ref: MrRef, discussionId: String?, text: String, startReview: Boolean) {
        service.coroutineScope.launch {
            val result: GitLabResult<*> = when {
                startReview -> service.createDraftNote(ref, text, inReplyToDiscussionId = discussionId)
                discussionId != null -> service.replyToDiscussion(ref, discussionId, text)
                else -> service.addNote(ref, text)
            }
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadNotes(ref)
                    else -> showError("detail.error.comment", result)
                }
            }
        }
    }

    /**
     * Save-Draft path of the composer: creates a draft note ([createDraftNote]). In reply mode the draft
     * is threaded into the discussion via [discussionId] (`in_reply_to_discussion_id`); otherwise it is a
     * general draft. On success the timeline reloads (the pending-drafts banner reflects it).
     */
    private fun saveDraftComposer(ref: MrRef, discussionId: String?, text: String) {
        service.coroutineScope.launch {
            val result = service.createDraftNote(ref, text, inReplyToDiscussionId = discussionId)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadNotes(ref)
                    else -> showError("detail.error.comment", result)
                }
            }
        }
    }

    /**
     * Sets the timeline tab's title (on its plain-label tab component) with the human-note count. A null
     * (loading/error) or zero count shows the plain "Events & Discussions" title — the `(0)` suffix is
     * suppressed so an MR with no comments does not read as "zero".
     */
    private fun setTimelineTabTitle(count: Int?) {
        timelineTabLabel.text = if (count == null || count == 0) {
            CockpitBundle.message("detail.tab.timeline")
        } else {
            CockpitBundle.message("detail.tab.timelineCount", count)
        }
    }

    // --- Edit actions -------------------------------------------------------------------------

    /**
     * Opens the unified Edit dialog (iter3 G20): title + description + reviewers/assignee rows. The
     * reviewer/assignee rows reuse the existing pickers ([EditReviewersDialog] / [EditAssigneeDialog])
     * as a sub-step; OK applies the staged title, description, reviewers and assignee in one update.
     */
    private fun onEditMr(mr: GitLabMergeRequest) {
        val dialog = EditMrDialog(
            project,
            mr,
            pickReviewers = { current, onPicked ->
                withMembers(mr.projectId) { members ->
                    val picker = EditReviewersDialog(project, members, current.map { it.id }.toSet())
                    if (picker.showAndGet()) {
                        val ids = picker.selectedIds().toSet()
                        onPicked(members.filter { it.id in ids })
                    }
                }
            },
            pickAssignee = { current, onPicked ->
                withMembers(mr.projectId) { members ->
                    val picker = EditAssigneeDialog(project, members, current?.id)
                    if (picker.showAndGet()) {
                        val id = picker.selectedIds().firstOrNull()
                        onPicked(members.firstOrNull { it.id == id })
                    }
                }
            },
            pickLabels = { current, onPicked ->
                withLabels(mr.projectId) { projectLabels ->
                    // Keep any current label absent from the project roster so it is not silently dropped.
                    val known = projectLabels.mapTo(HashSet()) { it.name }
                    val roster = projectLabels + current.filterNot { it in known }
                        .map { GitLabLabel(it, FALLBACK_LABEL_COLOR) }
                    val picker = EditLabelsDialog(project, roster, current.toSet())
                    if (picker.showAndGet()) onPicked(picker.selectedNames())
                }
            },
        )
        if (dialog.showAndGet()) {
            applyUpdate(
                MrRef(mr.projectId, mr.iid),
                MergeRequestUpdate(
                    title = dialog.editedTitle,
                    description = dialog.editedDescription,
                    reviewerIds = dialog.reviewerIds,
                    assigneeIds = dialog.assigneeIds,
                    labels = dialog.labelsCsvOrNull,
                ),
            )
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

    /**
     * Loads [projectId]'s labels off the EDT (with a wait cursor), then runs [onLoaded] on the EDT — the
     * label-picker analogue of [withMembers] (GLC-42). The MR's own project id is used so, in the "All
     * projects" mode, the picker lists the labels of the MR's project rather than the git-resolved one.
     */
    private fun withLabels(projectId: Long, onLoaded: (List<GitLabLabel>) -> Unit) {
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        service.coroutineScope.launch {
            val result = service.getProjectLabels(projectId)
            withContext(Dispatchers.EDT) {
                cursor = Cursor.getDefaultCursor()
                when (result) {
                    is GitLabResult.Success -> onLoaded(result.data)
                    else -> showError("detail.error.labels", result)
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

    /**
     * The unified Edit Merge Request dialog (iter3 G20, Kotlin UI DSL v2). Title and description are
     * edited inline; the Assignees, Reviewers and Labels rows show the current values and an "Edit…"
     * button that opens the matching picker as a sub-step (via the injected [pickReviewers] /
     * [pickAssignee] / [pickLabels]), staging the picks into [reviewerIds] / [assigneeIds] /
     * [labelsCsvOrNull]. A "Mark as draft" checkbox (GLC-42) toggles the MR's draft state through its
     * title's `Draft: ` prefix — GitLab's REST update endpoint has no direct draft/WIP input, so
     * [editedTitle] normalizes the title to the checkbox via [applyDraftState]. The title field shows
     * the *stripped* title (without any draft marker) so the checkbox is its single source of truth. OK
     * returns the staged fields to the caller.
     */
    private class EditMrDialog(
        project: Project,
        mr: GitLabMergeRequest,
        private val pickReviewers: (List<GitLabUser>, (List<GitLabUser>) -> Unit) -> Unit,
        private val pickAssignee: (GitLabUser?, (GitLabUser?) -> Unit) -> Unit,
        private val pickLabels: (List<String>, (List<String>) -> Unit) -> Unit,
    ) : DialogWrapper(project) {

        private val titleField = JBTextField(stripDraftPrefix(mr.title), 40)
        private val descriptionArea = JBTextArea(mr.description.orEmpty(), 12, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        private var stagedReviewers: List<GitLabUser> = mr.reviewers
        private var stagedAssignee: GitLabUser? = mr.assignees.firstOrNull()
        private var stagedLabels: List<String> = mr.labels

        private val reviewersLabel = JBLabel(peopleText(stagedReviewers))
        private val assigneeLabel = JBLabel(personText(stagedAssignee))
        private val labelsValueLabel = JBLabel(labelsText(stagedLabels))
        private val draftCheck = JBCheckBox(CockpitBundle.message("dialog.editMr.draftLabel"), mr.draft)

        /** The MR's original labels, for change detection: labels are only sent when this set changes. */
        private val originalLabels: Set<String> = mr.labels.toSet()

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
            row(CockpitBundle.message("dialog.editMr.assigneeLabel")) {
                cell(assigneeLabel).align(AlignX.FILL)
                button(CockpitBundle.message("dialog.editMr.editButton")) {
                    pickAssignee(stagedAssignee) { picked ->
                        stagedAssignee = picked
                        assigneeLabel.text = personText(picked)
                    }
                }
            }
            row(CockpitBundle.message("dialog.editMr.reviewersLabel")) {
                cell(reviewersLabel).align(AlignX.FILL)
                button(CockpitBundle.message("dialog.editMr.editButton")) {
                    pickReviewers(stagedReviewers) { picked ->
                        stagedReviewers = picked
                        reviewersLabel.text = peopleText(picked)
                    }
                }
            }
            row(CockpitBundle.message("dialog.editMr.labelsLabel")) {
                cell(labelsValueLabel).align(AlignX.FILL)
                button(CockpitBundle.message("dialog.editMr.editButton")) {
                    pickLabels(stagedLabels) { picked ->
                        stagedLabels = picked
                        labelsValueLabel.text = labelsText(picked)
                    }
                }
            }
            row { cell(draftCheck) }
        }

        override fun getPreferredFocusedComponent(): JComponent = titleField

        /** The title normalized to the draft checkbox (adds/removes the `Draft: ` prefix, GLC-42). */
        val editedTitle: String get() = applyDraftState(titleField.text.trim(), draftCheck.isSelected)
        val editedDescription: String get() = descriptionArea.text
        val reviewerIds: List<Long> get() = stagedReviewers.map { it.id }
        val assigneeIds: List<Long> get() = stagedAssignee?.let { listOf(it.id) } ?: emptyList()

        /**
         * The chosen labels as a CSV of names (GLC-42), or null when the selection is unchanged so the
         * update omits the `labels` key. An empty string is meaningful — it clears every label.
         */
        val labelsCsvOrNull: String?
            get() = if (stagedLabels.toSet() == originalLabels) null else stagedLabels.joinToString(",")

        private fun peopleText(users: List<GitLabUser>): String =
            users.takeIf { it.isNotEmpty() }?.joinToString(", ") { displayName(it) }
                ?: CockpitBundle.message("detail.none")

        private fun personText(user: GitLabUser?): String =
            user?.let { displayName(it) } ?: CockpitBundle.message("detail.none")

        private fun labelsText(names: List<String>): String =
            names.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: CockpitBundle.message("detail.none")
    }

    /**
     * Reviewer picker with an incremental search field over a [CheckBoxList]. The selection state
     * lives in a pure [dev.jota.gitlabcockpit.core.ReviewerSelectionModel], so a member checked while
     * unfiltered stays checked even after a search hides it — the dialog is glue that repopulates the
     * visible rows on each keystroke and forwards toggles to the model. [selectedIds] just reads the
     * model.
     */
    private class EditReviewersDialog(
        project: Project,
        members: List<GitLabUser>,
        currentReviewerIds: Set<Long>,
    ) : DialogWrapper(project) {

        private val model = dev.jota.gitlabcockpit.core.ReviewerSelectionModel(members, currentReviewerIds)
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
     * Label picker (GLC-42): the reviewer-picker pattern over labels — an incremental [SearchTextField]
     * above a [CheckBoxList] of the project's labels, with the MR's current labels pre-checked. The
     * selection lives in a pure [LabelSelectionModel] keyed by label name, so a label checked while
     * unfiltered stays checked even after a search hides it; the dialog is glue that repopulates the
     * visible rows on each keystroke and forwards toggles to the model. [selectedNames] just reads the
     * model.
     */
    private class EditLabelsDialog(
        project: Project,
        labels: List<GitLabLabel>,
        currentLabelNames: Set<String>,
    ) : DialogWrapper(project) {

        private val model = LabelSelectionModel(labels, currentLabelNames)
        private val searchField = SearchTextField()
        private val checkList = CheckBoxList<GitLabLabel>()

        init {
            title = CockpitBundle.message("dialog.editLabels.title")
            checkList.setCheckBoxListListener { index, value ->
                checkList.getItemAt(index)?.let { model.setChecked(it.name, value) }
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
            model.visibleItems(searchField.text).forEach { label ->
                checkList.addItem(label, label.name, model.isChecked(label.name))
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

        fun selectedNames(): List<String> = model.selectedNames()
    }

    /**
     * Assignee picker: an incremental search field over a single-selection [JBList] whose first row
     * is always the fixed "None" option (null), kept visible regardless of the filter. Typing filters
     * the members below it via [filterMembers]; the current assignee is preselected, a double click
     * confirms, and [selectedIds] returns the picked user's id (empty for "None").
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

    /** Confirmation for closing an MR: a single "Close merge request !iid?" line (Kotlin UI DSL v2). */
    private class ConfirmCloseDialog(project: Project, private val iid: Long) : DialogWrapper(project) {

        init {
            title = CockpitBundle.message("dialog.closeMr.title")
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row { label(CockpitBundle.message("dialog.closeMr.confirm", iid)) }
        }
    }

    /**
     * The non-modal composer popup (iter3 F14). A markdown-format toolbar wraps the textarea selection
     * (via [wrapMarkdown]); a "Start review" checkbox flips Submit's meaning. The three exits are:
     *
     * - **Submit** (no "Start review"): publishes the note/reply directly (the current flow).
     * - **Submit** with "Start review": creates a draft note that begins the review.
     * - **Save Draft**: always creates a draft note.
     * - **Cancel** / Esc: closes without sending.
     *
     * The dialog itself is pure UI: [onSubmit] / [onSaveDraft] carry the text (and the "Start review"
     * flag) to the panel, which performs the network call. The textarea takes focus on open.
     */
    private class ComposerDialog(
        project: Project,
        dialogTitle: String,
        private val onSubmit: (text: String, startReview: Boolean) -> Unit,
        private val onSaveDraft: (text: String) -> Unit,
        initialText: String = "",
        private val editMode: Boolean = false,
    ) : DialogWrapper(project) {

        private val area = JBTextArea(initialText, COMPOSER_ROWS, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        private val startReviewCheck = JBCheckBox(CockpitBundle.message("detail.composer.startReview"))

        init {
            title = dialogTitle
            isModal = false
            init()
            setOKButtonText(CockpitBundle.message("detail.composer.submit"))
        }

        override fun createCenterPanel(): JComponent = panel {
            row { cell(buildFormatToolbar()) }
            row {
                cell(JBScrollPane(area)).align(Align.FILL)
            }.resizableRow()
            // Edit mode publishes the change directly — there is no draft/"start review" flow.
            if (!editMode) row { cell(startReviewCheck) }
        }.apply { preferredSize = CockpitTheme.EDIT_MR_DIALOG_SIZE }

        /** The markdown-format toolbar: B / I / S / inline code / code block / quote / link. */
        private fun buildFormatToolbar(): JComponent {
            val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply { isOpaque = false }
            bar.add(formatButton("B", "detail.composer.format.bold", MarkdownMarker.BOLD, Font.BOLD))
            bar.add(formatButton("I", "detail.composer.format.italic", MarkdownMarker.ITALIC, Font.ITALIC))
            bar.add(formatButton("S", "detail.composer.format.strike", MarkdownMarker.STRIKE, Font.PLAIN))
            bar.add(formatButton("</>", "detail.composer.format.code", MarkdownMarker.CODE, Font.PLAIN))
            bar.add(formatButton("{ }", "detail.composer.format.codeBlock", MarkdownMarker.CODE_BLOCK, Font.PLAIN))
            bar.add(formatButton(">", "detail.composer.format.quote", MarkdownMarker.QUOTE, Font.PLAIN))
            val linkButton = JButton(CockpitIcons.copyLink).apply {
                toolTipText = CockpitBundle.message("detail.composer.format.link")
                addActionListener { applyFormat(MarkdownMarker.LINK) }
            }
            bar.add(linkButton)
            return bar
        }

        private fun formatButton(text: String, tooltipKey: String, marker: MarkdownMarker, style: Int): JButton =
            JButton(text).apply {
                toolTipText = CockpitBundle.message(tooltipKey)
                if (style != Font.PLAIN) font = font.deriveFont(style)
                margin = JBUI.emptyInsets()
                addActionListener { applyFormat(marker) }
            }

        /** Applies [marker] to the current selection and restores the caret/selection the wrap yields. */
        private fun applyFormat(marker: MarkdownMarker) {
            val result = wrapMarkdown(area.text, area.selectionStart, area.selectionEnd, marker)
            area.text = result.text
            area.select(result.selectionStart, result.selectionEnd)
            area.requestFocusInWindow()
        }

        override fun getPreferredFocusedComponent(): JComponent = area

        // Edit mode drops the Save Draft exit (a note edit has no draft form).
        override fun createActions(): Array<Action> =
            if (editMode) arrayOf(okAction, cancelAction) else arrayOf(okAction, saveDraftAction, cancelAction)

        /** Save Draft: creates a draft note regardless of the "Start review" checkbox. */
        private val saveDraftAction: Action = object : DialogWrapperAction(
            CockpitBundle.message("detail.composer.saveDraft"),
        ) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                val text = area.text.trim()
                if (text.isEmpty()) return
                onSaveDraft(text)
                close(OK_EXIT_CODE)
            }
        }

        override fun doOKAction() {
            val text = area.text.trim()
            if (text.isEmpty()) return
            onSubmit(text, !editMode && startReviewCheck.isSelected)
            super.doOKAction()
        }
    }

    /**
     * A rounded, subtly-shaded timeline card (iter3 B5). Paints a [CockpitTheme.cardBackground] fill and
     * a 1px [JBColor.border] outline with a [CARD_ARC] corner radius — the [DiffThreadPanel] visual
     * pattern, kept independent of it — then lets its children paint on top over the 8px padding.
     */
    private class RoundedCardPanel(layout: java.awt.LayoutManager) : JPanel(layout) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(8)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(CARD_ARC).toFloat()
                val rect = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
                g2.color = CockpitTheme.cardBackground()
                g2.fill(rect)
                g2.color = JBColor.border()
                g2.draw(rect)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /**
     * A read-only label pill (GLC-42): a rounded [CockpitTheme.chipBackground] chip with a
     * [JBColor.border] outline — the [ReactionChip] visual pattern without the highlight state — that
     * carries a leading color-dot icon and the label name.
     */
    private class LabelChip(text: String) : JBLabel(text) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(1, 6)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(REACTION_CHIP_ARC).toFloat()
                val rect = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
                g2.color = CockpitTheme.chipBackground()
                g2.fill(rect)
                g2.color = JBColor.border()
                g2.draw(rect)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /** A small filled circle in the label's own color, painted as the [LabelChip]'s leading icon (GLC-42). */
    private class ColorDotIcon(private val color: Color, private val diameter: Int) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x, y, diameter, diameter)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = diameter
        override fun getIconHeight(): Int = diameter
    }

    /** Cached emoji reactions of a note (GLC-40), keyed alongside the note's `updated_at` for freshness. */
    private data class CachedAwards(val updatedAt: String?, val awards: List<GitLabAward>)

    /**
     * A small rounded reaction chip (GLC-40): an `emoji count` pill on a [CockpitTheme.chipBackground]
     * fill with a [JBColor.border] outline, painted like [RoundedCardPanel] but at chip scale. When
     * [highlighted] (the current user is among the reactors) it takes a translucent [CockpitTheme.info]
     * fill and an info-colored outline so a reaction of the user's own reads at a glance.
     */
    private class ReactionChip(text: String, private val highlighted: Boolean) : JBLabel(text) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(1, 6)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(REACTION_CHIP_ARC).toFloat()
                val rect = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
                g2.color = if (highlighted) ColorUtil.toAlpha(CockpitTheme.info, 40) else CockpitTheme.chipBackground()
                g2.fill(rect)
                g2.color = if (highlighted) CockpitTheme.info else JBColor.border()
                g2.draw(rect)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    companion object {
        /** Index of the Events & Discussions tab inside the "main" card's small tabbed pane (Info = 0). */
        private const val TIMELINE_TAB_INDEX = 1

        /** Persisted proportion key for the MR tab's horizontal (changes | cards) splitter (GLC-37). */
        private const val MRTAB_SPLITTER_KEY = "dev.jota.gitlabcockpit.mrtab.splitter"

        /** [com.intellij.openapi.actionSystem.ActionPlaces]-style id for the MR tab's vertical toolbar. */
        private const val MR_TOOLBAR_PLACE = "GitLabCockpitMrToolbar"

        /** CardLayout name of the "main" card (Info | Events & Discussions). */
        private const val CARD_MAIN = "main"

        /** CardLayout name of the "pipelines" drill-in card. */
        private const val CARD_PIPELINES = "pipelines"

        /** Diameter (px) of the header people-row avatars. */
        private const val HEADER_AVATAR_SIZE = 18

        /** Gap (unscaled px) between adjacent avatars in the header people row. */
        private const val AVATAR_ROW_GAP = 4

        /** Vertical gap (unscaled px) between two native timeline cards (iter3 B). */
        private const val TIMELINE_CARD_GAP = 8

        /** Corner radius (unscaled px) of a timeline card's rounded border/background (iter3 B5). */
        private const val CARD_ARC = 8

        /** Corner radius (unscaled px) of an emoji reaction chip (GLC-40). */
        private const val REACTION_CHIP_ARC = 10

        /** Diameter (unscaled px) of a read-only label chip's leading color dot (GLC-42). */
        private const val LABEL_DOT_DIAMETER = 8

        /**
         * Fallback color for a picker-roster label that is not among the project's returned labels
         * (GLC-42) — e.g. a current MR label the labels endpoint did not include. The picker shows names
         * only, so this color is never actually painted; it just satisfies the [GitLabLabel] shape.
         */
        private const val FALLBACK_LABEL_COLOR = "#808080"

        /** Rows of the composer popup's textarea (iter3 F14). */
        private const val COMPOSER_ROWS = 8

        /** Separator between the Overview date parts (a spaced middle dot U+00B7). */
        private const val DATE_SEPARATOR = " · "

        /** Bundle label for a [TimelineFilter] combo entry. */
        private fun timelineFilterLabel(filter: TimelineFilter): String = when (filter) {
            TimelineFilter.ALL -> CockpitBundle.message("detail.timeline.filter.all")
            TimelineFilter.EVENTS -> CockpitBundle.message("detail.timeline.filter.events")
            TimelineFilter.DISCUSSIONS -> CockpitBundle.message("detail.timeline.filter.discussions")
        }

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
