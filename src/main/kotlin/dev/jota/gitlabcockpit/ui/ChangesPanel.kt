package dev.jota.gitlabcockpit.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.chains.SimpleDiffRequestProducer
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.DiffRefs
import dev.jota.gitlabcockpit.api.GitLabDiffFile
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDraftNote
import dev.jota.gitlabcockpit.api.GitLabMrVersion
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.core.COCKPIT_NOTIFICATION_GROUP
import dev.jota.gitlabcockpit.core.ChangeType
import dev.jota.gitlabcockpit.core.ChangesView
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.DiffLineMap
import dev.jota.gitlabcockpit.core.FileNode
import dev.jota.gitlabcockpit.core.LinePosition
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.ReviewedFiles
import dev.jota.gitlabcockpit.core.ThreadSide
import dev.jota.gitlabcockpit.core.buildLineMap
import dev.jota.gitlabcockpit.core.chainIndex
import dev.jota.gitlabcockpit.core.changeTypeOf
import dev.jota.gitlabcockpit.core.changesViews
import dev.jota.gitlabcockpit.core.buildFileTree
import dev.jota.gitlabcockpit.core.discussionsByFile
import dev.jota.gitlabcockpit.core.versionRefs
import dev.jota.gitlabcockpit.ui.diff.CockpitDiffContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The changed-files side of an MR tab (GLC-37). A tree of the MR's changed files (grouped by
 * directory), each file iconed by its [ChangeType] and suffixed with a comment count when it has diff
 * discussions, with an "N of M files reviewed" counter and — when there are unpublished draft notes —
 * the "Pending review" section (Submit review / per-row delete) beneath it. A double-click on a file,
 * or the tab's shared toolbar Open-diff action, opens its base/head diff in the IDE editor
 * ([DiffManager]) without any checkout — the two sides are fetched raw at the MR's `diff_refs`
 * base/head SHAs.
 *
 * This panel is the left side of the MR tab's horizontal splitter; the review discussions live inline
 * in the diff and in the Events & Discussions timeline (there is no per-file comment pane here). The
 * file tree's toolbar actions are exposed via [treeActions] so the MR tab folds them into its single
 * vertical toolbar.
 *
 * Diffs and discussions load lazily the first time the tab binds an MR ([onTabSelected]) and are
 * re-fetched after every detail refresh ([setMr]). Every network call runs on the service's coroutine
 * scope (never the EDT); results are marshaled with [Dispatchers.EDT] and dropped when stale
 * (re-checking [currentRef]).
 *
 * An "All changes ▾" selector above the tree (GLC-41) switches the tree/diff between the MR's full diff
 * (the default, its current head) and any past diff version — opening that version's files at its own
 * base/head SHAs through the same raw-file flow. The reviewed state and its counter apply only to
 * "All changes"; they are keyed to the current head, so a concrete version is a read-only view. The
 * selector resets to "All changes" on every (re)load.
 *
 * @param onFileCountChanged reports the loaded file count (or null while unknown).
 * @param onReviewSubmitted called after a successful "Submit review" (bulk publish) so the parent can
 * refresh its Events & Discussions timeline (published drafts become regular notes; the draft banner
 * clears).
 */
