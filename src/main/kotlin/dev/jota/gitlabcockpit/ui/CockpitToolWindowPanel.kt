package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.CockpitState
import dev.jota.gitlabcockpit.core.MergeRequestState
import dev.jota.gitlabcockpit.core.MrFilterSelection
import dev.jota.gitlabcockpit.core.RoleFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * The tool window panel: a filter toolbar over a list of merge requests. All data loading runs on
 * the project service's coroutine scope; the UI thread is only touched via [Dispatchers.EDT].
 * Auto-refreshes every 60s while the tool window is visible; also loads once on open and on demand.
 */
class CockpitToolWindowPanel(
    project: Project,
    private val toolWindow: ToolWindow,
) : JBPanel<CockpitToolWindowPanel>(BorderLayout()), Disposable {

    private val service = CockpitProjectService.getInstance(project)

    private val roleCombo = ComboBox(RoleFilter.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create<RoleFilter>("") { roleLabel(it) }
        selectedItem = RoleFilter.ALL
    }

    private val userField = JBTextField(12).apply {
        emptyText.text = CockpitBundle.message("toolwindow.filter.user.placeholder")
        isVisible = false
    }

    private val stateCombo = ComboBox(MergeRequestState.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create<MergeRequestState>("") { stateLabel(it) }
        selectedItem = MergeRequestState.OPENED
    }

    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = CockpitBundle.message("toolwindow.refresh")
    }

    private val listModel = CollectionListModel<GitLabMergeRequest>()

    private val mrList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = MrCellRenderer()
    }

    private var loadJob: Job? = null

    private val autoRefreshJob: Job = service.coroutineScope.launch {
        while (isActive) {
            delay(AUTO_REFRESH_MS)
            val visible = withContext(Dispatchers.EDT) { toolWindow.isVisible }
            if (!visible) continue
            val selection = withContext(Dispatchers.EDT) { currentSelection() }
            val state = service.loadMergeRequests(selection)
            withContext(Dispatchers.EDT) { render(state) }
        }
    }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(mrList), BorderLayout.CENTER)

        roleCombo.addActionListener {
            userField.isVisible = roleCombo.selectedItem == RoleFilter.BY_USER
            userField.parent?.revalidate()
            reload(invalidateCache = false)
        }
        stateCombo.addActionListener { reload(invalidateCache = false) }
        userField.addActionListener { reload(invalidateCache = false) }
        refreshButton.addActionListener { reload(invalidateCache = true) }

        installOpenActions()

        // Initial load when the tool window is first opened.
        reload(invalidateCache = false)
    }

    private fun buildToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        toolbar.add(JBLabel(CockpitBundle.message("toolwindow.filter.role.label")))
        toolbar.add(roleCombo)
        toolbar.add(userField)
        toolbar.add(JBLabel(CockpitBundle.message("toolwindow.filter.state.label")))
        toolbar.add(stateCombo)
        toolbar.add(refreshButton)
        return toolbar
    }

    private fun installOpenActions() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelected()
                return true
            }
        }.installOn(mrList)

        mrList.registerKeyboardAction(
            { openSelected() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
    }

    private fun openSelected() {
        mrList.selectedValue?.let { BrowserUtil.browse(it.webUrl) }
    }

    private fun currentSelection(): MrFilterSelection = MrFilterSelection(
        role = roleCombo.selectedItem as? RoleFilter ?: RoleFilter.ALL,
        state = stateCombo.selectedItem as? MergeRequestState ?: MergeRequestState.OPENED,
        username = userField.text,
    )

    /** Runs on the EDT (event handlers / init). Shows Loading, then loads off the EDT. */
    private fun reload(invalidateCache: Boolean) {
        if (invalidateCache) service.refresh()
        render(CockpitState.Loading)
        val selection = currentSelection()
        loadJob?.cancel()
        loadJob = service.coroutineScope.launch {
            val state = service.loadMergeRequests(selection)
            withContext(Dispatchers.EDT) { render(state) }
        }
    }

    /** Must be called on the EDT. */
    private fun render(state: CockpitState) {
        when (state) {
            CockpitState.Loading ->
                showMessage(CockpitBundle.message("toolwindow.empty.loading"))
            CockpitState.NotConfigured ->
                showMessage(CockpitBundle.message("toolwindow.empty.notConfigured"))
            CockpitState.NoGitLabRemote ->
                showMessage(CockpitBundle.message("toolwindow.empty.noRemote"))
            is CockpitState.Error ->
                showMessage(state.message)
            is CockpitState.Ready -> {
                mrList.emptyText.text = CockpitBundle.message("toolwindow.empty.noMrs")
                listModel.replaceAll(state.mrs)
            }
        }
    }

    private fun showMessage(text: String) {
        listModel.removeAll()
        mrList.emptyText.text = text
    }

    override fun dispose() {
        autoRefreshJob.cancel()
        loadJob?.cancel()
    }

    private class MrCellRenderer : ColoredListCellRenderer<GitLabMergeRequest>() {
        override fun customizeCellRenderer(
            list: javax.swing.JList<out GitLabMergeRequest>,
            value: GitLabMergeRequest,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value.draft) {
                append(
                    CockpitBundle.message("toolwindow.mr.draft") + " ",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
            append(value.title)
            append("  !${value.iid} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(
                "${value.sourceBranch} → ${value.targetBranch}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            append("   ${value.author.username}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val reviewers = value.reviewers.joinToString(", ") { it.username }
            if (reviewers.isNotEmpty()) {
                append(" → $reviewers", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            append(
                "   ${formatRelative(value.updatedAt)}",
                SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
            )
            if (value.hasConflicts) {
                append(
                    "  ${CockpitBundle.message("toolwindow.mr.conflicts")}",
                    SimpleTextAttributes.ERROR_ATTRIBUTES,
                )
            }
        }
    }

    companion object {
        private const val AUTO_REFRESH_MS = 60_000L

        private fun roleLabel(role: RoleFilter): String = when (role) {
            RoleFilter.ALL -> CockpitBundle.message("toolwindow.filter.role.all")
            RoleFilter.I_AM_AUTHOR -> CockpitBundle.message("toolwindow.filter.role.author")
            RoleFilter.I_AM_REVIEWER -> CockpitBundle.message("toolwindow.filter.role.reviewer")
            RoleFilter.REVIEWER_NOT_APPROVED ->
                CockpitBundle.message("toolwindow.filter.role.reviewerNotApproved")
            RoleFilter.BY_USER -> CockpitBundle.message("toolwindow.filter.role.byUser")
        }

        private fun stateLabel(state: MergeRequestState): String = when (state) {
            MergeRequestState.OPENED -> CockpitBundle.message("toolwindow.filter.state.opened")
            MergeRequestState.MERGED -> CockpitBundle.message("toolwindow.filter.state.merged")
            MergeRequestState.CLOSED -> CockpitBundle.message("toolwindow.filter.state.closed")
            MergeRequestState.ALL -> CockpitBundle.message("toolwindow.filter.state.all")
        }
    }
}
