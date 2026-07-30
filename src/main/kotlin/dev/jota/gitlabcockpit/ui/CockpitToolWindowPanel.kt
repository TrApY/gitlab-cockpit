package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.util.Key
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.core.COCKPIT_NOTIFICATION_GROUP
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.CockpitState
import dev.jota.gitlabcockpit.core.MergeRequestState
import dev.jota.gitlabcockpit.core.MrFilterSelection
import dev.jota.gitlabcockpit.core.MrNotificationsWatcher
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.MrSection
import dev.jota.gitlabcockpit.core.RoleFilter
import dev.jota.gitlabcockpit.core.filterByTitle
import dev.jota.gitlabcockpit.core.mrTabLabel
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
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

/**
 * The tool window panel: a filter toolbar over a list of merge requests. All data loading runs on
 * the project service's coroutine scope; the UI thread is only touched via [Dispatchers.EDT].
 * Auto-refreshes every 60s while the tool window is visible; also loads once on open and on demand.
 */
class CockpitToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : JBPanel<CockpitToolWindowPanel>(BorderLayout()), Disposable {

    private val service = CockpitProjectService.getInstance(project)

    /** Fires the configurable IDE balloons (pipelines, new MRs, state changes, pushes, comments). */
    private val mrNotificationsWatcher = MrNotificationsWatcher(project, service)

    private val roleCombo = ComboBox(RoleFilter.entries.toTypedArray()).apply {
        renderer = textCellRenderer<RoleFilter>("") { roleLabel(it) }
        selectedItem = RoleFilter.ALL
        toolTipText = CockpitBundle.message("toolwindow.filter.role.label")
    }

    /**
     * Live, in-memory title filter over the already-loaded list (no network). Debounced through
     * [titleFilterAlarm] so typing coalesces into a single [applyDisplayedList]; sits at the front of
     * the toolbar, self-explained by its search icon and placeholder.
     */
    private val titleSearchField = SearchTextField().apply {
        textEditor.emptyText.text = CockpitBundle.message("toolwindow.filter.title.placeholder")
        textEditor.columns = TITLE_FIELD_COLUMNS
    }

    /** Debounces the title filter: each keystroke restarts a [TITLE_FILTER_DEBOUNCE_MS] timer. */
    private val titleFilterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

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
        toolTipText = CockpitBundle.message("toolwindow.filter.state.label")
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

    /** Circular author/reviewer avatars for the rows; shared application-level cache. */
    private val avatarCache = AvatarCache.getInstance()

    /** Per-row head-pipeline status, fetched in the background and read synchronously by the renderer. */
    private val enrichment = MrListEnrichment.getInstance(project)

    /** Shared row renderer; its [MrListCellRenderer.showProject] is toggled by [render] per loaded mode. */
    private val mrCellRenderer = MrListCellRenderer(project, avatarCache, enrichment) { mrList.repaint() }