class ChangesPanel(
    private val project: Project,
    private val service: CockpitProjectService,
    private val onFileCountChanged: (Int?) -> Unit,
    private val onReviewSubmitted: () -> Unit,
) : JPanel(BorderLayout()) {

    /** Ref of the MR currently displayed; null when cleared. */
    var currentRef: MrRef? = null
        private set

    /** The MR's diff SHAs; needed to open a diff. Null until the detail is bound (or if absent). */
    private var diffRefs: DiffRefs? = null

    /** The MR's project base web URL, used to absolutize relative `/uploads/…` attachment links. */
    private var projectWebUrl: String? = null

    /** The ref whose changes have been loaded, so the tab only reloads when it changes. */
    private var loadedForRef: MrRef? = null

    /** Diff discussions grouped by file path; read by the tree renderer (badges) and the diff context. */
    private var discussionsByFilePath: Map<String, List<GitLabDiscussion>> = emptyMap()

    /** The changed file currently selected in the tree; enables the Open-diff / Toggle-reviewed actions. */
    private var selectedFile: GitLabDiffFile? = null

    /** The MR's changed files (flat), kept so a reveal can map a discussion's path back to its file. */
    private var loadedFiles: List<GitLabDiffFile> = emptyList()

    /** True once a load has populated the discussions/files, so a pending reveal can resolve. */
    private var discussionsLoaded = false

    /** A discussion the timeline asked to reveal; held until the changes finish loading. */
    private var pendingRevealId: String? = null

    /** The MR's pending draft notes, loaded alongside the discussions; drives the "Pending review" section. */
    private var currentDrafts: List<GitLabDraftNote> = emptyList()

    /** The MR's full "All changes" diff set (its current head), kept so a version→All-changes switch is instant. */
    private var allChangesFiles: List<GitLabDiffFile> = emptyList()

    /** The MR's diff versions (newest-first), loaded with the changes; empty until loaded or on error. */
    private var versions: List<GitLabMrVersion> = emptyList()

    /** The version currently shown, or null for "All changes" (the default). Drives [viewRefs] and the counter gate. */
    private var selectedVersion: GitLabMrVersion? = null

    /** Guards [onVersionSelected] against the combo's programmatic (re)build so a rebuild is not read as a pick. */
    private var updatingVersionCombo = false

    private var loadJob: Job? = null
    private var discussionsJob: Job? = null
    private var newThreadJob: Job? = null
    private var draftsJob: Job? = null
    private var publishJob: Job? = null
    private var deleteJob: Job? = null
    private var versionDiffsJob: Job? = null

    /** Per-MR reviewed-file store (GLC-35): drives the tree's muted+tick rendering and the counter. */
    private val reviewedFiles = ReviewedFiles.getInstance(project)

    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = FileTreeRenderer()
    }

    /** Muted "N of M files reviewed" footer under the tree; hidden until a change is loaded. */
    private val reviewedCountLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
    }

    // --- Changes version selector (GLC-41) ----------------------------------------------------

    /** "All changes ▾" selector above the tree; each [ChangesView] renders as its label ([versionOptionLabel]). */
    private val versionCombo = ComboBox<ChangesView>().apply {
        renderer = textCellRenderer<ChangesView>("") { versionOptionLabel(it) }
        addActionListener { onVersionSelected() }
    }

    /** Wrapper for [versionCombo], the first row of the panel; hidden until the MR has ≥1 diff version. */
    private val versionSelectorPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4, 8, 2, 8)
        add(versionCombo, BorderLayout.CENTER)
        isVisible = false
    }

    // --- Pending review (F4b) -----------------------------------------------------------------

    /** One row per draft, rebuilt on every drafts (re)load. */
    private val draftsRowsPanel = JPanel(VerticalLayout(JBUI.scale(2)))
    private val submitButton = JButton().apply { addActionListener { onSubmitReview() } }
    private val refreshButton = JButton(CockpitBundle.message("changes.pending.refresh")).apply {
        addActionListener { currentRef?.let { reloadDrafts(it) } }
    }
    private val pendingReviewPanel = JPanel(BorderLayout())

    init {
        val bottom = JPanel(VerticalLayout(0)).apply { isOpaque = false }
        bottom.add(reviewedCountLabel)
        bottom.add(buildPendingReviewPanel())
        add(versionSelectorPanel, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val fileNode = node?.userObject as? FileNode
            selectedFile = fileNode?.file
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
                val file = (node.userObject as? FileNode)?.file ?: return false
                openDiff(file)
                return true
            }
        }.installOn(tree)

        // Manual reviewed toggle: Space on the selected file, or a right-click menu (GLC-35).
        tree.registerKeyboardAction(
            { toggleReviewedForSelection() },
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
            JComponent.WHEN_FOCUSED,
        )
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                showReviewedContextMenu(comp, x, y)
            }
        })

        clear()
    }

    /**
     * The changed-file tree actions (GLC-37): expand / collapse the tree and — on the current tree
     * selection — open its diff or toggle its reviewed state. This is the iter1 Changes toolbar, now
     * folded into the MR tab's single vertical toolbar (no duplicated logic). Expand / Collapse are
     * enabled whenever an MR is bound; Open diff / Toggle reviewed only while a changed file is
     * selected.
     */
    fun treeActions(): List<AnAction> = listOf(
        changesAction("changes.action.expandAll", AllIcons.Actions.Expandall, needsSelection = false) {
            TreeUtil.expandAll(tree)
        },
        changesAction("changes.action.collapseAll", AllIcons.Actions.Collapseall, needsSelection = false) {
            TreeUtil.collapseAll(tree, 1)
        },
        Separator.getInstance(),
        changesAction("changes.action.openDiff", AllIcons.Actions.Diff, needsSelection = true) {
            selectedFile?.let { openDiff(it) }
        },
        changesAction("changes.action.toggleReviewed", AllIcons.Actions.Checked, needsSelection = true) {
            toggleReviewedForSelection()
        },
    )

    /**
     * Builds one toolbar [AnAction] from a bundle [key], [icon] and [body]. A [needsSelection] action
     * is enabled only while a changed file is selected in the tree (Open diff / Toggle reviewed); the
     * others are enabled whenever an MR is bound.
     */
    private fun changesAction(
        key: String,
        icon: Icon,
        needsSelection: Boolean,
        body: () -> Unit,
    ): AnAction = object : AnAction(CockpitBundle.message(key), null, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = if (needsSelection) selectedFile != null else currentRef != null
        }

        override fun actionPerformed(e: AnActionEvent) = body()
    }

    /** Builds the (initially hidden) "Pending review" section: draft rows + Submit review / Refresh. */
    private fun buildPendingReviewPanel(): JComponent {
        pendingReviewPanel.border = JBUI.Borders.empty(4, 8)
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.add(
            JBLabel(CockpitBundle.message("changes.pending.title")).apply { font = font.deriveFont(Font.BOLD) },
            BorderLayout.WEST,
        )
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        buttons.add(submitButton)
        buttons.add(refreshButton)
        header.add(buttons, BorderLayout.EAST)
        pendingReviewPanel.add(header, BorderLayout.NORTH)
        val scroll = JBScrollPane(draftsRowsPanel).apply { preferredSize = JBUI.size(240, 110) }
        pendingReviewPanel.add(scroll, BorderLayout.CENTER)
        pendingReviewPanel.isVisible = false
        return pendingReviewPanel
    }

    // --- Lifecycle called by MrDetailPanel ----------------------------------------------------

    /** Binds this tab to [ref] / [refs] and marks the changes as needing a (re)load. */
    fun setMr(ref: MrRef, refs: DiffRefs?, projectWebUrl: String?) {
        currentRef = ref
        diffRefs = refs
        this.projectWebUrl = projectWebUrl
        loadedForRef = null
        cancelJobs()
        clearContent()
        onFileCountChanged(null)
        tree.emptyText.text = ""
    }

    /** Resets to the empty placeholder (no MR selected). */
    fun clear() {
        currentRef = null
        diffRefs = null
        projectWebUrl = null
        loadedForRef = null
        cancelJobs()
        clearContent()
        onFileCountChanged(null)
        tree.emptyText.text = ""
    }

    /** Called when the changes side becomes bound; loads the changes the first time per MR. */
    fun onTabSelected() {
        val ref = currentRef ?: return
        if (loadedForRef != ref) load(ref)
    }

    /**
     * Reveals a discussion in the diff, driven by the timeline's "jump to thread" link. When the
     * changes are already loaded it resolves immediately; otherwise the id is held and resolved once
     * the (lazy) load finishes. An unknown or non-positioned id is a silent no-op.
     */
    fun revealDiscussion(discussionId: String) {
        pendingRevealId = discussionId
        if (discussionsLoaded) resolvePendingReveal()
    }

    private fun cancelJobs() {
        loadJob?.cancel()
        discussionsJob?.cancel()
        newThreadJob?.cancel()
        draftsJob?.cancel()
        publishJob?.cancel()
        deleteJob?.cancel()
        versionDiffsJob?.cancel()
    }

    private fun clearContent() {
        rootNode.removeAllChildren()
        treeModel.reload()
        discussionsByFilePath = emptyMap()
        loadedFiles = emptyList()
        allChangesFiles = emptyList()
        discussionsLoaded = false
        pendingRevealId = null
        selectedFile = null
        currentDrafts = emptyList()
        draftsRowsPanel.removeAll()
        pendingReviewPanel.isVisible = false
        reviewedCountLabel.isVisible = false
        reviewedCountLabel.text = ""
        versions = emptyList()
        selectedVersion = null
        rebuildVersionCombo()
    }

    // --- Loading ------------------------------------------------------------------------------

    /** The parallel results of one changes load: diffs, discussions, drafts and diff versions. */
    private class LoadedChanges(
        val diffs: GitLabResult<List<GitLabDiffFile>>,
        val discussions: GitLabResult<List<GitLabDiscussion>>,
        val drafts: GitLabResult<List<GitLabDraftNote>>,
        val versions: GitLabResult<List<GitLabMrVersion>>,
    )

    /** Loads the MR's diffs, discussions, drafts and diff versions in parallel, then renders the tree. */
    private fun load(ref: MrRef) {
        loadedForRef = ref
        clearContent()
        onFileCountChanged(null)
        tree.emptyText.text = CockpitBundle.message("changes.loading")
        loadJob?.cancel()
        loadJob = service.coroutineScope.launch {
            val loaded = coroutineScope {
                val diffs = async { service.getMrDiffs(ref) }
                val discussions = async { service.getMrDiscussions(ref) }
                val drafts = async { service.getDraftNotes(ref) }
                val versions = async { service.getMrVersions(ref) }
                LoadedChanges(diffs.await(), discussions.await(), drafts.await(), versions.await())
            }
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                // Drafts are non-fatal too: an error just hides the "Pending review" section.
                renderDrafts((loaded.drafts as? GitLabResult.Success)?.data ?: emptyList())
                // Versions are non-fatal: an error just hides the selector (All changes stays available).
                renderVersions((loaded.versions as? GitLabResult.Success)?.data ?: emptyList())
                when (val diffsResult = loaded.diffs) {
                    is GitLabResult.Success -> {
                        // Discussions are non-fatal: an error just means "no comments shown".
                        val discussions = (loaded.discussions as? GitLabResult.Success)?.data ?: emptyList()
                        discussionsByFilePath = discussionsByFile(discussions)
                        allChangesFiles = diffsResult.data
                        renderFiles(diffsResult.data)
                        discussionsLoaded = true
                        resolvePendingReveal()
                    }
                    else -> {
                        loadedForRef = null
                        tree.emptyText.text = CockpitBundle.message("changes.error.diffs", describe(diffsResult))
                        onFileCountChanged(null)
                    }
                }
            }
        }
    }

    /** EDT. Builds the file tree from [files] and updates the tab counter. */
    private fun renderFiles(files: List<GitLabDiffFile>) {
        loadedFiles = files
        val root = buildFileTree(files)
        rootNode.removeAllChildren()
        for (child in root.children) rootNode.add(toTreeNode(child))
        treeModel.reload()
        TreeUtil.expandAll(tree)
        tree.emptyText.text = if (files.isEmpty()) CockpitBundle.message("changes.empty") else ""
        selectedFile = null
        onFileCountChanged(files.size)
        updateReviewedCounter()
    }

    private fun toTreeNode(node: FileNode): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(node)
        for (child in node.children) treeNode.add(toTreeNode(child))
        return treeNode
    }

    // --- Changes version selector (GLC-41) ----------------------------------------------------

    /** EDT. Stores the loaded [versions], resets the selection to "All changes" and rebuilds the combo. */
    private fun renderVersions(versions: List<GitLabMrVersion>) {
        this.versions = versions
        selectedVersion = null
        rebuildVersionCombo()
    }

    /**
     * EDT. Rebuilds the combo from [versions] ("All changes" + one entry per version), selecting "All
     * changes", and shows the selector only when the MR has at least one version (a lone "All changes"
     * entry is not worth a combo). The [updatingVersionCombo] guard keeps the programmatic rebuild from
     * being read as a user pick.
     */
    private fun rebuildVersionCombo() {
        updatingVersionCombo = true
        try {
            versionCombo.removeAllItems()
            for (view in changesViews(versions)) versionCombo.addItem(view)
            if (versionCombo.itemCount > 0) versionCombo.selectedIndex = 0
        } finally {
            updatingVersionCombo = false
        }
        versionSelectorPanel.isVisible = versions.isNotEmpty()
    }

    /** The combo's label for one [view]: "All changes", or "Version N · <relative date>". */
    private fun versionOptionLabel(view: ChangesView): String = when (view) {
        ChangesView.AllChanges -> CockpitBundle.message("changes.version.all")
        is ChangesView.Version ->
            CockpitBundle.message("changes.version.label", view.ordinal, formatRelative(view.version.createdAt))
    }

    /** EDT. A user pick in the version combo: switch the tree/diff to that view (ignored during a rebuild). */
    private fun onVersionSelected() {
        if (updatingVersionCombo) return
        when (val view = versionCombo.selectedItem as? ChangesView) {
            null, ChangesView.AllChanges -> showAllChanges()
            is ChangesView.Version -> showVersion(view.version)
        }
    }

    /** EDT. Returns the tree/diff to the MR's full "All changes" set (already loaded — no network). */
    private fun showAllChanges() {
        versionDiffsJob?.cancel()
        selectedVersion = null
        renderFiles(allChangesFiles)
    }

    /**
     * EDT. Loads [version]'s changed files off the EDT and renders them; the diff then opens each file at
     * the version's base/head SHAs ([viewRefs]). A stale result (the MR changed, or another view was
     * picked meanwhile) is dropped. Reviewed state and its counter are hidden while a version is shown.
     */
    private fun showVersion(version: GitLabMrVersion) {
        val ref = currentRef ?: return
        selectedVersion = version
        updateReviewedCounter()
        tree.emptyText.text = CockpitBundle.message("changes.loading")
        versionDiffsJob?.cancel()
        versionDiffsJob = service.coroutineScope.launch {
            val result = service.getMrVersionDiffs(ref, version.id)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref || selectedVersion != version) return@withContext
                when (result) {
                    is GitLabResult.Success -> renderFiles(result.data)
                    else -> {
                        tree.emptyText.text =
                            CockpitBundle.message("changes.version.error", describe(result))
                        onFileCountChanged(null)
                    }
                }
            }
        }
    }

    /**
     * The base/head/start SHAs the diff opens with: the selected version's SHAs while a version is
     * shown, otherwise the MR's own `diff_refs`. Null only when no version is shown and the MR has no
     * `diff_refs` (nothing to anchor a diff to).
     */
    private fun viewRefs(): DiffRefs? = selectedVersion?.let { versionRefs(it) } ?: diffRefs

    // --- Reviewed state (GLC-35) --------------------------------------------------------------

    /** A changed file's tree/review key: its `new_path`, or `old_path` for a deleted file. */
    private fun treePathOf(file: GitLabDiffFile): String =
        if (file.deletedFile) file.oldPath else file.newPath

    /**
     * Whether [path] is reviewed for the current MR at its head SHA (false when either is unknown).
     * Always false while a concrete version is shown — reviewed state is keyed to the current head only.
     */
    private fun isPathReviewed(path: String): Boolean {
        if (selectedVersion != null) return false
        val ref = currentRef ?: return false
        val sha = diffRefs?.headSha ?: return false
        return reviewedFiles.isReviewed(ref, sha, path)
    }

    /**
     * EDT. Refreshes the muted "N of M files reviewed" footer (hidden when there is no change, or while
     * a concrete version is shown — reviewed counters only make sense on the current head's "All changes").
     */
    private fun updateReviewedCounter() {
        val ref = currentRef
        val sha = diffRefs?.headSha
        if (selectedVersion != null || ref == null || sha == null || loadedFiles.isEmpty()) {
            reviewedCountLabel.isVisible = false
            reviewedCountLabel.text = ""
            return
        }
        val paths = loadedFiles.map { treePathOf(it) }
        val reviewed = reviewedFiles.reviewedCount(ref, sha, paths)
        reviewedCountLabel.text = CockpitBundle.message("changes.reviewed.count", reviewed, paths.size)
        reviewedCountLabel.isVisible = true
    }

    /** Space on the selected file leaf: flip its reviewed state, then repaint the tree and counter. */
    private fun toggleReviewedForSelection() {
        if (selectedVersion != null) return
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val fileNode = node.userObject as? FileNode ?: return
        if (fileNode.file == null) return
        val ref = currentRef ?: return
        val sha = diffRefs?.headSha ?: return
        reviewedFiles.toggle(ref, sha, fileNode.path)
        tree.repaint()
        updateReviewedCounter()
    }

    /** Right-click on a file leaf: a single "Mark as (not) reviewed" item reflecting its state. */
    private fun showReviewedContextMenu(comp: Component, x: Int, y: Int) {
        if (selectedVersion != null) return
        val path = tree.getPathForLocation(x, y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val fileNode = node.userObject as? FileNode ?: return
        if (fileNode.file == null) return
        tree.selectionPath = path
        val ref = currentRef ?: return
        val sha = diffRefs?.headSha ?: return
        val reviewed = reviewedFiles.isReviewed(ref, sha, fileNode.path)
        val item = JMenuItem(
            CockpitBundle.message(
                if (reviewed) "changes.file.markNotReviewed" else "changes.file.markReviewed",
            ),
        ).apply { addActionListener { setReviewed(fileNode.path, !reviewed) } }
        JPopupMenu().apply { add(item) }.show(comp, x, y)
    }

    /** Sets [path]'s reviewed state to [reviewed] (idempotent), then repaints the tree and counter. */
    private fun setReviewed(path: String, reviewed: Boolean) {
        val ref = currentRef ?: return
        val sha = diffRefs?.headSha ?: return
        if (reviewed) {
            reviewedFiles.mark(ref, sha, path)
        } else if (reviewedFiles.isReviewed(ref, sha, path)) {
            reviewedFiles.toggle(ref, sha, path)
        }
        tree.repaint()
        updateReviewedCounter()
    }

    /**
     * EDT callback for [CockpitDiffContext.onFileReviewed]: the diff extension just auto-marked a file
     * reviewed, so repaint the tree and refresh the counter if this tab is still bound to an MR.
     */
    private fun refreshReviewedAfterAutoMark() {
        if (currentRef == null) return
        tree.repaint()
        updateReviewedCounter()
    }

    // --- Diff in the editor -------------------------------------------------------------------

    /**
     * Opens the MR's files as a single diff *chain* — every changed file, in tree order — positioned on
     * [file], so the platform's next/previous-file navigation (Alt+Shift+Right/Left) and the
     * "go to changed file" popup walk the whole MR without reopening a diff per file. Each file's two
     * sides are fetched lazily (only when its slide is shown) off the EDT in the producer's [process],
     * so opening is instant and the other files cost nothing until visited. A missing `diff_refs` is a
     * hard error (nothing to anchor to).
     *
     * [revealDiscussionId] is handed only to [file]'s producer (the timeline "jump to thread" path),
     * so just the file the user landed on scrolls to its thread.
     */
    private fun openDiff(file: GitLabDiffFile, revealDiscussionId: String? = null) {
        val ref = currentRef ?: return
        val refs = viewRefs()
        if (refs == null) {
            Messages.showErrorDialog(
                project,
                CockpitBundle.message("changes.diff.noRefs"),
                CockpitBundle.message("detail.error.title"),
            )
            return
        }
        val files = loadedFiles
        if (files.isEmpty()) return
        // Review context (inline threads, "new comment at caret" and auto-mark-reviewed) is bound to
        // the MR's current head, so it is attached only in "All changes" — a concrete version is a
        // read-only historical view (see [buildDiffRequest]).
        val reviewContext = selectedVersion == null
        // Snapshot the panel state the (background) producers read, so the whole chain is consistent
        // and no field is touched off the EDT.
        val discussionsByPath = discussionsByFilePath
        val webUrl = projectWebUrl
        val producers = files.map { changed ->
            diffProducerFor(
                ref,
                changed,
                refs,
                discussionsByPath,
                webUrl,
                revealDiscussionId.takeIf { changed === file },
                reviewContext,
            )
        }
        val chain = SimpleDiffRequestChain.fromProducers(producers, chainIndex(files, file))
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }

    /** Marker for a successfully resolved diff side (an HTTP error resolves to empty text). */
    private class SideText(val text: String)

    /**
     * Fetches one diff side from [projectId]'s repository. A `404` (or any HTTP error — e.g. the old
     * side of a renamed file that did not exist under that path) resolves to empty text so the diff
     * still opens; a transport failure returns null so the caller can report it.
     */
    private suspend fun loadSide(projectId: Long, path: String, ref: String): SideText? =
        when (val result = service.getRawFile(projectId, path, ref)) {
            is GitLabResult.Success -> SideText(result.data)
            is GitLabResult.HttpError -> SideText("")
            is GitLabResult.NetworkError -> null
        }

    /**
     * One chain entry: a lazy [DiffRequestProducer] whose name is [file]'s display path. Its [process]
     * runs off the EDT (the chain processor drives it with a progress indicator) and builds the same
     * [SimpleDiffRequest] the old direct-open path did — via [buildDiffRequest] — so nothing about a
     * single file's diff changes; only *when* it is assembled does.
     */
    private fun diffProducerFor(
        ref: MrRef,
        file: GitLabDiffFile,
        refs: DiffRefs,
        discussionsByPath: Map<String, List<GitLabDiscussion>>,
        webUrl: String?,
        revealDiscussionId: String?,
        reviewContext: Boolean,
    ): DiffRequestProducer {
        val displayPath = if (file.deletedFile) file.oldPath else file.newPath
        return SimpleDiffRequestProducer.create(displayPath) {
            buildDiffRequest(ref, file, refs, discussionsByPath, webUrl, revealDiscussionId, reviewContext)
        }
    }

    /**
     * Background (producer [process] thread). Fetches [file]'s two sides raw at the MR's `diff_refs`
     * base/head SHAs (the missing side of an add/delete is empty), blocking with
     * [runBlockingCancellable] so the suspend service calls run under the indicator's cancellation, and
     * assembles a [SimpleDiffRequest] with the file's type. A transport failure on either side aborts
     * the producer with a [DiffRequestProducerException] carrying the localized error.
     *
     * A "Comment on line…" toolbar action ([DiffUserDataKeys.CONTEXT_ACTIONS]) is attached so the user
     * can start a review thread from the caret without leaving the diff. The request also carries a
     * [CockpitDiffContext] (F4c) with [file]'s loaded discussions ([discussionsByPath]) so
     * [dev.jota.gitlabcockpit.ui.diff.CockpitDiffExtension] renders the review threads inline, stamps
     * the "New comment at caret" handle ([openNewThreadDialog]) and — when there are threads — the
     * thread-navigation handle. [revealDiscussionId], when set, tells the renderer to scroll to that
     * thread. The two editors show the whole base/head file, so an editor line equals that side's
     * GitLab line number.
     */
    @Throws(DiffRequestProducerException::class)
    private fun buildDiffRequest(
        ref: MrRef,
        file: GitLabDiffFile,
        refs: DiffRefs,
        discussionsByPath: Map<String, List<GitLabDiscussion>>,
        webUrl: String?,
        revealDiscussionId: String?,
        reviewContext: Boolean,
    ): DiffRequest {
        val sides = runBlockingCancellable {
            val old = if (file.newFile) SideText("") else loadSide(ref.projectId, file.oldPath, refs.baseSha)
            val new = if (file.deletedFile) SideText("") else loadSide(ref.projectId, file.newPath, refs.headSha)
            old to new
        }
        val (oldSide, newSide) = sides
        if (oldSide == null || newSide == null) {
            throw DiffRequestProducerException(CockpitBundle.message("changes.diff.error"))
        }
        val displayPath = if (file.deletedFile) file.oldPath else file.newPath
        val fileName = displayPath.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            CockpitBundle.message("changes.diff.title", ref.iid, displayPath),
            factory.create(project, oldSide.text, fileType),
            factory.create(project, newSide.text, fileType),
            CockpitBundle.message("changes.diff.base"),
            CockpitBundle.message("changes.diff.head"),
        )
        // Only the "All changes" head carries the review affordances: the "Comment on line…" action, the
        // inline thread renderer and the auto-mark-reviewed. A concrete version opens as a plain, read-only
        // diff (no [CockpitDiffContext]), so nothing is anchored to — or reviewed against — its old SHAs.
        if (reviewContext) {
            val commentAction = object : AnAction(
                CockpitBundle.message("diff.commentAction"),
                null,
                AllIcons.General.Balloon,
            ) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = onCommentFromDiff(e, file, refs)
            }
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf<AnAction>(commentAction))
            // The discussions map keys by the position's new_path (falling back to old_path), so both
            // paths are probed — a rename/delete may have been keyed under either one.
            val fileDiscussions =
                (discussionsByPath[file.newPath].orEmpty() + discussionsByPath[file.oldPath].orEmpty())
                    .distinctBy { it.id }
            request.putUserData(
                CockpitDiffContext.KEY,
                CockpitDiffContext(
                    mrRef = ref,
                    file = file,
                    refs = refs,
                    discussions = fileDiscussions,
                    projectWebUrl = webUrl,
                    revealDiscussionId = revealDiscussionId,
                    openNewThread = { side, line -> openNewThreadDialog(file, refs, side, line) },
                    onFileReviewed = { refreshReviewedAfterAutoMark() },
                    onFileShown = { syncTreeToShownFile(file) },
                ),
            )
        }
        return request
    }

    /**
     * Resolves a [pendingRevealId] once the changes are loaded: finds the file the discussion is
     * anchored to (via the by-file discussion keys), selects it in the tree and opens its diff with
     * the reveal target set. Consumes the pending id; a discussion that is unknown or not anchored to
     * a file is a silent no-op. The diff is (re-)issued as a fresh request — the same thing a
     * double-click does — since the architecture builds a new [SimpleDiffRequest] per open; the new
     * request carries the reveal id so its viewer scrolls to the thread.
     */
    private fun resolvePendingReveal() {
        val id = pendingRevealId ?: return
        pendingRevealId = null
        val path = discussionsByFilePath.entries
            .firstOrNull { (_, threads) -> threads.any { it.id == id } }
            ?.key ?: return
        val file = loadedFiles.firstOrNull { it.newPath == path || it.oldPath == path } ?: return
        selectFileInTree(file)
        openDiff(file, revealDiscussionId = id)
    }

    /** Selects [file]'s leaf in the changed-files tree. */
    private fun selectFileInTree(file: GitLabDiffFile) {
        val targetPath = if (file.deletedFile) file.oldPath else file.newPath
        val node = findFileNode(rootNode, targetPath) ?: return
        TreeUtil.selectNode(tree, node)
    }

    /**
     * EDT callback for [CockpitDiffContext.onFileShown] (GLC-43 C14): keep the tree in step with the
     * file whose diff is on screen as the user walks the diff chain (next/previous file). Selects the
     * file's leaf and scrolls it into view **without requesting focus**, so the diff editor keeps the
     * keyboard — only the visual selection follows. A no-op when the tab was unbound or the node is gone
     * (a stale chain after a reload). Because the selection now tracks the shown file, the manual
     * reviewed toggle (Space / right-click) acts on that file — which is what the user expects.
     */
    private fun syncTreeToShownFile(file: GitLabDiffFile) {
        if (currentRef == null) return
        val targetPath = if (file.deletedFile) file.oldPath else file.newPath
        val node = findFileNode(rootNode, targetPath) ?: return
        val path = TreePath(node.path)
        if (tree.selectionPath == path) return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /** Depth-first search for the file leaf whose [FileNode.path] equals [path]. */
    private fun findFileNode(node: DefaultMutableTreeNode, path: String): DefaultMutableTreeNode? {
        val data = node.userObject as? FileNode
        if (data?.file != null && data.path == path) return node
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            findFileNode(child, path)?.let { return it }
        }
        return null
    }

    /** Re-fetches the MR's discussions (diffs unchanged) so the tree's comment badges stay current. */
    private fun reloadDiscussions(ref: MrRef) {
        discussionsJob?.cancel()
        discussionsJob = service.coroutineScope.launch {
            val result = service.getMrDiscussions(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                val discussions = (result as? GitLabResult.Success)?.data ?: emptyList()
                discussionsByFilePath = discussionsByFile(discussions)
                tree.repaint()
            }
        }
    }

    // --- New review thread (F4a) --------------------------------------------------------------

    /**
     * Diff context action: opens the dialog pre-filled from the caret. The caret's editor line (1-based)
     * is that side's GitLab line number; the side is derived by comparing the current editor with the
     * two-side viewer's editors, degrading to the new side when it cannot be determined reliably.
     */
    private fun onCommentFromDiff(e: AnActionEvent, file: GitLabDiffFile, refs: DiffRefs) {
        val editor = e.getData(DiffDataKeys.CURRENT_EDITOR) ?: e.getData(CommonDataKeys.EDITOR)
        val caretLine = editor?.caretModel?.logicalPosition?.line?.plus(1)
        val threadSide = when (determineSide(e, editor)) {
            Side.LEFT -> ThreadSide.OLD
            Side.RIGHT -> ThreadSide.NEW
            else -> ThreadSide.NEW
        }
        openNewThreadDialog(file, refs, threadSide, caretLine)
    }

    /**
     * Which side the [editor] belongs to, or null when it cannot be told reliably (e.g. a unified
     * viewer). Compares the current editor against the two-side viewer's left/right editors.
     */
    private fun determineSide(e: AnActionEvent, editor: Editor?): Side? {
        if (editor == null) return null
        val viewer = e.getData(DiffDataKeys.DIFF_VIEWER)
        if (viewer is TwosideTextDiffViewer) {
            return when {
                editor === viewer.getEditor(Side.LEFT) -> Side.LEFT
                editor === viewer.getEditor(Side.RIGHT) -> Side.RIGHT
                else -> null
            }
        }
        return null
    }

    /** Parses [file]'s diff into a line-map and shows the [NewThreadDialog]; posts on OK. */
    private fun openNewThreadDialog(file: GitLabDiffFile, refs: DiffRefs, side: ThreadSide, line: Int?) {
        val ref = currentRef ?: return
        val lineMap = buildLineMap(file.diff)
        val dialog = NewThreadDialog(project, file, lineMap, side, line)
        if (!dialog.showAndGet()) return
        val pos = dialog.selectedPosition() ?: return
        val body = dialog.body()
        if (body.isBlank()) return
        if (dialog.saveAsDraft()) {
            submitNewDraftThread(ref, file, refs, pos, body)
        } else {
            submitNewThread(ref, file, refs, pos, body)
        }
    }

    /** Posts a new diff thread off the EDT, then reloads discussions so the tree badges refresh. */
    private fun submitNewThread(ref: MrRef, file: GitLabDiffFile, refs: DiffRefs, pos: LinePosition, body: String) {
        newThreadJob?.cancel()
        newThreadJob = service.coroutineScope.launch {
            val result = service.createDiffThread(ref, file, refs, pos, body)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> reloadDiscussions(ref)
                    else -> Messages.showErrorDialog(
                        project,
                        CockpitBundle.message("changes.error.createThread", describe(result)),
                        CockpitBundle.message("detail.error.title"),
                    )
                }
            }
        }
    }

    /**
     * Posts a new *draft* diff thread off the EDT, then reloads the "Pending review" section. A draft
     * is not a published discussion, so the discussions tree is left untouched.
     */
    private fun submitNewDraftThread(ref: MrRef, file: GitLabDiffFile, refs: DiffRefs, pos: LinePosition, body: String) {
        newThreadJob?.cancel()
        newThreadJob = service.coroutineScope.launch {
            val result = service.createDraftThread(ref, file, refs, pos, body)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> reloadDrafts(ref)
                    else -> Messages.showErrorDialog(
                        project,
                        CockpitBundle.message("changes.error.drafts", describe(result)),
                        CockpitBundle.message("detail.error.title"),
                    )
                }
            }
        }
    }

    // --- Pending review (F4b) -----------------------------------------------------------------

    /** EDT. Rebuilds the "Pending review" section from [drafts]; hides it when there are none. */
    private fun renderDrafts(drafts: List<GitLabDraftNote>) {
        currentDrafts = drafts
        draftsRowsPanel.removeAll()
        if (drafts.isEmpty()) {
            draftsRowsPanel.add(
                JBLabel(CockpitBundle.message("changes.pending.empty")).apply {
                    foreground = UIUtil.getInactiveTextColor()
                },
            )
        } else {
            for (draft in drafts) draftsRowsPanel.add(buildDraftRow(draft))
        }
        submitButton.text = CockpitBundle.message("changes.pending.submit", drafts.size)
        submitButton.isEnabled = drafts.isNotEmpty()
        pendingReviewPanel.isVisible = drafts.isNotEmpty()
        draftsRowsPanel.revalidate()
        draftsRowsPanel.repaint()
        revalidate()
        repaint()
    }

    /** One "Pending review" row: the draft's text (+ line) on the left, a Delete link on the right. */
    private fun buildDraftRow(draft: GitLabDraftNote): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }
        row.add(JBLabel(draftLabelText(draft)), BorderLayout.CENTER)
        val delete = ActionLink(CockpitBundle.message("changes.pending.delete")) { onDeleteDraft(draft.id) }
        val east = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        east.add(delete)
        row.add(east, BorderLayout.EAST)
        return row
    }

    /** Deletes a single draft off the EDT, then reloads the "Pending review" section. */
    private fun onDeleteDraft(draftId: Long) {
        val ref = currentRef ?: return
        deleteJob?.cancel()
        deleteJob = service.coroutineScope.launch {
            val result = service.deleteDraftNote(ref, draftId)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> reloadDrafts(ref)
                    else -> Messages.showErrorDialog(
                        project,
                        CockpitBundle.message("changes.error.drafts", describe(result)),
                        CockpitBundle.message("detail.error.title"),
                    )
                }
            }
        }
    }

    /** Re-fetches the MR's draft notes and re-renders the "Pending review" section. */
    private fun reloadDrafts(ref: MrRef) {
        draftsJob?.cancel()
        draftsJob = service.coroutineScope.launch {
            val result = service.getDraftNotes(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                renderDrafts((result as? GitLabResult.Success)?.data ?: emptyList())
            }
        }
    }

    /**
     * "Submit review": publishes every pending draft off the EDT. On success it reloads the drafts and
     * the discussions (published drafts become threads), notifies the parent to refresh its timeline,
     * and fires a balloon notification.
     */
    private fun onSubmitReview() {
        val ref = currentRef ?: return
        val count = currentDrafts.size
        if (count == 0) return
        submitButton.isEnabled = false
        publishJob?.cancel()
        publishJob = service.coroutineScope.launch {
            val result = service.publishDrafts(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        notifyReviewSubmitted(count)
                        reloadDrafts(ref)
                        reloadDiscussions(ref)
                        onReviewSubmitted()
                    }
                    else -> {
                        submitButton.isEnabled = true
                        Messages.showErrorDialog(
                            project,
                            CockpitBundle.message("changes.error.publish", describe(result)),
                            CockpitBundle.message("detail.error.title"),
                        )
                    }
                }
            }
        }
    }

    /** Fires the "Review submitted" balloon on the existing Cockpit notification group. */
    private fun notifyReviewSubmitted(count: Int) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(COCKPIT_NOTIFICATION_GROUP)
            .createNotification(
                CockpitBundle.message("notification.review.submitted", count),
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    /** `<first line, ≤80 chars>` plus ` · L<n>` when the draft is anchored to a diff line. */
    private fun draftLabelText(draft: GitLabDraftNote): String {
        val text = draft.note.lineSequence().firstOrNull()?.trim().orEmpty().take(80)
        val line = draft.position?.let { it.newLine ?: it.oldLine }
        return if (line != null) {
            text + " · " + CockpitBundle.message("changes.comments.line", line)
        } else {
            text
        }
    }

    // --- Tree cell rendering ------------------------------------------------------------------

    private inner class FileTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            jtree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val data = node.userObject as? FileNode ?: return
            if (data.isDir) {
                icon = AllIcons.Nodes.Folder
                append(data.name)
                return
            }
            val file = data.file
            val reviewed = file != null && isPathReviewed(data.path)
            // File icon comes from its type; a reviewed file adds a tick badge next to it and mutes the
            // name, otherwise the change type tints the name (added/deleted/renamed).
            val typeIcon = FileTypeManager.getInstance().getFileTypeByFileName(data.name).icon
                ?: AllIcons.FileTypes.Any_type
            icon = if (reviewed) RowIcon(typeIcon, AllIcons.Actions.Checked) else typeIcon
            append(data.name, nameAttributes(file, reviewed))
            val count = discussionsByFilePath[data.path]?.size ?: 0
            if (count > 0) {
                append(
                    "  " + CockpitBundle.message("changes.file.comments", count),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
        }
    }

    /** Name attributes for a file leaf: muted when reviewed, else tinted by its change type. */
    private fun nameAttributes(file: GitLabDiffFile?, reviewed: Boolean): SimpleTextAttributes {
        if (reviewed) return SimpleTextAttributes.GRAYED_ATTRIBUTES
        val color = file?.let { colorForChange(changeTypeOf(it)) }
            ?: return SimpleTextAttributes.REGULAR_ATTRIBUTES
        return SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
    }

    /**
     * Modal dialog to start a review thread on a diff line. Shows the file, a New/Old side selector,
     * a line combo populated *only* with the [lineMap]'s commentable lines for the chosen side, and a
     * comment box. When the file has no inline diff (no commentable line) the inputs are disabled and
     * OK stays blocked with a notice. [selectedPosition] resolves the chosen side+line to a
     * [LinePosition] via the line-map.
     */
    private class NewThreadDialog(
        project: Project,
        private val file: GitLabDiffFile,
        private val lineMap: DiffLineMap,
        initialSide: ThreadSide,
        initialLine: Int?,
    ) : DialogWrapper(project) {

        private val hasCommentableLines =
            lineMap.commentableNewLines.isNotEmpty() || lineMap.commentableOldLines.isNotEmpty()

        private val sideCombo = ComboBox<ThreadSide>().apply {
            addItem(ThreadSide.NEW)
            addItem(ThreadSide.OLD)
            renderer = textCellRenderer<ThreadSide>("") { side ->
                when (side) {
                    ThreadSide.NEW -> CockpitBundle.message("dialog.newThread.side.new")
                    ThreadSide.OLD -> CockpitBundle.message("dialog.newThread.side.old")
                }
            }
            selectedItem = initialSide
            isEnabled = hasCommentableLines
        }

        private val lineCombo = ComboBox<Int>().apply {
            renderer = textCellRenderer<Int>("") { line ->
                CockpitBundle.message("dialog.newThread.line.label", line)
            }
        }

        private val bodyArea = JBTextArea(6, 50).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        private val draftCheckBox = JBCheckBox(CockpitBundle.message("dialog.newThread.draft"))

        private val noticeLabel = JBLabel(CockpitBundle.message("dialog.newThread.noDiff")).apply {
            foreground = CockpitTheme.danger
            isVisible = !hasCommentableLines
        }

        init {
            title = CockpitBundle.message("dialog.newThread.title")
            sideCombo.addActionListener { repopulateLines(null) }
            repopulateLines(initialLine)
            init()
        }

        /** Fills the line combo with the commentable lines of the selected side, keeping [preferred] if present. */
        private fun repopulateLines(preferred: Int?) {
            val side = sideCombo.selectedItem as? ThreadSide ?: ThreadSide.NEW
            val lines = if (side == ThreadSide.NEW) lineMap.commentableNewLines else lineMap.commentableOldLines
            lineCombo.removeAllItems()
            lines.forEach { lineCombo.addItem(it) }
            lineCombo.isEnabled = lines.isNotEmpty()
            when {
                preferred != null && preferred in lines -> lineCombo.selectedItem = preferred
                lines.isNotEmpty() -> lineCombo.selectedIndex = 0
            }
        }

        override fun createCenterPanel(): JComponent {
            val displayPath = if (file.deletedFile) file.oldPath else file.newPath
            return panel {
                row(CockpitBundle.message("dialog.newThread.file")) {
                    cell(JBLabel(displayPath))
                }
                row(CockpitBundle.message("dialog.newThread.side")) {
                    cell(sideCombo)
                }
                row(CockpitBundle.message("dialog.newThread.line")) {
                    cell(lineCombo)
                }
                row(CockpitBundle.message("dialog.newThread.body")) {
                    cell(JBScrollPane(bodyArea)).align(Align.FILL)
                }.resizableRow()
                row { cell(draftCheckBox) }
                if (!hasCommentableLines) {
                    row { cell(noticeLabel) }
                }
            }.apply { preferredSize = CockpitTheme.NEW_THREAD_DIALOG_SIZE }
        }

        override fun doValidate(): ValidationInfo? = when {
            !hasCommentableLines -> ValidationInfo(CockpitBundle.message("dialog.newThread.noDiff"))
            lineCombo.selectedItem == null ->
                ValidationInfo(CockpitBundle.message("dialog.newThread.selectLine"), lineCombo)
            bodyArea.text.isBlank() ->
                ValidationInfo(CockpitBundle.message("dialog.newThread.emptyBody"), bodyArea)
            else -> null
        }

        override fun getPreferredFocusedComponent(): JComponent = bodyArea

        /** The chosen line resolved to a [LinePosition] via the line-map, or null when none is selected. */
        fun selectedPosition(): LinePosition? {
            val line = lineCombo.selectedItem as? Int ?: return null
            return when (sideCombo.selectedItem as? ThreadSide) {
                ThreadSide.NEW -> lineMap.forNewLine(line)
                ThreadSide.OLD -> lineMap.forOldLine(line)
                null -> null
            }
        }

        fun body(): String = bodyArea.text.trim()

        /** Whether the user chose to save the comment as a draft instead of publishing it now. */
        fun saveAsDraft(): Boolean = draftCheckBox.isSelected
    }

    companion object {
        /**
         * Name color for a change type: added/deleted/renamed map to the IDE's VCS [FileStatus] colors
         * (with the plugin palette as a fallback when the active theme leaves one unset); a plain
         * modification returns null so the name keeps the tree's default foreground.
         */
        private fun colorForChange(type: ChangeType): Color? = when (type) {
            ChangeType.ADDED -> FileStatus.ADDED.color ?: CockpitTheme.success
            ChangeType.DELETED -> FileStatus.DELETED.color ?: CockpitTheme.danger
            ChangeType.RENAMED -> FileStatus.MODIFIED.color ?: CockpitTheme.info
            ChangeType.MODIFIED -> null
        }

        private fun describe(result: GitLabResult<*>): String = when (result) {
            is GitLabResult.HttpError -> "HTTP ${result.status}"
            is GitLabResult.NetworkError -> result.cause.message ?: result.cause.javaClass.simpleName
            is GitLabResult.Success<*> -> ""
        }
    }
}
