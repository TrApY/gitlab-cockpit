package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import javax.swing.Icon

/**
 * Single source of the plugin's status icons and custom assets, the icon counterpart to
 * [CockpitTheme]: one status→icon mapping shared by the pipelines tree and any other status-aware
 * surface.
 */
object CockpitIcons {

    /**
     * Maps a GitLab job/stage status to its icon. A failed job that is [allowFailure] shows the
     * warning icon instead of the error one (GitLab's "allowed to fail"). A `running` status returns
     * a fresh [AnimatedIcon.Default] on every call so each spinner animates independently.
     */
    fun status(status: String, allowFailure: Boolean = false): Icon =
        if (status == "failed" && allowFailure) {
            AllIcons.General.Warning
        } else {
            when (status) {
                "success" -> AllIcons.RunConfigurations.TestState.Green2
                "failed" -> AllIcons.RunConfigurations.TestState.Red2
                "running" -> AnimatedIcon.Default()
                "warning" -> AllIcons.General.Warning
                "manual" -> AllIcons.Actions.Pause
                "canceled" -> AllIcons.Actions.Suspend
                else -> AllIcons.RunConfigurations.TestNotRan // pending / created / skipped / unknown
            }
        }

    /** Tool window icon: a monochrome cockpit dial, loaded with its light/dark SVG pair. */
    val toolWindow: Icon = IconLoader.getIcon("/icons/toolwindow.svg", CockpitIcons::class.java)
}
