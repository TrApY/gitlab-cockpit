package dev.jota.gitlabcockpit.core

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import dev.jota.gitlabcockpit.ui.CockpitNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps GLC-27 notifications alive while the tool window is closed (GLC-28). Registered as a
 * `postStartupActivity` [ProjectActivity]: [execute] runs once per opened project and launches a loop
 * on the project service's coroutine scope (so it is cancelled when the project closes), then returns.
 *
 * Each tick sleeps for the configured [GitLabCockpitSettings.backgroundPollMinutes] (re-read every
 * iteration, so changing it in Settings takes effect without restarting the loop), then:
 * - master notifications off → skip (no network);
 * - tool window visible → skip (its own 60s auto-refresh already covers that case);
 * - otherwise load the whole project's open MRs and feed a [CockpitState.Ready] through the same
 *   [MrNotificationsWatcher.onReady] the panel uses. The watcher's state (last snapshot / pipeline
 *   status) lives in the shared service, so the panel and this poller never double-notify.
 *
 * Non-[CockpitState.Ready] outcomes (not configured / no remote / error) are skipped silently.
 */
class BackgroundNotificationsPoller : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = CockpitProjectService.getInstance(project)
        val watcher = MrNotificationsWatcher(project, service)
        service.coroutineScope.launch {
            while (isActive) {
                val minutes = GitLabCockpitSettings.getInstance().backgroundPollMinutes.coerceAtLeast(1)
                delay(minutes.toLong() * 60_000L)
                pollOnce(project, service, watcher)
            }
        }
    }

    /** One poll: honors the master switch and the tool-window-visible skip, then runs the watcher. */
    private suspend fun pollOnce(
        project: Project,
        service: CockpitProjectService,
        watcher: MrNotificationsWatcher,
    ) {
        if (!GitLabCockpitSettings.getInstance().notificationsEnabled) return
        if (isToolWindowVisible(project)) return
        val state = service.loadMergeRequests(
            MrFilterSelection(RoleFilter.ALL, MergeRequestState.OPENED),
        )
        if (state is CockpitState.Ready) watcher.onReady(state)
    }

    /** Reads the plugin tool window's visibility on the EDT (tool window state must be queried there). */
    private suspend fun isToolWindowVisible(project: Project): Boolean =
        withContext(Dispatchers.EDT) {
            ToolWindowManager.getInstance(project)
                .getToolWindow(CockpitNavigation.TOOL_WINDOW_ID)?.isVisible == true
        }
}