    /**
     * The MR list. A [JBList] subclass so its rows can carry tooltips (GLC-38 / iter3 G17): a cell
     * renderer is a stamp, so its avatars/badges never fire tooltips on their own. [getToolTipText]
     * maps the hovered point to its row, re-stamps and lays out the renderer at the cell's size, and
     * hit-tests the sub-component under the mouse — so each avatar answers with *its own* person
     * («Alex Marin (Author, Reviewer)»), the `+N` badge with the people it hides, and the badges with
     * their own text (GLC-44: per-element tooltips instead of one whole-row string).
     */
    private val mrList: JBList<GitLabMergeRequest> = object : JBList<GitLabMergeRequest>(listModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index < 0) return null
            val bounds = getCellBounds(index, index) ?: return null
            if (!bounds.contains(event.point)) return null
            val mr = model.getElementAt(index) ?: return null
            val stamp = cellRenderer.getListCellRendererComponent(this, mr, index, false, false)
            stamp.setBounds(0, 0, bounds.width, bounds.height)
            layoutRecursively(stamp)
            val deepest = SwingUtilities.getDeepestComponentAt(
                stamp,
                event.point.x - bounds.x,
                event.point.y - bounds.y,
            )
            var component: Component? = deepest
            while (component != null && component !== stamp) {
                (component as? JComponent)?.toolTipText?.let { return it }
                component = component.parent
            }
            return null
        }

        /** Lays out the freshly-stamped renderer tree (a non-displayable stamp never gets validated). */
        private fun layoutRecursively(component: Component) {
            component.doLayout()
            if (component is java.awt.Container) {
                for (child in component.components) layoutRecursively(child)
            }
        }
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = mrCellRenderer
    }

    /**
     * The full list as last loaded from GitLab, before the in-memory title filter. The displayed
     * [listModel] is [filterByTitle] applied to this; kept so the filter can re-run on each keystroke
     * without a reload.
     */
    private var loadedMrs: List<GitLabMergeRequest> = emptyList()

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
        add(JBScrollPane(mrList), BorderLayout.CENTER)

        roleCombo.addActionListener {
            // In "All projects" mode the ALL role is not valid; bounce it to I_AM_AUTHOR. Setting the
            // combo re-enters this listener with the new role, which performs the actual reload.
            if (allProjectsCheckBox.isSelected && roleCombo.selectedItem == RoleFilter.ALL) {
                roleCombo.selectedItem = RoleFilter.I_AM_AUTHOR
                notifyAllRoleUnavailable()
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
            updateRoleComboTooltip()
            if (allProjectsCheckBox.isSelected && roleCombo.selectedItem == RoleFilter.ALL) {
                roleCombo.selectedItem = RoleFilter.I_AM_AUTHOR
                notifyAllRoleUnavailable()
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
        titleSearchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun schedule() {
                titleFilterAlarm.cancelAllRequests()
                titleFilterAlarm.addRequest({ applyDisplayedList() }, TITLE_FILTER_DEBOUNCE_MS)
            }

            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = schedule()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = schedule()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = schedule()
        })

        installOpenActions()

        // Enable per-row tooltips on the MR list (JList does not register with the manager on its own).
        ToolTipManager.sharedInstance().registerComponent(mrList)

        // Initial load when the tool window is first opened.
        reload(invalidateCache = false)
    }

    private fun buildToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        toolbar.add(titleSearchField)
        toolbar.add(roleCombo)
        toolbar.add(userField)
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

    /** Double-click / Enter on a row: open (or re-select) the selected MR's own closeable tab. */
    private fun openSelected() {
        val mr = mrList.selectedValue ?: return
        openMrTab(mr)
    }

    /**
     * Opens (or re-selects) [mr]'s own closeable tab, landing on [section]. Tabs are keyed by [MrRef] so
     * a second open of the same MR just re-selects its existing tab instead of duplicating it; a fresh
     * tab loads that MR into its own [MrDetailPanel] and takes focus. Public so a notification's "Open in
     * Cockpit" action can reach it through [CockpitNavigation] once the tool window has been activated
     * (GLC-54).
     *
     * [section] is what an event balloon uses to land on the matching part of the tab (GLC-64) and is
     * honored in both paths: an already-open tab gets its section applied right after it is re-selected,
     * a fresh one right after its detail load is kicked off (the panel defers it until the MR renders).
     * It defaults to [MrSection.OVERVIEW], a no-op that leaves the tab exactly where it was — which is
     * what the list's own double-click/Enter open wants.
     */
    fun openMrTab(mr: GitLabMergeRequest, section: MrSection = MrSection.OVERVIEW) {
        val ref = MrRef(mr.projectId, mr.iid)
        val contentManager = toolWindow.contentManager

        val existing = contentManager.contents.firstOrNull { it.getUserData(MR_TAB_REF_KEY) == ref }
        if (existing != null) {
            contentManager.setSelectedContent(existing, true)
            (existing.component as? MrDetailPanel)?.showSection(section)
            return
        }

        val detail = MrDetailPanel(project, service, onListReloadRequested = { reloadSilently() })
        val content = ContentFactory.getInstance()
            .createContent(detail, mrTabLabel(mr.iid, mr.title), false)
        content.isCloseable = true
        content.description = mr.title
        content.putUserData(MR_TAB_REF_KEY, ref)
        content.setDisposer(detail)
        contentManager.addContent(content)
        contentManager.setSelectedContent(content, true)
        detail.loadDetail(ref)
        detail.showSection(section)
    }

    /** Opens the selected MR's GitLab page in the external browser. */
    private fun browseSelected() {
        mrList.selectedValue?.let { BrowserUtil.browse(it.webUrl) }
    }

    /**
     * Explains the silent ALL→I_AM_AUTHOR bounce: in "All projects" mode the list comes from the
     * instance-wide `/merge_requests?scope=all`, where an unrestricted "All" would page through every
     * merge request the user can see on the whole GitLab instance. Without this balloon the bounce
     * looks like a broken combo (reported by a user who kept re-selecting "All").
     */
    private fun notifyAllRoleUnavailable() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(COCKPIT_NOTIFICATION_GROUP)
            .createNotification(
                CockpitBundle.message("toolwindow.filter.role.allUnavailable"),
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    /** Keeps the role combo's tooltip in sync with the "All projects" constraint on the ALL role. */
    private fun updateRoleComboTooltip() {
        roleCombo.toolTipText = if (allProjectsCheckBox.isSelected) {
            CockpitBundle.message("toolwindow.filter.role.allProjectsTooltip")
        } else {
            CockpitBundle.message("toolwindow.filter.role.label")
        }
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
                loadedMrs = state.mrs
                mrList.emptyText.text = CockpitBundle.message("toolwindow.empty.noMrs")
                enrichment.enrich(loadedMrs) { mrList.repaint() }
                applyDisplayedList()
            }
        }
    }

    /**
     * Rebuilds the displayed [listModel] from [loadedMrs] through the in-memory title filter,
     * preserving the current selection by [MrRef]. Runs on the EDT; called on each successful load and
     * on every (debounced) title-filter keystroke.
     */
    private fun applyDisplayedList() {
        val previousRef = mrList.selectedValue?.let { MrRef(it.projectId, it.iid) }
        val displayed = filterByTitle(loadedMrs, titleSearchField.text)
        listModel.replaceAll(displayed)
        val restoreIndex = previousRef?.let { ref ->
            displayed.indexOfFirst { MrRef(it.projectId, it.iid) == ref }
        } ?: -1
        if (restoreIndex >= 0) mrList.selectedIndex = restoreIndex
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
        loadedMrs = emptyList()
        listModel.removeAll()
        mrList.emptyText.text = text
    }

    override fun dispose() {
        autoRefreshJob.cancel()
        loadJob?.cancel()
    }

    companion object {
        private const val AUTO_REFRESH_MS = 60_000L
        private const val USER_FILTER_DEBOUNCE_MS = 500
        private const val TITLE_FILTER_DEBOUNCE_MS = 300
        private const val TITLE_FIELD_COLUMNS = 16

        /** Content user-data slot keying each per-MR tab by its [MrRef], so re-opens re-select it. */
        private val MR_TAB_REF_KEY: Key<MrRef> = Key.create("dev.jota.gitlabcockpit.toolwindow.mrTabRef")

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
