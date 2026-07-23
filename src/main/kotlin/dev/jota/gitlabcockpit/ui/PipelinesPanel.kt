package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
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
import dev.jota.gitlabcockpit.core.PipelineRow
import dev.jota.gitlabcockpit.core.StageGroup
import dev.jota.gitlabcockpit.core.aggregateStatus
import dev.jota.gitlabcockpit.core.compactStageRow
import dev.jota.gitlabcockpit.core.compactStages
import dev.jota.gitlabcockpit.core.groupByStage
import dev.jota.gitlabcockpit.core.isJobCancelable
import dev.jota.gitlabcockpit.core.isJobPlayable
import dev.jota.gitlabcockpit.core.isJobRetryable
import dev.jota.gitlabcockpit.core.isPipelineLive
import dev.jota.gitlabcockpit.core.mergeHeadPipeline
import dev.jota.gitlabcockpit.core.stagesToExpand
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import dev.jota.gitlabcockpit.ui.log.JobLogVirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
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

/** Tree node payload for a single-job stage flattened to one `stage · job` row (GLC-59). */
private data class FlatStageNodeData(val stage: StageGroup) {
    /** The stage's single job; null only if a malformed stage ever arrives without jobs. */
    val job: GitLabJob? get() = stage.jobs.firstOrNull()
}

/** Tree node payload for the "N stages passed (M jobs)" summary row (GLC-59). */
private data class SummaryNodeData(val summary: PipelineRow.Summary)

/**
 * What the tree had selected before an in-place rebuild, reduced to identities that survive it
 * (GLC-59): a job id (job rows and flattened `stage · job` rows), a stage name (stage rows and
 * flattened rows, as the fallback when the job disappeared) and whether the summary row itself was
 * selected.
 */
private data class SelectionSnapshot(val jobId: Long?, val stageName: String?, val summary: Boolean)

/**
 * The "Pipelines" tab of the MR detail. Shows the pipelines a merge request has triggered and lets
 * the user drive them:
 *
 * - a combo of the MR's pipelines (`#id · status · ref · when`) plus refresh and "Run pipeline"
 *   (which creates a pipeline on the MR's source branch, after a confirmation),
 * - a horizontal strip of stage dots colored by each stage's aggregated status,
 * - a compact, attention-first stage tree (GLC-59): stages that need attention (failed / running /
 *   pending / manual / canceled / warning) show as individual rows — flattened to a single
 *   `stage · job` line when they hold one job — while fully successful stages fold into one
 *   collapsed "N stages passed (M jobs)" summary row placed last; failed multi-job stages are
 *   auto-expanded. A persisted "Show all stages" checkbox ([GitLabCockpitSettings]) restores the
 *   classic stage → job tree (no summary, no flattening), re-rendering in place without a re-fetch,
 * - a toolbar to retry / cancel the selected pipeline and a right-click menu to retry a stage's
 *   failed jobs or retry / cancel / play / open a single job (flattened rows get the job menu).
 *
 * All network calls run on the service's coroutine scope (never the EDT); results are marshaled back
 * with [Dispatchers.EDT] and dropped when stale (re-checking [currentRef] and the selected pipeline).
 * Pipelines load lazily the first time the tab is shown for an MR ([onTabSelected]) and again after
 * every detail refresh ([setMr]).
 *
 * **Live status (GLC-43 B).** While the card is showing ([onCardShown] / [onCardHidden] driven by the
 * detail panel's card switch, plus a [HierarchyEvent.SHOWING_CHANGED] guard for the tool window being
 * hidden) and the selected pipeline is still alive ([isPipelineLive]), a poll every
 * [POLL_INTERVAL_MS]ms reloads that pipeline's jobs — and the pipeline list every
 * [PIPELINE_LIST_EVERY] cycles — and updates the combo / strip / tree **in place**, preserving the
 * tree's expansion (per-stage via [stagesToExpand], plus whether the summary row was open) and the
 * current selection ([SelectionSnapshot]). The loop stops the moment
 * the card is hidden, the panel is unbound/cleared, the tool window stops showing, or the pipeline
 * turns terminal (one last pass first). When the polled pipeline is the head one and its aggregate
 * status changes, [onHeadPipelineStatusChange] lets the detail's Info "Pipeline status" line follow.
 *
 * @param onHeadPipelineStatusChange invoked (EDT) with the head pipeline's new aggregate status when a
 *   poll detects it changed; used to refresh the Info card's pipeline line.
 */
