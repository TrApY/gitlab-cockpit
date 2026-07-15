package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabPipeline
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.StageGroup
import dev.jota.gitlabcockpit.core.groupByStage
import dev.jota.gitlabcockpit.core.isJobCancelable
import dev.jota.gitlabcockpit.core.isJobPlayable
import dev.jota.gitlabcockpit.core.isJobRetryable
import dev.jota.gitlabcockpit.core.mergeHeadPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.math.roundToLong

/** Tree node payload for a pipeline stage. */
private data class StageNodeData(val stage: StageGroup)

/** Tree node payload for a single CI job. */
private data class JobNodeData(val job: GitLabJob)

/**
 * The "Pipelines" tab of the MR detail. Shows the pipelines a merge request has triggered and lets
 * the user drive them:
 *
 * - a combo of the MR's pipelines (`#id · status · ref · when`) plus refresh and "Run pipeline"
 *   (which creates a pipeline on the MR's source branch, after a confirmation),
 * - a horizontal strip of stage dots colored by each stage's aggregated status,
 * - a stage → job tree with per-status icons and job durations; failed stages are auto-expanded,
 * - a toolbar to retry / cancel the selected pipeline and a right-click menu to retry a stage's
 *   failed jobs or retry / cancel / play / open a single job.
 *
 * All network calls run on the service's coroutine scope (never the EDT); results are marshaled back
 * with [Dispatchers.EDT] and dropped when stale (re-checking [currentRef] and the selected pipeline).
 * Pipelines load lazily the first time the tab is shown for an MR ([onTabSelected]) and again after
 * every detail refresh ([setMr]).
 */
