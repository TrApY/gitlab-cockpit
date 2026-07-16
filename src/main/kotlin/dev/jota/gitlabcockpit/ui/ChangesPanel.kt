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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.DiffRefs
import dev.jota.gitlabcockpit.api.GitLabDiffFile
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabDraftNote
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.NotePosition
import dev.jota.gitlabcockpit.core.COCKPIT_NOTIFICATION_GROUP
import dev.jota.gitlabcockpit.core.ChangeType
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.DiffLineMap
import dev.jota.gitlabcockpit.core.FileNode
import dev.jota.gitlabcockpit.core.LinePosition
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.ThreadSide
import dev.jota.gitlabcockpit.core.buildLineMap
import dev.jota.gitlabcockpit.core.chainIndex
import dev.jota.gitlabcockpit.core.changeTypeOf
import dev.jota.gitlabcockpit.core.buildFileTree
import dev.jota.gitlabcockpit.core.discussionsByFile
import dev.jota.gitlabcockpit.ui.diff.CockpitDiffContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The "Changes" tab of the MR detail. A vertical splitter with:
 *
 * - **top**: a tree of the MR's changed files (grouped by directory), each file iconed by its
 *   [ChangeType] and suffixed with a comment count when it has diff discussions. A double-click on a
 *   file opens its base/head diff in the IDE editor ([DiffManager]) without any checkout — the two
 *   sides are fetched raw at the MR's `diff_refs` base/head SHAs.
 * - **bottom**: the diff discussions of the selected file. A list of threads, an HTML view of the
 *   selected thread's notes, and a single reply box that posts to the selected thread.
 *
 * Diffs and discussions load lazily the first time the tab is shown for an MR ([onTabSelected]) and
 * are re-fetched after every detail refresh ([setMr]). Every network call runs on the service's
 * coroutine scope (never the EDT); results are marshaled with [Dispatchers.EDT] and dropped when
 * stale (re-checking [currentRef]).
 *
 * The bottom half also hosts the F4b "Pending review" section (the MR's unpublished draft notes,
 * with per-row delete plus Submit review / Refresh) and a per-thread Resolve/Unresolve action.
 *
 * @param onFileCountChanged reports the loaded file count (or null while unknown) so the parent can
 * put it in the tab title.
 * @param onReviewSubmitted called after a successful "Submit review" (bulk publish) so the parent can
 * refresh its Comments tab (published drafts become regular notes; the draft banner clears).
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

    /** Diff discussions grouped by file path; read by the tree renderer and the comments panel. */
    private var discussionsByFilePath: Map<String, List<GitLabDiscussion>> = emptyMap()

    /** Path of the file whose comments the bottom panel currently shows; null when none. */
    private var selectedFilePath: String? = null

    /** The changed file currently selected in the tree; enables "New thread". Null when none. */
    private var selectedFile: GitLabDiffFile? = null

    /** The MR's changed files (flat), kept so a reveal can map a discussion's path back to its file. */
    private var loadedFiles: List<GitLabDiffFile> = emptyList()

    /** True once a load has populated the discussions/files, so a pending reveal can resolve. */
    private var discussionsLoaded = false

    /** A discussion the Comments tab asked to reveal; held until the changes finish loading. */
    private var pendingRevealId: String? = null

    /** The MR's pending draft notes, loaded alongside the discussions; drives the "Pending review" section. */
    private var currentDrafts: List<GitLabDraftNote> = emptyList()

    private var loadJob: Job? = null
    private var discussionsJob: Job? = null
    private var replyJob: Job? = null
    private var newThreadJob: Job? = null
    private var draftsJob: Job? = null
    private var publishJob: Job? = null
    private var deleteJob: Job? = null
    private var resolveJob: Job? = null

    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = FileTreeRenderer()
    }

    private val discussionListModel = CollectionListModel<GitLabDiscussion>()
    private val discussionList = JBList(discussionListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = textCellRenderer<GitLabDiscussion>("") { discussionLabel(it) }
    }

    private val discussionPane = CockpitHtml.createHtmlPane()
    private val commentsTitle = JBLabel(CockpitBundle.message("changes.comments.title"))
    private val replyArea = JBTextArea(2, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = CockpitBundle.message("changes.reply.placeholder")
    }
    private val replyButton = JButton(CockpitBundle.message("changes.reply.button"))
    private val newThreadButton = JButton(CockpitBundle.message("changes.newThread")).apply {
        isEnabled = false
        addActionListener { onNewThread() }
    }

    /** Resolve/Unresolve action for the selected thread; hidden unless that thread is resolvable. */
    private val resolveButton = JButton().apply {
        isVisible = false
        addActionListener { onToggleResolve() }
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
        val splitter = OnePixelSplitter(true, 0.6f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = buildBottomPanel()
        }
        add(splitter, BorderLayout.CENTER)

        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val fileNode = node?.userObject as? FileNode
            selectedFile = fileNode?.file
            newThreadButton.isEnabled = selectedFile != null
            showFileComments(fileNode?.takeIf { it.file != null }?.path)
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

        discussionList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val discussion = discussionList.selectedValue
                replyButton.isEnabled = discussion != null
                updateResolveButton(discussion)
                renderDiscussion(discussion)
            }
        }
        replyButton.addActionListener { onReply() }

        clear()
    }

    private fun buildCommentsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        commentsTitle.border = JBUI.Borders.empty(4, 8)
        val header = JPanel(BorderLayout())
        header.add(commentsTitle, BorderLayout.CENTER)
        val headerButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 8)
        }
        headerButtons.add(newThreadButton)
        header.add(headerButtons, BorderLayout.EAST)
        panel.add(header, BorderLayout.NORTH)

        val inner = OnePixelSplitter(false, 0.32f).apply {
            firstComponent = JBScrollPane(discussionList)
            secondComponent = JBScrollPane(discussionPane)
        }
        panel.add(inner, BorderLayout.CENTER)
        panel.add(buildReplyInput(), BorderLayout.SOUTH)
        return panel
    }

    private fun buildReplyInput(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.border = JBUI.Borders.empty(6, 8)
        panel.add(JBScrollPane(replyArea), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        buttons.add(resolveButton)
        buttons.add(replyButton)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    /**
     * The Changes tab's bottom half: the "Pending review" section (F4b, hidden unless there are
     * drafts) stacked above the file's discussions.
     */
    private fun buildBottomPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(buildPendingReviewPanel(), BorderLayout.NORTH)
        panel.add(buildCommentsPanel(), BorderLayout.CENTER)
        return panel
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

    /** Called when the Changes tab becomes visible; loads the changes the first time per MR. */
    fun onTabSelected() {
        val ref = currentRef ?: return
        if (loadedForRef != ref) load(ref)
    }

    /**
     * Reveals a discussion in the diff, driven by the Comments tab's "jump to thread" link. When the
     * changes are already loaded it resolves immediately; otherwise the id is held and resolved once
     * the (lazy) load triggered by the tab switch finishes. An unknown or non-positioned id is a
     * silent no-op.
     */
    fun revealDiscussion(discussionId: String) {
        pendingRevealId = discussionId
        if (discussionsLoaded) resolvePendingReveal()
    }

    private fun cancelJobs() {
        loadJob?.cancel()
        discussionsJob?.cancel()
        replyJob?.cancel()
        newThreadJob?.cancel()
        draftsJob?.cancel()
        publishJob?.cancel()
        deleteJob?.cancel()
        resolveJob?.cancel()
    }

    private fun clearContent() {
        rootNode.removeAllChildren()
        treeModel.reload()
        discussionsByFilePath = emptyMap()
        loadedFiles = emptyList()
        discussionsLoaded = false
        pendingRevealId = null
        selectedFilePath = null
        selectedFile = null
        newThreadButton.isEnabled = false
        discussionListModel.removeAll()
        discussionPane.text = CockpitHtml.wrapHtml("")
        replyArea.text = ""
        replyButton.isEnabled = false
        resolveButton.isVisible = false
        commentsTitle.text = CockpitBundle.message("changes.comments.title")
        currentDrafts = emptyList()
        draftsRowsPanel.removeAll()
        pendingReviewPanel.isVisible = false
    }

    // --- Loading ------------------------------------------------------------------------------

    /** Loads the MR's diffs and discussions in parallel, then renders the tree. */
    private fun load(ref: MrRef) {
        loadedForRef = ref
        clearContent()
        onFileCountChanged(null)
        tree.emptyText.text = CockpitBundle.message("changes.loading")
        loadJob?.cancel()
        loadJob = service.coroutineScope.launch {
            val (diffsResult, discussionsResult, draftsResult) = coroutineScope {
                val diffs = async { service.getMrDiffs(ref) }
                val discussions = async { service.getMrDiscussions(ref) }
                val drafts = async { service.getDraftNotes(ref) }
                Triple(diffs.await(), discussions.await(), drafts.await())
            }
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                // Drafts are non-fatal too: an error just hides the "Pending review" section.
                renderDrafts((draftsResult as? GitLabResult.Success)?.data ?: emptyList())
                when (diffsResult) {
                    is GitLabResult.Success -> {
                        // Discussions are non-fatal: an error just means "no comments shown".
                        val discussions = (discussionsResult as? GitLabResult.Success)?.data ?: emptyList()
                        discussionsByFilePath = discussionsByFile(discussions)
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
        selectedFilePath = null
        selectedFile = null
        newThreadButton.isEnabled = false
        showFileComments(null)
        onFileCountChanged(files.size)
    }

    private fun toTreeNode(node: FileNode): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(node)
        for (child in node.children) treeNode.add(toTreeNode(child))
        return treeNode
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
     * [revealDiscussionId] is handed only to [file]'s producer (the Comments-tab "jump to thread" path),
     * so just the file the user landed on scrolls to its thread.
     */
    private fun openDiff(file: GitLabDiffFile, revealDiscussionId: String? = null) {
        val ref = currentRef ?: return
        val refs = diffRefs
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
    ): DiffRequestProducer {
        val displayPath = if (file.deletedFile) file.oldPath else file.newPath
        return SimpleDiffRequestProducer.create(displayPath) {
            buildDiffRequest(ref, file, refs, discussionsByPath, webUrl, revealDiscussionId)
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
     * the "New comment at caret" handle ([openNewThread]) and — when there are threads — the
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
            ),
        )
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

    /** Selects [file]'s leaf in the changed-files tree (also refreshing the bottom comments panel). */
    private fun selectFileInTree(file: GitLabDiffFile) {
        val targetPath = if (file.deletedFile) file.oldPath else file.newPath
        val node = findFileNode(rootNode, targetPath) ?: return
        TreeUtil.selectNode(tree, node)
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

    // --- Comments -----------------------------------------------------------------------------

    /** EDT. Shows the discussions anchored to [path] (null → none); selects the first thread. */
    private fun showFileComments(path: String?) {
        selectedFilePath = path
        commentsTitle.text = path ?: CockpitBundle.message("changes.comments.title")
        val discussions = path?.let { discussionsByFilePath[it] }.orEmpty()
        discussionListModel.replaceAll(discussions)
        if (discussions.isEmpty()) {
            discussionPane.text = CockpitHtml.wrapHtml(
                "<p><i>" + CockpitHtml.escapeHtml(CockpitBundle.message("changes.comments.empty")) + "</i></p>",
            )
            replyButton.isEnabled = false
            updateResolveButton(null)
        } else {
            discussionList.selectedIndex = 0
        }
    }

    /** EDT. Renders every non-system note of [discussion] as one themed HTML document. */
    private fun renderDiscussion(discussion: GitLabDiscussion?) {
        val notes = discussion?.notes?.filterNot { it.system }.orEmpty()
        if (notes.isEmpty()) {
            discussionPane.text = CockpitHtml.wrapHtml("")
            return
        }
        val metaColor = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val fallbackPosition = positionOf(discussion)
        val body = buildString {
            notes.forEachIndexed { index, note ->
                append("<div style=\"color:").append(metaColor).append(";\">")
                append(CockpitHtml.escapeHtml(headerFor(note, fallbackPosition)))
                append("</div>")
                append(CockpitHtml.stripBody(MarkdownRenderer.toHtml(note.body)))
                if (index < notes.lastIndex) append("<hr>")
            }
        }
        // The async image re-apply is dropped once the user selects a different thread.
        applyMarkdownUploads(
            pane = discussionPane,
            fragment = body,
            service = service,
            projectId = currentRef?.projectId ?: return,
            projectWebUrl = projectWebUrl,
            isCurrent = { discussionList.selectedValue === discussion },
        )
    }

    /** Posts the reply-box text to the selected thread, then reloads the MR's discussions. */
    private fun onReply() {
        val ref = currentRef ?: return
        val discussion = discussionList.selectedValue ?: return
        val text = replyArea.text.trim()
        if (text.isEmpty()) return
        replyButton.isEnabled = false
        replyJob?.cancel()
        replyJob = service.coroutineScope.launch {
            val result = service.replyToDiscussion(ref, discussion.id, text)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        replyArea.text = ""
                        reloadDiscussions(ref, selectedFilePath, discussion.id)
                    }
                    else -> {
                        replyButton.isEnabled = discussionList.selectedValue != null
                        Messages.showErrorDialog(
                            project,
                            CockpitBundle.message("changes.error.reply", describe(result)),
                            CockpitBundle.message("detail.error.title"),
                        )
                    }
                }
            }
        }
    }

    /** Re-fetches the MR's discussions (diffs unchanged), refreshing tree badges and the panel. */
    private fun reloadDiscussions(ref: MrRef, keepFilePath: String?, keepDiscussionId: String?) {
        discussionsJob?.cancel()
        discussionsJob = service.coroutineScope.launch {
            val result = service.getMrDiscussions(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                val discussions = (result as? GitLabResult.Success)?.data ?: emptyList()
                discussionsByFilePath = discussionsByFile(discussions)
                tree.repaint()
                showFileComments(keepFilePath)
                if (keepDiscussionId != null) selectDiscussionById(keepDiscussionId)
            }
        }
    }

    private fun selectDiscussionById(id: String) {
        for (index in 0 until discussionListModel.size) {
            if (discussionListModel.getElementAt(index).id == id) {
                discussionList.selectedIndex = index
                return
            }
        }
    }

    // --- New review thread (F4a) --------------------------------------------------------------

    /** "New thread" button: opens the dialog for the selected file, defaulting to the new side. */
    private fun onNewThread() {
        val file = selectedFile ?: return
        val refs = diffRefs
        if (refs == null) {
            Messages.showErrorDialog(
                project,
                CockpitBundle.message("changes.diff.noRefs"),
                CockpitBundle.message("detail.error.title"),
            )
            return
        }
        openNewThreadDialog(file, refs, ThreadSide.NEW, null)
    }

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

    /** Posts a new diff thread off the EDT, then reloads discussions and re-selects the new thread. */
    private fun submitNewThread(ref: MrRef, file: GitLabDiffFile, refs: DiffRefs, pos: LinePosition, body: String) {
        newThreadJob?.cancel()
        newThreadJob = service.coroutineScope.launch {
            val result = service.createDiffThread(ref, file, refs, pos, body)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> reloadDiscussions(ref, file.newPath, result.data.id)
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

    // --- Pending review & resolution (F4b) ----------------------------------------------------

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
     * the discussions (published drafts become threads), notifies the parent to refresh its Comments
     * tab, and fires a balloon notification.
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
                        reloadDiscussions(ref, selectedFilePath, null)
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

    /** EDT. Shows a Resolve/Unresolve action for [discussion] when its thread is resolvable. */
    private fun updateResolveButton(discussion: GitLabDiscussion?) {
        val firstNote = discussion?.notes?.firstOrNull { !it.system }
        val resolvable = firstNote?.resolvable == true
        resolveButton.isVisible = resolvable
        resolveButton.isEnabled = resolvable
        if (resolvable) {
            resolveButton.text = CockpitBundle.message(
                if (firstNote!!.resolved) "changes.discussion.unresolve" else "changes.discussion.resolve",
            )
        }
    }

    /** Toggles the selected thread's resolution off the EDT, then reloads the discussions. */
    private fun onToggleResolve() {
        val ref = currentRef ?: return
        val discussion = discussionList.selectedValue ?: return
        val firstNote = discussion.notes.firstOrNull { !it.system } ?: return
        if (!firstNote.resolvable) return
        val newResolved = !firstNote.resolved
        resolveButton.isEnabled = false
        resolveJob?.cancel()
        resolveJob = service.coroutineScope.launch {
            val result = service.setDiscussionResolved(ref, discussion.id, newResolved)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> reloadDiscussions(ref, selectedFilePath, discussion.id)
                    else -> {
                        resolveButton.isEnabled = true
                        Messages.showErrorDialog(
                            project,
                            CockpitBundle.message("changes.error.resolve", describe(result)),
                            CockpitBundle.message("detail.error.title"),
                        )
                    }
                }
            }
        }
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
            } else {
                data.file?.let { icon = iconForChange(changeTypeOf(it)) }
                append(data.name)
                val count = discussionsByFilePath[data.path]?.size ?: 0
                if (count > 0) {
                    append("  " + CockpitBundle.message("changes.file.comments", count), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
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
            foreground = JBColor.RED
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
            val builder = FormBuilder.createFormBuilder()
                .addLabeledComponent(CockpitBundle.message("dialog.newThread.file"), JBLabel(displayPath))
                .addLabeledComponent(CockpitBundle.message("dialog.newThread.side"), sideCombo)
                .addLabeledComponent(CockpitBundle.message("dialog.newThread.line"), lineCombo)
                .addLabeledComponentFillVertically(
                    CockpitBundle.message("dialog.newThread.body"),
                    JBScrollPane(bodyArea),
                )
                .addComponent(draftCheckBox)
            if (!hasCommentableLines) builder.addComponent(noticeLabel)
            return builder.panel.apply { preferredSize = JBUI.size(520, 360) }
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
        /** Maps a file's change type to its tree icon. */
        private fun iconForChange(type: ChangeType): Icon = when (type) {
            ChangeType.ADDED -> AllIcons.Actions.AddFile
            // AllIcons.Actions.DeleteFile does not exist in 2025.2; General.Remove (a minus) is the
            // closest stable equivalent for a removed file.
            ChangeType.DELETED -> AllIcons.General.Remove
            ChangeType.RENAMED -> AllIcons.Actions.Copy
            ChangeType.MODIFIED -> AllIcons.Actions.Edit
        }

        /** The position of a discussion's first positioned, non-system note (or null). */
        private fun positionOf(discussion: GitLabDiscussion?): NotePosition? =
            discussion?.notes?.firstOrNull { !it.system && it.position != null }?.position

        /** `author · L<n> · <first body line>` for the thread list. */
        private fun discussionLabel(discussion: GitLabDiscussion): String {
            val note = discussion.notes.firstOrNull { !it.system } ?: return "…"
            val firstLine = note.body.lineSequence().firstOrNull()?.trim().orEmpty().take(60)
            val parts = buildList {
                add(displayName(note.author))
                lineLabel(positionOf(discussion))?.let { add(it) }
                if (firstLine.isNotEmpty()) add(firstLine)
            }
            return parts.joinToString(" · ")
        }

        /** `author · L<n> · resolved` header for one note. */
        private fun headerFor(note: GitLabDiscussionNote, fallback: NotePosition?): String {
            val parts = buildList {
                add(displayName(note.author))
                lineLabel(note.position ?: fallback)?.let { add(it) }
                if (note.resolved) add(CockpitBundle.message("changes.comments.resolved"))
            }
            return parts.joinToString(" · ")
        }

        /** `L<new_line|old_line>` for a position, or null when it anchors to no line. */
        private fun lineLabel(position: NotePosition?): String? {
            val line = position?.let { it.newLine ?: it.oldLine } ?: return null
            return CockpitBundle.message("changes.comments.line", line)
        }

        private fun displayName(user: GitLabUser): String = user.name.ifBlank { user.username }

        private fun describe(result: GitLabResult<*>): String = when (result) {
            is GitLabResult.HttpError -> "HTTP ${result.status}"
            is GitLabResult.NetworkError -> result.cause.message ?: result.cause.javaClass.simpleName
            is GitLabResult.Success<*> -> ""
        }
    }
}
