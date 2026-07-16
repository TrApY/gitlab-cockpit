package dev.jota.gitlabcockpit.ui.diff

import com.intellij.openapi.util.Key

/**
 * Editor user-data pointing at a file's [DiffThreadsRenderer], so the remappable Next / Previous
 * review-thread actions ([CockpitNextThreadAction] / [CockpitPreviousThreadAction]) can drive
 * thread-to-thread navigation. [CockpitDiffExtension] stamps it on both editors **only** when it
 * installs the renderer (i.e. the file has threads); its absence keeps the actions disabled, which is
 * exactly right for a file with nothing to jump between.
 *
 * [navigate] moves the caret and scrolls to the next ([forward] = true) or previous review thread,
 * cycling; the renderer resolves the focused editor and caret itself.
 */
class CockpitThreadNavigator(val navigate: (forward: Boolean) -> Unit) {
    companion object {
        /** The editor user-data slot the thread-navigation actions look for. */
        val KEY: Key<CockpitThreadNavigator> = Key.create("dev.jota.gitlabcockpit.diff.threadNavigator")
    }
}