class PipelinesPanel(
    private val project: Project,
    private val service: CockpitProjectService,
) : JPanel(BorderLayout()) {

    /** Ref of the MR currently displayed; null when cleared. */
    var currentRef: MrRef? = null
        private set

    private var sourceBranch: String? = null

    /**
     * The MR's head pipeline (from the detail endpoint), merged into the loaded list so externally
     * reported pipelines that `/pipelines` omits (e.g. Jenkins) still appear. Null when unknown.
     */
    private var headPipeline: GitLabPipeline? = null

    /** The ref whose pipelines have been loaded, so the tab only reloads when it changes. */
    private var loadedForRef: MrRef? = null

    /** The pipeline whose jobs are shown (or loading); used to drop stale job loads. */
    private var selectedPipelineId: Long? = null

    /** After a pipeline reload, the pipeline id to reselect (null → newest, i.e. index 0). */
    private var pendingSelectPipelineId: Long? = null

    /** Suppresses combo action events while the combo is repopulated programmatically. */
    private var suppressComboEvents = false

    private var pipelinesJob: Job? = null
    private var jobsJob: Job? = null
    private var actionJob: Job? = null

    private val pipelineCombo = ComboBox<GitLabPipeline>().apply {
        renderer = SimpleListCellRenderer.create("") { pipelineLabel(it) }
    }

    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = CockpitBundle.message("pipelines.refresh")
    }

    private val runButton = JButton(CockpitBundle.message("pipelines.run"))
    private val retryPipelineButton = JButton(CockpitBundle.message("pipelines.retryPipeline"))
    private val cancelPipelineButton = JButton(CockpitBundle.message("pipelines.cancelPipeline"))

    private val stageStrip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { isOpaque = false }

    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = PipeTreeRenderer()
    }

    init {
        add(buildNorth(), BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        refreshButton.addActionListener { currentRef?.let { loadPipelines(it) } }
        runButton.addActionListener { onRunPipeline() }
        retryPipelineButton.addActionListener { onRetryPipeline() }
        cancelPipelineButton.addActionListener { onCancelPipeline() }
        pipelineCombo.addActionListener { if (!suppressComboEvents) onPipelineSelected() }

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                buildContextMenu(node)?.show(comp, x, y)
            }
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
                return when (val data = node.userObject) {
                    is JobNodeData -> { openJobLog(data.job); true }
                    is StageNodeData -> { openStageLogs(data.stage); true }
                    else -> false
                }
            }
        }.installOn(tree)

        clear()
    }

    private fun buildNorth(): JComponent {
        val north = JPanel(VerticalLayout(JBUI.scale(4)))
        north.border = JBUI.Borders.empty(6, 8)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        controls.add(pipelineCombo)
        controls.add(refreshButton)
        controls.add(runButton)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        actions.add(retryPipelineButton)
        actions.add(cancelPipelineButton)

        north.add(controls)
        north.add(actions)
        north.add(stageStrip)
        return north
    }

    // --- Lifecycle called by MrDetailPanel ----------------------------------------------------

    /**
     * Binds this tab to [iid] / [branch] and marks the pipelines as needing a (re)load. [headPipeline]
     * is the MR detail's `head_pipeline`, folded into the loaded list by [loadPipelines] so external
     * pipelines still show even when `/pipelines` returns nothing.
     */
    fun setMr(ref: MrRef, branch: String, headPipeline: GitLabPipeline?) {
        currentRef = ref
        sourceBranch = branch
        this.headPipeline = headPipeline
        loadedForRef = null
        selectedPipelineId = null
        pipelinesJob?.cancel()
        jobsJob?.cancel()
        actionJob?.cancel()
        clearContent()
        runButton.isEnabled = true
        refreshButton.isEnabled = true
        tree.emptyText.text = ""
    }

    /** Resets to the empty placeholder (no MR selected). */
    fun clear() {
        currentRef = null
        sourceBranch = null
        headPipeline = null
        loadedForRef = null
        selectedPipelineId = null
        pipelinesJob?.cancel()
        jobsJob?.cancel()
        actionJob?.cancel()
        clearContent()
        runButton.isEnabled = false
        refreshButton.isEnabled = false
        tree.emptyText.text = ""
    }

    /** Called when the Pipelines tab becomes visible; loads pipelines the first time per MR. */
    fun onTabSelected() {
        val ref = currentRef ?: return
        if (loadedForRef != ref) loadPipelines(ref)
    }

    // --- Loading ------------------------------------------------------------------------------

    private fun loadPipelines(ref: MrRef, preservePipelineId: Long? = null) {
        loadedForRef = ref
        pendingSelectPipelineId = preservePipelineId
        runButton.isEnabled = true
        refreshButton.isEnabled = true
        clearContent()
        tree.emptyText.text = CockpitBundle.message("pipelines.loading")
        pipelinesJob?.cancel()
        pipelinesJob = service.coroutineScope.launch {
            val result = service.getMrPipelines(ref)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> renderPipelines(mergeHeadPipeline(result.data, headPipeline))
                    else -> {
                        loadedForRef = null
                        tree.emptyText.text = CockpitBundle.message("pipelines.error.pipelines", describe(result))
                    }
                }
            }
        }
    }

    /** EDT. Fills the combo and selects the target pipeline, then loads its jobs. */
    private fun renderPipelines(pipelines: List<GitLabPipeline>) {
        if (pipelines.isEmpty()) {
            clearContent()
            tree.emptyText.text = CockpitBundle.message("pipelines.empty")
            return
        }
        suppressComboEvents = true
        pipelineCombo.removeAllItems()
        pipelines.forEach { pipelineCombo.addItem(it) }
        pipelineCombo.isEnabled = true
        val target = pendingSelectPipelineId?.let { id -> pipelines.firstOrNull { it.id == id } } ?: pipelines.first()
        pipelineCombo.selectedItem = target
        suppressComboEvents = false
        pendingSelectPipelineId = null
        onPipelineSelected()
    }

    /** EDT. Loads the jobs of the currently selected pipeline. */
    private fun onPipelineSelected() {
        val pipeline = pipelineCombo.selectedItem as? GitLabPipeline
        if (pipeline == null) {
            selectedPipelineId = null
            return
        }
        selectedPipelineId = pipeline.id
        loadJobs(pipeline)
    }

    private fun loadJobs(pipeline: GitLabPipeline) {
        val ref = currentRef ?: return
        rootNode.removeAllChildren()
        treeModel.reload()
        stageStrip.removeAll()
        stageStrip.revalidate()
        stageStrip.repaint()
        tree.emptyText.text = CockpitBundle.message("pipelines.jobs.loading")
        updatePipelineButtons(pipeline.status)
        jobsJob?.cancel()
        jobsJob = service.coroutineScope.launch {
            val result = service.getPipelineJobs(ref.projectId, pipeline.id)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref || selectedPipelineId != pipeline.id) return@withContext
                when (result) {
                    is GitLabResult.Success -> renderJobs(result.data)
                    else -> tree.emptyText.text = CockpitBundle.message("pipelines.error.jobs", describe(result))
                }
            }
        }
    }

    /** EDT. Builds the stage → job tree and the stage strip, auto-expanding failed stages. */
    private fun renderJobs(jobs: List<GitLabJob>) {
        val stages = groupByStage(jobs)

        rootNode.removeAllChildren()
        val failedStagePaths = mutableListOf<TreePath>()
        for (stage in stages) {
            val stageNode = DefaultMutableTreeNode(StageNodeData(stage))
            for (job in stage.jobs) stageNode.add(DefaultMutableTreeNode(JobNodeData(job)))
            rootNode.add(stageNode)
            if (stage.status == "failed") failedStagePaths.add(TreePath(arrayOf<Any>(rootNode, stageNode)))
        }
        treeModel.reload()
        failedStagePaths.forEach { tree.expandPath(it) }
        tree.emptyText.text = if (jobs.isEmpty()) CockpitBundle.message("pipelines.jobs.empty") else ""

        renderStageStrip(stages)
    }

    private fun renderStageStrip(stages: List<StageGroup>) {
        stageStrip.removeAll()
        for (stage in stages) {
            val label = JBLabel("\u25CF " + stage.name).apply {
                foreground = colorForStatus(stage.status)
                toolTipText = stage.status
            }
            stageStrip.add(label)
        }
        stageStrip.revalidate()
        stageStrip.repaint()
    }

    private fun updatePipelineButtons(status: String) {
        retryPipelineButton.isEnabled = isJobRetryable(status)
        cancelPipelineButton.isEnabled = isJobCancelable(status)
    }

    private fun clearContent() {
        suppressComboEvents = true
        pipelineCombo.removeAllItems()
        suppressComboEvents = false
        pipelineCombo.isEnabled = false
        stageStrip.removeAll()
        stageStrip.revalidate()
        stageStrip.repaint()
        rootNode.removeAllChildren()
        treeModel.reload()
        retryPipelineButton.isEnabled = false
        cancelPipelineButton.isEnabled = false
    }

    // --- Actions ------------------------------------------------------------------------------

    private fun onRunPipeline() {
        val branch = sourceBranch ?: return
        val ref = currentRef ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            CockpitBundle.message("pipelines.run.confirm", branch),
            CockpitBundle.message("pipelines.run.title"),
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return
        actionJob?.cancel()
        actionJob = service.coroutineScope.launch {
            val result = service.createPipeline(ref.projectId, branch)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadPipelines(ref) // newest pipeline selected
                    else -> showActionError(result)
                }
            }
        }
    }

    private fun onRetryPipeline() {
        val ref = currentRef ?: return
        val pipeline = pipelineCombo.selectedItem as? GitLabPipeline ?: return
        actionJob?.cancel()
        actionJob = service.coroutineScope.launch {
            val result = service.retryPipeline(ref.projectId, pipeline.id)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadPipelines(ref, preservePipelineId = pipeline.id)
                    else -> showActionError(result)
                }
            }
        }
    }

    private fun onCancelPipeline() {
        val ref = currentRef ?: return
        val pipeline = pipelineCombo.selectedItem as? GitLabPipeline ?: return
        actionJob?.cancel()
        actionJob = service.coroutineScope.launch {
            val result = service.cancelPipeline(ref.projectId, pipeline.id)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> loadPipelines(ref, preservePipelineId = pipeline.id)
                    else -> showActionError(result)
                }
            }
        }
    }

    private fun onRetryJob(job: GitLabJob) {
        val ref = currentRef ?: return
        runJobAction { service.retryJob(ref.projectId, job.id) }
    }

    private fun onCancelJob(job: GitLabJob) {
        val ref = currentRef ?: return
        runJobAction { service.cancelJob(ref.projectId, job.id) }
    }

    private fun onPlayJob(job: GitLabJob) {
        val ref = currentRef ?: return
        runJobAction { service.playJob(ref.projectId, job.id) }
    }

    /** Runs a single-job action, then reloads the current pipeline's jobs on success. */
    private fun runJobAction(action: suspend () -> GitLabResult<Unit>) {
        val ref = currentRef ?: return
        actionJob?.cancel()
        actionJob = service.coroutineScope.launch {
            val result = action()
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                when (result) {
                    is GitLabResult.Success -> reloadJobs()
                    else -> showActionError(result)
                }
            }
        }
    }

    private fun onRetryStage(stage: StageGroup) {
        val ref = currentRef ?: return
        val pipelineId = selectedPipelineId ?: return
        actionJob?.cancel()
        actionJob = service.coroutineScope.launch {
            val result = service.retryStage(ref.projectId, pipelineId, stage)
            withContext(Dispatchers.EDT) {
                if (currentRef != ref) return@withContext
                val message = when {
                    result.retried == 0 && result.firstError == null ->
                        CockpitBundle.message("pipelines.retryStage.none", stage.name)
                    result.firstError != null ->
                        CockpitBundle.message(
                            "pipelines.retryStage.resultWithError",
                            result.retried,
                            stage.name,
                            result.firstError,
                        )
                    else ->
                        CockpitBundle.message("pipelines.retryStage.result", result.retried, stage.name)
                }
                Messages.showInfoMessage(project, message, CockpitBundle.message("pipelines.retryStage.title"))
                if (result.retried > 0) reloadJobs()
            }
        }
    }

    private fun reloadJobs() {
        val pipeline = pipelineCombo.selectedItem as? GitLabPipeline ?: return
        loadJobs(pipeline)
    }

    private fun showActionError(result: GitLabResult<*>) {
        Messages.showErrorDialog(
            project,
            CockpitBundle.message("pipelines.error.action", describe(result)),
            CockpitBundle.message("detail.error.title"),
        )
    }

    /** Opens the non-modal streaming log viewer for [job]. */
    private fun openJobLog(job: GitLabJob) {
        val ref = currentRef ?: return
        JobLogDialog(project, service, ref.projectId, job).show()
    }

    /**
     * Opens the log viewer for a whole [stage]: a single-job stage reuses [openJobLog], a multi-job
     * stage opens the tabbed [StageLogsDialog]. An empty stage does nothing.
     */
    private fun openStageLogs(stage: StageGroup) {
        val ref = currentRef ?: return
        when {
            stage.jobs.size == 1 -> openJobLog(stage.jobs.first())
            stage.jobs.isNotEmpty() -> StageLogsDialog(project, service, ref.projectId, stage).show()
        }
    }

    // --- Context menu -------------------------------------------------------------------------

    private fun buildContextMenu(node: DefaultMutableTreeNode): JPopupMenu? = when (val data = node.userObject) {
        is StageNodeData -> JPopupMenu().apply {
            add(
                JMenuItem(CockpitBundle.message("pipelines.stage.viewLogs")).apply {
                    addActionListener { openStageLogs(data.stage) }
                },
            )
            addSeparator()
            add(
                JMenuItem(CockpitBundle.message("pipelines.retryStage")).apply {
                    addActionListener { onRetryStage(data.stage) }
                },
            )
        }
        is JobNodeData -> {
            val job = data.job
            JPopupMenu().apply {
                add(
                    JMenuItem(CockpitBundle.message("log.viewLog")).apply {
                        addActionListener { openJobLog(job) }
                    },
                )
                addSeparator()
                add(
                    JMenuItem(CockpitBundle.message("pipelines.job.retry")).apply {
                        isEnabled = isJobRetryable(job.status)
                        addActionListener { onRetryJob(job) }
                    },
                )
                add(
                    JMenuItem(CockpitBundle.message("pipelines.job.cancel")).apply {
                        isEnabled = isJobCancelable(job.status)
                        addActionListener { onCancelJob(job) }
                    },
                )
                add(
                    JMenuItem(CockpitBundle.message("pipelines.job.play")).apply {
                        isEnabled = isJobPlayable(job.status)
                        addActionListener { onPlayJob(job) }
                    },
                )
                addSeparator()
                add(
                    JMenuItem(CockpitBundle.message("pipelines.job.open")).apply {
                        addActionListener { BrowserUtil.browse(job.webUrl) }
                    },
                )
            }
        }
        else -> null
    }

    // --- Tree cell rendering ------------------------------------------------------------------

    private inner class PipeTreeRenderer : ColoredTreeCellRenderer() {
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
            when (val data = node.userObject) {
                is StageNodeData -> {
                    icon = iconForStatus(data.stage.status)
                    append(data.stage.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  (${data.stage.jobs.size})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is JobNodeData -> {
                    val job = data.job
                    icon = jobIcon(job)
                    append(job.name)
                    job.duration?.let { append("  ${formatDuration(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
            }
        }
    }

    companion object {
        /**
         * `#id · status · ref · when` for the pipeline combo. The `ref` segment is dropped entirely
         * when the pipeline has no ref (external pipelines), so no doubled separator is left behind.
         */
        private fun pipelineLabel(p: GitLabPipeline): String {
            val dot = " \u00B7 "
            val when0 = formatRelative(p.updatedAt ?: p.createdAt ?: "")
            val parts = buildList {
                add("#${p.id}")
                add(p.status)
                p.ref?.let { add(it) }
                add(when0)
            }
            return parts.joinToString(dot)
        }

        /** Maps a job or aggregated stage status to its status icon. */
        private fun iconForStatus(status: String): Icon = when (status) {
            "success" -> AllIcons.RunConfigurations.TestState.Green2
            "failed" -> AllIcons.RunConfigurations.TestState.Red2
            "running" -> AnimatedIcon.Default()
            "warning" -> AllIcons.General.Warning
            "manual" -> AllIcons.Actions.Pause
            "canceled" -> AllIcons.Actions.Suspend
            else -> AllIcons.RunConfigurations.TestNotRan // pending / created / skipped / unknown
        }

        /** Like [iconForStatus] but a failed job that is allowed to fail shows the warning icon. */
        private fun jobIcon(job: GitLabJob): Icon =
            if (job.status == "failed" && job.allowFailure) AllIcons.General.Warning else iconForStatus(job.status)

        /** Maps a job or aggregated stage status to its dot/label color. */
        private fun colorForStatus(status: String): JBColor = when (status) {
            "success" -> JBColor.GREEN
            "failed" -> JBColor.RED
            "running" -> JBColor.BLUE
            "warning" -> JBColor.ORANGE
            "canceled" -> JBColor.DARK_GRAY
            "manual", "skipped" -> JBColor.LIGHT_GRAY
            else -> JBColor.GRAY // pending / created / unknown
        }

        /** Formats a duration in seconds as `Xm Ys` (or `Ys` under a minute). */
        private fun formatDuration(seconds: Double): String {
            val total = seconds.roundToLong()
            val minutes = total / 60
            val secs = total % 60
            return if (minutes > 0) "${minutes}m ${secs}s" else "${secs}s"
        }

        private fun describe(result: GitLabResult<*>): String = when (result) {
            is GitLabResult.HttpError -> "HTTP ${result.status}"
            is GitLabResult.NetworkError -> result.cause.message ?: result.cause.javaClass.simpleName
            is GitLabResult.Success<*> -> ""
        }
    }
}
