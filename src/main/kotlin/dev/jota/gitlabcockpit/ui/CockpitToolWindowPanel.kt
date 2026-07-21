package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.CockpitState
import dev.jota.gitlabcockpit.core.MergeRequestState
import dev.jota.gitlabcockpit.core.MrFilterSelection
import dev.jota.gitlabcockpit.core.MrNotificationsWatcher
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.RoleFilter
import dev.jota.gitlabcockpit.core.projectLabelOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
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

    /** Fires the configurable IDE balloons (pipelines, new MRs, state changes, pushes, comments). */
    private val mrNotificationsWatcher = MrNotificationsWatcher(project, service)

    private val roleCombo = ComboBox(RoleFilter.entries.toTypedArray()).apply {
        renderer = textCellRenderer<RoleFilter>("") { roleLabel(it) }
        selectedItem = RoleFilter.ALL
    }

    /** Feeds the "By user" field with member candidates matched by [filterMembers] semantics. */
    private val userCompletionProvider = MemberCompletionProvider()

    private val userField = TextFieldWithAutoCompletion(project, userCompletionProvider, true, "").apply {
        setPlaceholder(CockpitBundle.message("toolwindow.filter.user.placeholder"))
        setShowPlaceholderWhenFocused(true)
        setPreferredWidth(JBUI.scale(160))
        isVisible = false
    }

    /**
     * Debounces the "By user" reload: every document change restarts a [USER_FILTER_DEBOUNCE_MS]
     * timer so typing, pasting or picking from the popup all coalesce into a single reload. Parented
     * to this panel so it is disposed with it.
     */
    private val userReloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Whether the member candidates have been requested for the current (cached) roster. */
    private var userCandidatesLoaded = false

    private val stateCombo = ComboBox(MergeRequestState.entries.toTypedArray()).apply {
        renderer = textCellRenderer<MergeRequestState>("") { stateLabel(it) }
        selectedItem = MergeRequestState.OPENED
    }

    /**
     * "All projects" toggle: when checked, the list shows the current user's (or the filtered user's)
     * MRs across the whole GitLab instance instead of only the git-resolved project. The role [ALL] is
     * not meaningful instance-wide, so enabling this with [RoleFilter.ALL] selected flips the role to
     * [RoleFilter.I_AM_AUTHOR] (see the listeners below).
     */
    private val allProjectsCheckBox = JBCheckBox(CockpitBundle.message("toolwindow.filter.allProjects"))

    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = CockpitBundle.message("toolwindow.refresh")
    }

    /** Opens the selected MR's GitLab page in the external browser (also offered on right-click). */
    private val openInBrowserButton = JButton(AllIcons.General.Web).apply {
        toolTipText = CockpitBundle.message("toolwindow.openInBrowser")
    }

    /** Web URL opened by [projectLink]; set from the resolved project on each successful load. */
    private var projectWebUrl: String? = null

    /** Clickable `path/with/namespace` of the resolved GitLab project; shown only in [CockpitState.Ready]. */
    private val projectLink = ActionLink("") {
        projectWebUrl?.let { BrowserUtil.browse(it) }
    }.apply {
        toolTipText = CockpitBundle.message("toolwindow.project.tooltip")
        isVisible = false
    }

    /** Guards the remote selector's listener while it is repopulated programmatically by [render]. */
    private var suppressRemoteSelectorEvents = false

    /**
     * Repo selector shown instead of [projectLink] when the project has several git roots matching
     * the configured instance (e.g. submodules). Picking one persists the choice and reloads the list.
     * Mutually exclusive with [projectLink]; both are hidden outside [CockpitState.Ready].
     */
    private val remoteSelector = ComboBox<String>().apply {
        toolTipText = CockpitBundle.message("toolwindow.remoteSelector.tooltip")
        isVisible = false
        addActionListener {
            if (suppressRemoteSelectorEvents) return@addActionListener
            val path = selectedItem as? String ?: return@addActionListener
            service.selectRemote(path)
            // selectRemote already refreshed the caches, so no invalidateCache here.
            reload(invalidateCache = false)
        }
    }

    private val listModel = CollectionListModel<GitLabMergeRequest>()

    /** Shared row renderer; its [MrCellRenderer.showProject] is toggled by [render] per loaded mode. */
    private val mrCellRenderer = MrCellRenderer()

    private val mrList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = mrCellRenderer
    }

    private val detailPanel = MrDetailPanel(project, service, onListReloadRequested = { reloadSilently() })

    private val splitter = OnePixelSplitter(true, SPLITTER_PROPORTION_KEY, 0.45f).apply {
        firstComponent = JBScrollPane(mrList)
        secondComponent = detailPanel
    }

    /** Guards the selection listener while the list is repopulated and its selection restored. */
    private var suppressSelectionEvents = false

    private var loadJob: Job? = null

    private val autoRefreshJob: Job = service.coroutineScope.launch {
        while (isActive) {
            delay(AUTO_REFRESH_MS)
            val visible = withContext(Dispatchers.EDT) { toolWindow.isVisible }
            if (!visible) continue
            val selection = withContext(Dispatchers.EDT) { currentSelection() }
            val state = service.loadMergeRequests(selection)
            withContext(Dispatchers.EDT) { render(state, selection.allProjects) }
            maybeWatch(state)
        }
    }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        mrList.addListSelectionListener { event ->
            if (suppressSelectionEvents || event.valueIsAdjusting) return@addListSelectionListener
            reconcileDetailWithSelection()
        }

        roleCombo.addActionListener {
            // In "All projects" mode the ALL role is not valid; bounce it to I_AM_AUTHOR. Setting the
            // combo re-enters this listener with the new role, which performs the actual reload.
            if (allProjectsCheckBox.isSelected && roleCombo.selectedItem == RoleFilter.ALL) {
                roleCombo.selectedItem = RoleFilter.I_AM_AUTHOR
                return@addActionListener
            }
            val byUser = roleCombo.selectedItem == RoleFilter.BY_USER
            userField.isVisible = byUser
            userField.parent?.revalidate()
            if (byUser) ensureUserCandidatesLoaded()
            reload(invalidateCache = false)
        }
        allProjectsCheckBox.addActionListener {
            // Enabling global mode while ALL is selected switches the role (its own listener reloads);
            // any other toggle reloads directly.
            if (allProjectsCheckBox.isSelected && roleCombo.selectedItem == RoleFilter.ALL) {
                roleCombo.selectedItem = RoleFilter.I_AM_AUTHOR
            } else {
                reload(invalidateCache = false)
            }
        }
        stateCombo.addActionListener { reload(invalidateCache = false) }
        userField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                userReloadAlarm.cancelAllRequests()
                userReloadAlarm.addRequest({ reload(invalidateCache = false) }, USER_FILTER_DEBOUNCE_MS)
            }
        })
        refreshButton.addActionListener { reload(invalidateCache = true) }
        openInBrowserButton.addActionListener { browseSelected() }

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
        toolbar.add(allProjectsCheckBox)
        toolbar.add(refreshButton)
        toolbar.add(openInBrowserButton)
        toolbar.add(projectLink)
        toolbar.add(remoteSelector)
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

        // Right-click on a row: select it and offer "Open in browser" (mirrors the toolbar button).
        mrList.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = mrList.locationToIndex(Point(x, y))
                if (index < 0 || mrList.getCellBounds(index, index)?.contains(x, y) != true) return
                mrList.selectedIndex = index
                JPopupMenu().apply {
                    add(
                        JMenuItem(CockpitBundle.message("toolwindow.openInBrowser")).apply {
                            addActionListener { browseSelected() }
                        },
                    )
                }.show(comp, x, y)
            }
        })
    }

    /** Double-click / Enter on a row: show the selected MR in the detail pane and move focus to it. */
    private fun openSelected() {
        if (mrList.selectedValue == null) return
        reconcileDetailWithSelection()
        detailPanel.focusContent()
    }

    /** Opens the selected MR's GitLab page in the external browser. */
    private fun browseSelected() {
        mrList.selectedValue?.let { BrowserUtil.browse(it.webUrl) }
    }

    private fun currentSelection(): MrFilterSelection = MrFilterSelection(
        role = roleCombo.selectedItem as? RoleFilter ?: RoleFilter.ALL,
        state = stateCombo.selectedItem as? MergeRequestState ?: MergeRequestState.OPENED,
        username = userField.text,
        allProjects = allProjectsCheckBox.isSelected,
    )

    /** Runs on the EDT (event handlers / init). Shows Loading, then loads off the EDT. */
    private fun reload(invalidateCache: Boolean) {
        if (invalidateCache) {
            service.refresh()
            // The member cache was just dropped; re-fetch candidates if the "By user" field is live.
            userCandidatesLoaded = false
            if (userField.isVisible) ensureUserCandidatesLoaded()
        }
        val selection = currentSelection()
        render(CockpitState.Loading, selection.allProjects)
        loadJob?.cancel()
        loadJob = service.coroutineScope.launch {
            val state = service.loadMergeRequests(selection)
            withContext(Dispatchers.EDT) { render(state, selection.allProjects) }
            maybeWatch(state)
        }
    }

    /**
     * Reloads the list without the Loading placeholder, preserving the current selection. Used
     * after an edit so the list picks up the change while the detail stays put. Runs off the EDT.
     */
    private fun reloadSilently() {
        val selection = currentSelection()
        loadJob?.cancel()
        loadJob = service.coroutineScope.launch {
            val state = service.loadMergeRequests(selection)
            withContext(Dispatchers.EDT) { render(state, selection.allProjects) }
            maybeWatch(state)
        }
    }

    /**
     * Runs the lightweight notifications watcher for a freshly loaded list, on its own root
     * coroutine so it neither blocks the render nor is cancelled by the next [reload]. Off the EDT.
     */
    private fun maybeWatch(state: CockpitState) {
        if (state is CockpitState.Ready) {
            service.coroutineScope.launch { mrNotificationsWatcher.onReady(state) }
        }
    }

    /**
     * Loads the project members off the EDT (once per cached roster) and hands them to the "By user"
     * completion field. Called when the BY_USER filter first becomes visible and again after a manual
     * refresh invalidates the cache. A failed load leaves the flag unset so a later trigger retries.
     */
    private fun ensureUserCandidatesLoaded() {
        if (userCandidatesLoaded) return
        userCandidatesLoaded = true
        service.coroutineScope.launch {
            val result = service.getResolvedMembers()
            withContext(Dispatchers.EDT) {
                if (result is GitLabResult.Success) {
                    userField.setVariants(result.data)
                } else {
                    userCandidatesLoaded = false
                }
            }
        }
    }

    /** EDT. Loads/clears the detail to match the current list selection. */
    private fun reconcileDetailWithSelection() {
        val selected = mrList.selectedValue
        when {
            selected == null -> detailPanel.showPlaceholder()
            MrRef(selected.projectId, selected.iid) != detailPanel.currentRef ->
                detailPanel.loadDetail(MrRef(selected.projectId, selected.iid))
            // Same MR already shown → leave the detail untouched.
        }
    }

    /** Must be called on the EDT. [showProject] mirrors the load's "All projects" mode for the rows. */
    private fun render(state: CockpitState, showProject: Boolean) {
        mrCellRenderer.showProject = showProject
        renderRemoteControls(state)
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
                val previousRef = mrList.selectedValue?.let { MrRef(it.projectId, it.iid) }
                mrList.emptyText.text = CockpitBundle.message("toolwindow.empty.noMrs")
                suppressSelectionEvents = true
                listModel.replaceAll(state.mrs)
                val restoreIndex = previousRef?.let { ref ->
                    state.mrs.indexOfFirst { MrRef(it.projectId, it.iid) == ref }
                } ?: -1
                if (restoreIndex >= 0) mrList.selectedIndex = restoreIndex
                suppressSelectionEvents = false
                reconcileDetailWithSelection()
            }
        }
    }

    /**
     * EDT. Reconciles the two mutually-exclusive toolbar controls with [state]: a repo selector when
     * a Ready load has more than one matching git root, otherwise the single-project link; both hidden
     * in any non-Ready state.
     */
    private fun renderRemoteControls(state: CockpitState) {
        if (state is CockpitState.Ready && state.remotePaths.size > 1) {
            hideProjectLink()
            renderRemoteSelector(state.remotePaths, state.glProject.pathWithNamespace)
        } else if (state is CockpitState.Ready) {
            hideRemoteSelector()
            renderProjectLink(state.glProject.pathWithNamespace, state.glProject.webUrl)
        } else {
            hideRemoteSelector()
            hideProjectLink()
        }
    }

    /** EDT. Populates and shows the repo selector, selecting the currently resolved [selected] path. */
    private fun renderRemoteSelector(paths: List<String>, selected: String) {
        suppressRemoteSelectorEvents = true
        remoteSelector.model = DefaultComboBoxModel(paths.toTypedArray())
        remoteSelector.selectedItem = selected
        suppressRemoteSelectorEvents = false
        remoteSelector.isVisible = true
        remoteSelector.parent?.revalidate()
    }

    /** EDT. Hides the repo selector (single-root or non-Ready state). */
    private fun hideRemoteSelector() {
        remoteSelector.isVisible = false
        remoteSelector.parent?.revalidate()
    }

    /** EDT. Shows the toolbar link to the resolved GitLab [pathWithNamespace], opening [webUrl]. */
    private fun renderProjectLink(pathWithNamespace: String, webUrl: String) {
        projectWebUrl = webUrl
        projectLink.text = pathWithNamespace
        projectLink.isVisible = true
        projectLink.parent?.revalidate()
    }

    /** EDT. Hides the toolbar project link (any non-Ready state). */
    private fun hideProjectLink() {
        projectWebUrl = null
        projectLink.isVisible = false
        projectLink.parent?.revalidate()
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

        /** When true (the "All projects" mode), each row is prefixed with its `group/project` label. */
        var showProject: Boolean = false

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
            if (showProject) {
                projectLabelOf(value)?.let { label ->
                    append("$label  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
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
        private const val USER_FILTER_DEBOUNCE_MS = 500
        private const val SPLITTER_PROPORTION_KEY = "dev.jota.gitlabcockpit.detail.splitter"

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