class PipelinesPanel(
    private val project: Project,
    private val service: CockpitProjectService,
    private val onHeadPipelineStatusChange: (String) -> Unit = {},
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

    /** The live-status poll loop (GLC-43 B); non-null/active only while the card is visibly polling. */
    private var pollJob: Job? = null

    /** Whether the pipelines card is the one currently shown (set by [onCardShown] / [onCardHidden]). */
    private var cardVisible = false

    /** The jobs last rendered into the tree; the poll's start guard reads their liveness. */
    private var lastRenderedJobs: List<GitLabJob> = emptyList()

    /** Last head-pipeline aggregate status reported to the detail, so only real changes fire the callback. */
    private var lastHeadAggregate: String? = null

    private val pipelineCombo = ComboBox<GitLabPipeline>().apply {
        renderer = textCellRenderer<GitLabPipeline>("") { pipelineLabel(it) }
    }

    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = CockpitBundle.message("pipelines.refresh")
    }

    private val runButton = JButton(CockpitBundle.message("pipelines.run"))
    private val retryPipelineButton = JButton(CockpitBundle.message("pipelines.retryPipeline"))
    private val cancelPipelineButton = JButton(CockpitBundle.message("pipelines.cancelPipeline"))

    /**
     * The persisted "Show all stages" view toggle (GLC-59). A plain checkbox rather than a
     * [com.intellij.openapi.actionSystem.ToggleAction]: this panel composes plain Swing buttons (no
     * `ActionToolbar`), so a labeled checkbox on the actions row reads as the native "view option"
     * here — self-explanatory, stateful at a glance and consistent with the row's idiom.
     */
    private val showAllStagesCheckBox = JBCheckBox(CockpitBundle.message("pipelines.showAllStages")).apply {
        isOpaque = false
        toolTipText = CockpitBundle.message("pipelines.showAllStages.tooltip")
        isSelected = GitLabCockpitSettings.getInstance().pipelinesShowAllStages
    }

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
        showAllStagesCheckBox.addActionListener { onShowAllStagesToggled() }
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
                    is FlatStageNodeData -> { data.job?.let { openJobLog(it) }; true }
                    is StageNodeData -> { openStageLogs(data.stage); true }
                    else -> false // summary row: keep the tree's default expand/collapse toggle
                }
            }
        }.installOn(tree)

        // GLC-43 B: no timer may survive the tool window being hidden. When this panel stops showing,
        // the poll loop is cancelled; when it shows again it resumes if the card is up and still alive.
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing) maybeStartPolling() else stopPolling()
            }
        }

        clear()
    }

    private fun buildNorth(): JComponent {
        val north = JPanel(VerticalLayout(JBUI.scale(4)))
        north.border = CockpitTheme.panelBorder()

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        controls.add(pipelineCombo)
        controls.add(refreshButton)
        controls.add(runButton)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        actions.add(retryPipelineButton)
        actions.add(cancelPipelineButton)
        actions.add(showAllStagesCheckBox)

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
        stopPolling()
        lastRenderedJobs = emptyList()
        lastHeadAggregate = headPipeline?.status
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
        stopPolling()
        lastRenderedJobs = emptyList()
        lastHeadAggregate = null
        pipelinesJob?.cancel()
        jobsJob?.cancel()
        actionJob?.cancel()
        clearContent()
        runButton.isEnabled = false
        refreshButton.isEnabled = false
        tree.emptyText.text = ""
    }

    // --- Live status polling (GLC-43 B) -------------------------------------------------------

    /** Called by the detail panel when the pipelines card becomes the shown one; may start polling. */
    fun onCardShown() {
        cardVisible = true
        maybeStartPolling()
    }

    /** Called by the detail panel when the pipelines card is hidden (Back / another card); stops polling. */
    fun onCardHidden() {
        cardVisible = false
        stopPolling()
    }

    /** Cancels the live-status poll loop; a no-op when nothing is polling. */
    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Starts the poll loop when everything lines up: the card is the shown one, the panel is actually
     * showing on screen, a pipeline is selected and the last rendered jobs are still alive (or not yet
     * loaded — the first pass then decides). Idempotent: a poll already running is left alone.
     */
    private fun maybeStartPolling() {
        if (!cardVisible || !isShowing) return
        if (pollJob?.isActive == true) return
        val pipeline = pipelineCombo.selectedItem as? GitLabPipeline ?: return
        if (lastRenderedJobs.isNotEmpty() && !isPipelineLive(lastRenderedJobs)) return
        startPollingLoop(pipeline.id)
    }

    /**
     * The 5-second poll loop for [pipelineId]. Each cycle reloads that pipeline's jobs (and, every
     * [PIPELINE_LIST_EVERY] cycles, the pipeline list) off the EDT and applies them in place on the EDT;
     * it stops when the panel became stale/hidden or the pipeline turned terminal (after that final
     * pass). A transient job-load error keeps the loop alive for the next cycle.
     */
    private fun startPollingLoop(pipelineId: Long) {
        pollJob?.cancel()
        pollJob = service.coroutineScope.launch {
            var cycle = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                cycle++
                val ref = currentRef ?: break
                if (selectedPipelineId != pipelineId) break
                if (cycle % PIPELINE_LIST_EVERY == 0) {
                    val listResult = service.getMrPipelines(ref)
                    withContext(Dispatchers.EDT) {
                        if (currentRef == ref && selectedPipelineId == pipelineId &&
                            listResult is GitLabResult.Success
                        ) {
                            refreshPipelinesInPlace(mergeHeadPipeline(listResult.data, headPipeline))
                        }
                    }
                }
                val jobsResult = service.getPipelineJobs(ref.projectId, pipelineId)
                val stop = withContext(Dispatchers.EDT) {
                    if (currentRef != ref || selectedPipelineId != pipelineId) return@withContext true
                    if (!cardVisible || !isShowing) return@withContext true
                    when (jobsResult) {
                        is GitLabResult.Success -> {
                            refreshJobsInPlace(jobsResult.data)
                            maybeReportHeadStatus(pipelineId, jobsResult.data)
                            !isPipelineLive(jobsResult.data)
                        }
                        else -> false
                    }
                }
                if (stop) break
            }
        }
    }

    /** EDT. Fires [onHeadPipelineStatusChange] when the polled head pipeline's aggregate status changed. */
    private fun maybeReportHeadStatus(pipelineId: Long, jobs: List<GitLabJob>) {
        if (pipelineId != headPipeline?.id) return
        val aggregate = aggregateStatus(jobs)
        if (aggregate != lastHeadAggregate) {
            lastHeadAggregate = aggregate
            onHeadPipelineStatusChange(aggregate)
        }
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
        // A (re)load re-targets the tree; cancel any live poll — renderJobs restarts it for this pipeline.
        stopPolling()
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

    /**
     * EDT. First render of a pipeline's jobs: builds the compact row tree ([compactStages]) and the
     * stage strip, auto-expanding failed multi-job stages — the summary row starts collapsed — and
     * resets the selection. Records the jobs and (re)starts live polling if the card is up and the
     * pipeline is still alive.
     */
    private fun renderJobs(jobs: List<GitLabJob>) {
        val stages = groupByStage(jobs)
        rebuildRows(compactStages(stages, showAllStages()))
        treeModel.reload()
        applyExpansion(stagesToExpand(emptySet(), stages), summaryExpanded = false)
        tree.emptyText.text = if (jobs.isEmpty()) CockpitBundle.message("pipelines.jobs.empty") else ""
        renderStageStrip(stages)
        lastRenderedJobs = jobs
        maybeStartPolling()
    }

    /**
     * EDT. In-place refresh of the selected pipeline's jobs (GLC-43 B): rebuilds the tree/strip while
     * **preserving** the expansion the user had — per-stage names folded with the failed auto-expand
     * rule by [stagesToExpand], plus whether the summary row was open — and the current selection
     * ([SelectionSnapshot]: by job id, else stage name, else the summary row itself). Never touches
     * the combo or restarts polling — that is the loop's job.
     */
    private fun refreshJobsInPlace(jobs: List<GitLabJob>) {
        val stages = groupByStage(jobs)
        val previouslyExpanded = expandedStageNames()
        val summaryWasExpanded = isSummaryExpanded()
        val selection = selectionSnapshot()
        rebuildRows(compactStages(stages, showAllStages()))
        treeModel.reload()
        applyExpansion(stagesToExpand(previouslyExpanded, stages), summaryWasExpanded)
        restoreSelection(selection)
        tree.emptyText.text = if (jobs.isEmpty()) CockpitBundle.message("pipelines.jobs.empty") else ""
        renderStageStrip(stages)
        lastRenderedJobs = jobs
    }

    /** EDT. In-place refresh of the pipeline combo (GLC-43 B): keeps the selected pipeline by id. */
    private fun refreshPipelinesInPlace(pipelines: List<GitLabPipeline>) {
        if (pipelines.isEmpty()) return
        val selected = selectedPipelineId
        suppressComboEvents = true
        pipelineCombo.removeAllItems()
        pipelines.forEach { pipelineCombo.addItem(it) }
        val target = pipelines.firstOrNull { it.id == selected } ?: pipelines.first()
        pipelineCombo.selectedItem = target
        suppressComboEvents = false
        updatePipelineButtons(target.status)
    }

    /** The persisted "Show all stages" flag the render paths read (GLC-59). */
    private fun showAllStages(): Boolean = GitLabCockpitSettings.getInstance().pipelinesShowAllStages

    /**
     * EDT. Persists the toggled "Show all stages" value and re-renders the current pipeline's rows
     * immediately from [lastRenderedJobs] — no re-fetch — through [refreshJobsInPlace], so expansion
     * and selection carry over between the compact and the classic view where they still apply.
     * Nothing rendered yet (no pipeline, jobs still loading) → nothing to re-render.
     */
    private fun onShowAllStagesToggled() {
        GitLabCockpitSettings.getInstance().pipelinesShowAllStages = showAllStagesCheckBox.isSelected
        if (lastRenderedJobs.isNotEmpty()) refreshJobsInPlace(lastRenderedJobs)
    }

    /** EDT. Rebuilds the row nodes under the (cleared) root from [rows]; no reload/expansion. */
    private fun rebuildRows(rows: List<PipelineRow>) {
        rootNode.removeAllChildren()
        for (row in rows) rootNode.add(rowNode(row))
    }

    /**
     * The subtree one [PipelineRow] renders as: a flattened single-row stage, a stage node with its
     * job children, or the summary node whose children are its stages re-shaped by the same
     * [compactStageRow] flattening rule (never a nested summary, so the recursion is one level deep).
     */
    private fun rowNode(row: PipelineRow): DefaultMutableTreeNode = when (row) {
        is PipelineRow.FlatStage -> DefaultMutableTreeNode(FlatStageNodeData(row.stage))
        is PipelineRow.Stage -> DefaultMutableTreeNode(StageNodeData(row.stage)).also { node ->
            for (job in row.stage.jobs) node.add(DefaultMutableTreeNode(JobNodeData(job)))
        }
        is PipelineRow.Summary -> DefaultMutableTreeNode(SummaryNodeData(row)).also { node ->
            for (stage in row.stages) node.add(rowNode(compactStageRow(stage)))
        }
    }

    /**
     * EDT. Applies the expansion state after a rebuild: re-opens the summary row when
     * [summaryExpanded], then expands every stage node whose name is in [names]. Stage nodes living
     * *inside* a collapsed summary are deliberately left alone — [Tree.expandPath] expands the whole
     * parent chain, which would pop the summary open the moment a previously expanded stage turns
     * green and folds into it, defeating the collapse the compact view exists for.
     */
    private fun applyExpansion(names: Set<String>, summaryExpanded: Boolean) {
        val summary = summaryNode()
        if (summaryExpanded && summary != null) tree.expandPath(TreePath(summary.path))
        forEachNode { node ->
            val stage = (node.userObject as? StageNodeData)?.stage ?: return@forEachNode
            if (stage.name !in names) return@forEachNode
            if (summary != null && node.parent === summary && !summaryExpanded) return@forEachNode
            tree.expandPath(TreePath(node.path))
        }
    }

    /**
     * EDT. The names of the stage nodes currently expanded in the tree, at any depth. A stage inside
     * a collapsed summary reports as not expanded ([Tree.isExpanded] is false for hidden paths),
     * matching what the user actually sees.
     */
    private fun expandedStageNames(): Set<String> {
        val result = mutableSetOf<String>()
        forEachNode { node ->
            val stage = (node.userObject as? StageNodeData)?.stage ?: return@forEachNode
            if (tree.isExpanded(TreePath(node.path))) result.add(stage.name)
        }
        return result
    }

    /** The summary row's node, when the compact view rendered one; null in show-all mode. */
    private fun summaryNode(): DefaultMutableTreeNode? {
        for (index in 0 until rootNode.childCount) {
            val node = rootNode.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            if (node.userObject is SummaryNodeData) return node
        }
        return null
    }

    /** EDT. Whether the summary row exists and is expanded; the boolean [refreshJobsInPlace] preserves. */
    private fun isSummaryExpanded(): Boolean =
        summaryNode()?.let { tree.isExpanded(TreePath(it.path)) } ?: false

    /** EDT. Captures the current selection as refresh-stable identities; see [SelectionSnapshot]. */
    private fun selectionSnapshot(): SelectionSnapshot =
        when (val data = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is JobNodeData -> SelectionSnapshot(data.job.id, null, summary = false)
            is FlatStageNodeData -> SelectionSnapshot(data.job?.id, data.stage.name, summary = false)
            is StageNodeData -> SelectionSnapshot(null, data.stage.name, summary = false)
            is SummaryNodeData -> SelectionSnapshot(null, null, summary = true)
            else -> SelectionSnapshot(null, null, summary = false)
        }

    /**
     * EDT. Reselects what [snapshot] recorded: the summary row, else the job by id (whether it is now
     * a job row or a flattened `stage · job` row), else the stage by name (stage or flattened row).
     * A target that folded into a *collapsed* summary since the snapshot selects the summary row
     * instead — selecting the hidden node would leave no visible selection at all.
     */
    private fun restoreSelection(snapshot: SelectionSnapshot) {
        val target = when {
            snapshot.summary -> summaryNode()
            else ->
                snapshot.jobId?.let { id -> findNode { nodeJobId(it) == id } }
                    ?: snapshot.stageName?.let { name -> findNode { nodeStageName(it) == name } }
        } ?: return
        val summary = summaryNode()
        val visibleTarget =
            if (summary != null && target !== summary && target.isNodeAncestor(summary) &&
                !tree.isExpanded(TreePath(summary.path))
            ) {
                summary
            } else {
                target
            }
        tree.selectionPath = TreePath(visibleTarget.path)
    }

    /** The job id a node stands for: a job row's own, or a flattened `stage · job` row's single job. */
    private fun nodeJobId(node: DefaultMutableTreeNode): Long? = when (val data = node.userObject) {
        is JobNodeData -> data.job.id
        is FlatStageNodeData -> data.job?.id
        else -> null
    }

    /** The stage name a node stands for: a stage row's, or a flattened `stage · job` row's. */
    private fun nodeStageName(node: DefaultMutableTreeNode): String? = when (val data = node.userObject) {
        is StageNodeData -> data.stage.name
        is FlatStageNodeData -> data.stage.name
        else -> null
    }

    /** Top-down (preorder) search for the first node at any depth matching [match]. */
    private fun findNode(match: (DefaultMutableTreeNode) -> Boolean): DefaultMutableTreeNode? {
        val nodes = rootNode.preorderEnumeration()
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as? DefaultMutableTreeNode ?: continue
            if (node !== rootNode && match(node)) return node
        }
        return null
    }

    /** Runs [action] on every node under the root, at any depth, in top-down (preorder) order. */
    private fun forEachNode(action: (DefaultMutableTreeNode) -> Unit) {
        val nodes = rootNode.preorderEnumeration()
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as? DefaultMutableTreeNode ?: continue
            if (node !== rootNode) action(node)
        }
    }

    private fun renderStageStrip(stages: List<StageGroup>) {
        stageStrip.removeAll()
        for (stage in stages) {
            val label = JBLabel("\u25CF " + stage.name).apply {
                foreground = CockpitTheme.statusColor(stage.status)
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

    /**
     * Opens [job]'s streaming log as an editor tab (GLC-43 A): a read-only [JobLogVirtualFile] handed to
     * the [FileEditorManager], whose [JobLogFileEditor] wraps the reused [JobLogConsole]. Opening the
     * same job again reuses its tab (the file's identity is the job id), and the tab outlives the tool
     * window / MR tab because it lives in the editor.
     */
    private fun openJobLog(job: GitLabJob) {
        val ref = currentRef ?: return
        FileEditorManager.getInstance(project).openFile(JobLogVirtualFile(ref.projectId, job, ref), true)
    }

    /**
     * Opens a log tab for **every** job of [stage] (stages hold few jobs), each its own editor tab; the
     * last one lands focused. An empty stage does nothing.
     */
    private fun openStageLogs(stage: StageGroup) {
        val ref = currentRef ?: return
        val manager = FileEditorManager.getInstance(project)
        for (job in stage.jobs) {
            manager.openFile(JobLogVirtualFile(ref.projectId, job, ref), true)
        }
    }

    // --- Context menu -------------------------------------------------------------------------

    /**
     * The popup for a tree row: stage rows get the stage menu, job rows the job menu, and a flattened
     * `stage · job` row (GLC-59) gets the *job* menu — the row stands for its single job, and every
     * stage action on a one-job stage is that job's action anyway. The summary row has no popup.
     */
    private fun buildContextMenu(node: DefaultMutableTreeNode): JPopupMenu? = when (val data = node.userObject) {
        is StageNodeData -> stageMenu(data.stage)
        is JobNodeData -> jobMenu(data.job)
        is FlatStageNodeData -> data.job?.let { jobMenu(it) }
        else -> null
    }

    private fun stageMenu(stage: StageGroup): JPopupMenu = JPopupMenu().apply {
        add(
            JMenuItem(CockpitBundle.message("pipelines.stage.viewLogs")).apply {
                addActionListener { openStageLogs(stage) }
            },
        )
        addSeparator()
        add(
            JMenuItem(CockpitBundle.message("pipelines.retryStage")).apply {
                addActionListener { onRetryStage(stage) }
            },
        )
    }

    private fun jobMenu(job: GitLabJob): JPopupMenu = JPopupMenu().apply {
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
                    icon = CockpitIcons.status(data.stage.status)
                    append(data.stage.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  (${data.stage.jobs.size})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is JobNodeData -> {
                    val job = data.job
                    icon = CockpitIcons.status(job.status, job.allowFailure)
                    append(job.name)
                    job.duration?.let { append("  ${formatDuration(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                // GLC-59: one `stage · job` line carrying the job's status icon and duration. When the
                // job is named like its stage (a very common CI shape) the name is shown only once.
                is FlatStageNodeData -> {
                    val job = data.job
                    icon = if (job != null) {
                        CockpitIcons.status(job.status, job.allowFailure)
                    } else {
                        CockpitIcons.status(data.stage.status)
                    }
                    append(data.stage.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (job != null && job.name != data.stage.name) {
                        append(" \u00B7 ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append(job.name)
                    }
                    job?.duration?.let { append("  ${formatDuration(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                // GLC-59: the folded "N stages passed (M jobs)" row. The success icon carries the
                // green; the text stays regular-weight so the row reads as quiet, not as a stage.
                is SummaryNodeData -> {
                    icon = CockpitIcons.status("success")
                    append(CockpitBundle.message("pipelines.summary.passed", data.summary.stages.size))
                    append(
                        "  " + CockpitBundle.message("pipelines.summary.jobs", data.summary.jobCount),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
            }
        }
    }

    companion object {
        /** GLC-43 B: live-status poll cadence (5s) while the pipelines card is showing and alive. */
        private const val POLL_INTERVAL_MS = 5000L

        /** GLC-43 B: refresh the pipeline list (combo) every this many poll cycles; jobs every cycle. */
        private const val PIPELINE_LIST_EVERY = 3

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
