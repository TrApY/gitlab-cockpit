package dev.jota.gitlabcockpit.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
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
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.NotePosition
import dev.jota.gitlabcockpit.core.ChangeType
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.DiffLineMap
import dev.jota.gitlabcockpit.core.FileNode
import dev.jota.gitlabcockpit.core.LinePosition
import dev.jota.gitlabcockpit.core.buildLineMap
import dev.jota.gitlabcockpit.core.changeTypeOf
import dev.jota.gitlabcockpit.core.buildFileTree
import dev.jota.gitlabcockpit.core.discussionsByFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
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
 * stale (re-checking [currentIid]).
 *
 * @param onFileCountChanged reports the loaded file count (or null while unknown) so the parent can
 * put it in the tab title.
 */
class ChangesPanel(
    private val project: Project,
    private val service: CockpitProjectService,
    private val onFileCountChanged: (Int?) -> Unit,
) : JPanel(BorderLayout()) {

    /** iid of the MR currently displayed; null when cleared. */
    var currentIid: Long? = null
        private set

    /** The MR's diff SHAs; needed to open a diff. Null until the detail is bound (or if absent). */
    private var diffRefs: DiffRefs? = null

    /** The iid whose changes have been loaded, so the tab only reloads when it changes. */
    private var loadedForIid: Long? = null

    /** Diff discussions grouped by file path; read by the tree renderer and the comments panel. */
    private var discussionsByFilePath: Map<String, List<GitLabDiscussion>> = emptyMap()

    /** Path of the file whose comments the bottom panel currently shows; null when none. */
    private var selectedFilePath: String? = null

    /** The changed file currently selected in the tree; enables "New thread". Null when none. */
    private var selectedFile: GitLabDiffFile? = null

    private var loadJob: Job? = null
    private var discussionsJob: Job? = null
    private var diffJob: Job? = null
    private var replyJob: Job? = null
    private var newThreadJob: Job? = null

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
        cellRenderer = SimpleListCellRenderer.create("") { discussionLabel(it) }
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

    init {
        val splitter = OnePixelSplitter(true, 0.6f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = buildCommentsPanel()
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
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        buttons.add(replyButton)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    // --- Lifecycle called by MrDetailPanel ----------------------------------------------------

    /** Binds this tab to [iid] / [refs] and marks the changes as needing a (re)load. */
    fun setMr(iid: Long, refs: DiffRefs?) {
        currentIid = iid
        diffRefs = refs
        loadedForIid = null
        cancelJobs()
        clearContent()
        onFileCountChanged(null)
        tree.emptyText.text = ""
    }

    /** Resets to the empty placeholder (no MR selected). */
    fun clear() {
        currentIid = null
        diffRefs = null
        loadedForIid = null
        cancelJobs()
        clearContent()
        onFileCountChanged(null)
        tree.emptyText.text = ""
    }

    /** Called when the Changes tab becomes visible; loads the changes the first time per MR. */
    fun onTabSelected() {
        val iid = currentIid ?: return
        if (loadedForIid != iid) load(iid)
    }

    private fun cancelJobs() {
        loadJob?.cancel()
        discussionsJob?.cancel()
        diffJob?.cancel()
        replyJob?.cancel()
        newThreadJob?.cancel()
    }

    private fun clearContent() {
        rootNode.removeAllChildren()
        treeModel.reload()
        discussionsByFilePath = emptyMap()
        selectedFilePath = null
        selectedFile = null
        newThreadButton.isEnabled = false
        discussionListModel.removeAll()
        discussionPane.text = CockpitHtml.wrapHtml("")
        replyArea.text = ""
        replyButton.isEnabled = false
        commentsTitle.text = CockpitBundle.message("changes.comments.title")
    }

    // --- Loading ------------------------------------------------------------------------------

    /** Loads the MR's diffs and discussions in parallel, then renders the tree. */
    private fun load(iid: Long) {
        loadedForIid = iid
        clearContent()
        onFileCountChanged(null)
        tree.emptyText.text = CockpitBundle.message("changes.loading")
        loadJob?.cancel()
        loadJob = service.coroutineScope.launch {
            val (diffsResult, discussionsResult) = coroutineScope {
                val diffs = async { service.getMrDiffs(iid) }
                val discussions = async { service.getMrDiscussions(iid) }
                diffs.await() to discussions.await()
            }
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                when (diffsResult) {
                    is GitLabResult.Success -> {
                        // Discussions are non-fatal: an error just means "no comments shown".
                        val discussions = (discussionsResult as? GitLabResult.Success)?.data ?: emptyList()
                        discussionsByFilePath = discussionsByFile(discussions)
                        renderFiles(diffsResult.data)
                    }
                    else -> {
                        loadedForIid = null
                        tree.emptyText.text = CockpitBundle.message("changes.error.diffs", describe(diffsResult))
                        onFileCountChanged(null)
                    }
                }
            }
        }
    }

    /** EDT. Builds the file tree from [files] and updates the tab counter. */
    private fun renderFiles(files: List<GitLabDiffFile>) {
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
     * Opens [file]'s base/head diff in the editor. The two sides are fetched raw off the EDT at the
     * MR's `diff_refs` base/head SHAs (the missing side of an add/delete is empty); the diff is then
     * assembled and shown on the EDT. A missing `diff_refs` is a hard error (nothing to anchor to).
     */
    private fun openDiff(file: GitLabDiffFile) {
        val iid = currentIid ?: return
        val refs = diffRefs
        if (refs == null) {
            Messages.showErrorDialog(
                project,
                CockpitBundle.message("changes.diff.noRefs"),
                CockpitBundle.message("detail.error.title"),
            )
            return
        }
        diffJob?.cancel()
        diffJob = service.coroutineScope.launch {
            val oldSide = if (file.newFile) SideText("") else loadSide(file.oldPath, refs.baseSha)
            val newSide = if (file.deletedFile) SideText("") else loadSide(file.newPath, refs.headSha)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                if (oldSide == null || newSide == null) {
                    Messages.showErrorDialog(
                        project,
                        CockpitBundle.message("changes.diff.error"),
                        CockpitBundle.message("detail.error.title"),
                    )
                    return@withContext
                }
                showDiff(iid, file, refs, oldSide.text, newSide.text)
            }
        }
    }

    /** Marker for a successfully resolved diff side (an HTTP error resolves to empty text). */
    private class SideText(val text: String)

    /**
     * Fetches one diff side. A `404` (or any HTTP error — e.g. the old side of a renamed file that
     * did not exist under that path) resolves to empty text so the diff still opens; a transport
     * failure returns null so the caller can report it.
     */
    private suspend fun loadSide(path: String, ref: String): SideText? =
        when (val result = service.getRawFile(path, ref)) {
            is GitLabResult.Success -> SideText(result.data)
            is GitLabResult.HttpError -> SideText("")
            is GitLabResult.NetworkError -> null
        }

    /**
     * EDT. Assembles a [SimpleDiffRequest] with the file's type and shows it in the editor. A
     * "Comment on line…" context action ([DiffUserDataKeys.CONTEXT_ACTIONS]) is attached so the user
     * can start a review thread from the caret line without leaving the diff. The two editors show the
     * whole base/head file, so an editor line number equals that side's GitLab line number.
     */
    private fun showDiff(iid: Long, file: GitLabDiffFile, refs: DiffRefs, oldText: String, newText: String) {
        val displayPath = if (file.deletedFile) file.oldPath else file.newPath
        val fileName = displayPath.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            CockpitBundle.message("changes.diff.title", iid, displayPath),
            factory.create(project, oldText, fileType),
            factory.create(project, newText, fileType),
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
        DiffManager.getInstance().showDiff(project, request)
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
        discussionPane.text = CockpitHtml.wrapHtml(body)
        discussionPane.caretPosition = 0
    }

    /** Posts the reply-box text to the selected thread, then reloads the MR's discussions. */
    private fun onReply() {
        val iid = currentIid ?: return
        val discussion = discussionList.selectedValue ?: return
        val text = replyArea.text.trim()
        if (text.isEmpty()) return
        replyButton.isEnabled = false
        replyJob?.cancel()
        replyJob = service.coroutineScope.launch {
            val result = service.replyToDiscussion(iid, discussion.id, text)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                when (result) {
                    is GitLabResult.Success -> {
                        replyArea.text = ""
                        reloadDiscussions(iid, selectedFilePath, discussion.id)
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
    private fun reloadDiscussions(iid: Long, keepFilePath: String?, keepDiscussionId: String?) {
        discussionsJob?.cancel()
        discussionsJob = service.coroutineScope.launch {
            val result = service.getMrDiscussions(iid)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
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
        val iid = currentIid ?: return
        val lineMap = buildLineMap(file.diff)
        val dialog = NewThreadDialog(project, file, lineMap, side, line)
        if (!dialog.showAndGet()) return
        val pos = dialog.selectedPosition() ?: return
        val body = dialog.body()
        if (body.isBlank()) return
        submitNewThread(iid, file, refs, pos, body)
    }

    /** Posts a new diff thread off the EDT, then reloads discussions and re-selects the new thread. */
    private fun submitNewThread(iid: Long, file: GitLabDiffFile, refs: DiffRefs, pos: LinePosition, body: String) {
        newThreadJob?.cancel()
        newThreadJob = service.coroutineScope.launch {
            val result = service.createDiffThread(iid, file, refs, pos, body)
            withContext(Dispatchers.EDT) {
                if (currentIid != iid) return@withContext
                when (result) {
                    is GitLabResult.Success -> reloadDiscussions(iid, file.newPath, result.data.id)
                    else -> Messages.showErrorDialog(
                        project,
                        CockpitBundle.message("changes.error.createThread", describe(result)),
                        CockpitBundle.message("detail.error.title"),
                    )
                }
            }
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

    /** Which diff side a new thread anchors to. */
    private enum class ThreadSide { NEW, OLD }

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
            renderer = SimpleListCellRenderer.create("") { side ->
                when (side) {
                    ThreadSide.NEW -> CockpitBundle.message("dialog.newThread.side.new")
                    ThreadSide.OLD -> CockpitBundle.message("dialog.newThread.side.old")
                    null -> ""
                }
            }
            selectedItem = initialSide
            isEnabled = hasCommentableLines
        }

        private val lineCombo = ComboBox<Int>().apply {
            renderer = SimpleListCellRenderer.create("") { line ->
                line?.let { CockpitBundle.message("dialog.newThread.line.label", it) } ?: ""
            }
        }

        private val bodyArea = JBTextArea(6, 50).apply {
            lineWrap = true
            wrapStyleWord = true
        }

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
            if (!hasCommentableLines) builder.addComponent(noticeLabel)
            return builder.panel.apply { preferredSize = JBUI.size(520, 340) }
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
