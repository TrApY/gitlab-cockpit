package dev.jota.gitlabcockpit.settings.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabApiClient
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.settings.GitLabCockpitSettings
import dev.jota.gitlabcockpit.settings.InstanceState
import dev.jota.gitlabcockpit.settings.TokenStore
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent

/**
 * Settings page (Tools > Cockpit for GitLab). Edits the first configured instance: name, base URL
 * and token. The token field is never pre-filled with the real token; a placeholder signals whether
 * one is already saved. "Test Connection" calls `/version` off the EDT and reports inline.
 */
class GitLabCockpitConfigurable : Configurable {

    private val settings get() = GitLabCockpitSettings.getInstance()

    private val nameField = JBTextField()
    private val urlField = JBTextField()
    private val tokenField = JBPasswordField()
    private val resultLabel = JBLabel()

    private val squashCombo = ComboBox(MergeDefault.values())
    private val deleteSourceCombo = ComboBox(MergeDefault.values())

    private val notificationsEnabledCheck =
        JBCheckBox(CockpitBundle.message("settings.notifications.enabled")).apply {
            addActionListener { syncNotificationChecks() }
        }
    private val notifyPipelineCheck = JBCheckBox(CockpitBundle.message("settings.notifications.pipeline"))
    private val notifyNewMrCheck = JBCheckBox(CockpitBundle.message("settings.notifications.newMr"))
    private val notifyMrStateCheck = JBCheckBox(CockpitBundle.message("settings.notifications.mrState"))
    private val notifyPushCheck = JBCheckBox(CockpitBundle.message("settings.notifications.push"))
    private val notifyCommentsCheck = JBCheckBox(CockpitBundle.message("settings.notifications.comments"))

    /** The five per-event checkboxes, greyed out together while the master switch is off. */
    private val eventChecks = listOf(
        notifyPipelineCheck,
        notifyNewMrCheck,
        notifyMrStateCheck,
        notifyPushCheck,
        notifyCommentsCheck,
    )

    override fun getDisplayName(): String = CockpitBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val dialogPanel = panel {
            row(CockpitBundle.message("settings.name.label")) {
                cell(nameField).align(AlignX.FILL)
            }
            row(CockpitBundle.message("settings.url.label")) {
                cell(urlField).align(AlignX.FILL)
            }
            row(CockpitBundle.message("settings.token.label")) {
                cell(tokenField).align(AlignX.FILL)
            }
            row("") {
                button(CockpitBundle.message("settings.test.button")) { testConnection() }
                cell(resultLabel)
            }
            group(CockpitBundle.message("settings.merge.title")) {
                row(CockpitBundle.message("settings.merge.squash.label")) {
                    cell(squashCombo)
                }
                row(CockpitBundle.message("settings.merge.deleteSource.label")) {
                    cell(deleteSourceCombo)
                }
            }
            group(CockpitBundle.message("settings.notifications.title")) {
                row { cell(notificationsEnabledCheck) }
                row { cell(notifyPipelineCheck) }
                row { cell(notifyNewMrCheck) }
                row { cell(notifyMrStateCheck) }
                row { cell(notifyPushCheck) }
                row { cell(notifyCommentsCheck) }
            }
        }
        reset()
        return dialogPanel
    }

    /** Greys out the per-event checkboxes when the master notifications switch is off. */
    private fun syncNotificationChecks() {
        val enabled = notificationsEnabledCheck.isSelected
        eventChecks.forEach { it.isEnabled = enabled }
    }

    private fun firstInstance(): InstanceState? = settings.instances.firstOrNull()

    override fun isModified(): Boolean {
        val instance = firstInstance()
        val nameChanged = nameField.text != (instance?.name ?: "")
        val urlChanged = urlField.text != (instance?.baseUrl ?: "")
        // A non-empty token field always means the user typed a new token to save.
        val tokenChanged = tokenField.password.isNotEmpty()
        val squashChanged = selectedMerge(squashCombo).value != settings.mergeSquash
        val deleteChanged = selectedMerge(deleteSourceCombo).value != settings.mergeDeleteSourceBranch
        val notificationsChanged =
            notificationsEnabledCheck.isSelected != settings.notificationsEnabled ||
                notifyPipelineCheck.isSelected != settings.notifyPipeline ||
                notifyNewMrCheck.isSelected != settings.notifyNewMr ||
                notifyMrStateCheck.isSelected != settings.notifyMrState ||
                notifyPushCheck.isSelected != settings.notifyPush ||
                notifyCommentsCheck.isSelected != settings.notifyComments
        return nameChanged || urlChanged || tokenChanged || squashChanged || deleteChanged ||
            notificationsChanged
    }

    override fun apply() {
        val name = nameField.text.trim()
        val url = urlField.text.trim()

        val instances = settings.instances
        if (instances.isEmpty()) {
            instances.add(InstanceState(name, url))
        } else {
            instances[0].name = name
            instances[0].baseUrl = url
        }

        val typed = tokenField.password
        if (typed.isNotEmpty()) {
            TokenStore.set(url, String(typed))
            typed.fill('\u0000')
        }
        // Never keep the token in the field; refresh the placeholder to reflect what is saved.
        resetTokenField(url)

        settings.mergeSquash = selectedMerge(squashCombo).value
        settings.mergeDeleteSourceBranch = selectedMerge(deleteSourceCombo).value

        settings.notificationsEnabled = notificationsEnabledCheck.isSelected
        settings.notifyPipeline = notifyPipelineCheck.isSelected
        settings.notifyNewMr = notifyNewMrCheck.isSelected
        settings.notifyMrState = notifyMrStateCheck.isSelected
        settings.notifyPush = notifyPushCheck.isSelected
        settings.notifyComments = notifyCommentsCheck.isSelected

        resultLabel.text = ""
    }

    override fun reset() {
        val instance = firstInstance()
        nameField.text = instance?.name.orEmpty()
        urlField.text = instance?.baseUrl.orEmpty()
        resetTokenField(instance?.baseUrl.orEmpty())

        squashCombo.selectedItem = MergeDefault.of(settings.mergeSquash)
        deleteSourceCombo.selectedItem = MergeDefault.of(settings.mergeDeleteSourceBranch)

        notificationsEnabledCheck.isSelected = settings.notificationsEnabled
        notifyPipelineCheck.isSelected = settings.notifyPipeline
        notifyNewMrCheck.isSelected = settings.notifyNewMr
        notifyMrStateCheck.isSelected = settings.notifyMrState
        notifyPushCheck.isSelected = settings.notifyPush
        notifyCommentsCheck.isSelected = settings.notifyComments
        syncNotificationChecks()

        resultLabel.text = ""
    }

    private fun selectedMerge(combo: ComboBox<MergeDefault>): MergeDefault =
        combo.selectedItem as? MergeDefault ?: MergeDefault.GITLAB_DEFAULT

    private fun resetTokenField(baseUrl: String) {
        tokenField.text = ""
        val hasToken = baseUrl.isNotEmpty() && !TokenStore.get(baseUrl).isNullOrEmpty()
        tokenField.emptyText.text = CockpitBundle.message(
            if (hasToken) "settings.token.saved" else "settings.token.placeholder",
        )
    }

    private fun testConnection() {
        val url = urlField.text.trim()
        if (url.isEmpty()) {
            setResult(CockpitBundle.message("settings.test.noUrl"))
            return
        }
        // Prefer the token being typed; the fallback to the stored one is resolved off the EDT
        // because PasswordSafe can hit the OS keychain and must not block the UI thread.
        val typed = tokenField.password
        val typedToken: String? = if (typed.isNotEmpty()) String(typed) else null
        typed.fill('\u0000')

        setResult(CockpitBundle.message("settings.test.inProgress"))
        val modality = ModalityState.current()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, CockpitBundle.message("settings.test.title"), false) {
                override fun run(indicator: ProgressIndicator) {
                    val token = typedToken ?: TokenStore.get(url)
                    val result = runBlocking { GitLabApiClient(url) { token }.getVersion() }
                    val message = when (result) {
                        is GitLabResult.Success ->
                            CockpitBundle.message("settings.test.success", result.data.version)
                        is GitLabResult.HttpError ->
                            CockpitBundle.message("settings.test.httpError", result.status)
                        is GitLabResult.NetworkError ->
                            CockpitBundle.message(
                                "settings.test.networkError",
                                result.cause.message ?: result.cause.javaClass.simpleName,
                            )
                    }
                    ApplicationManager.getApplication().invokeLater({ setResult(message) }, modality)
                }
            },
        )
    }

    private fun setResult(text: String) {
        resultLabel.text = text
    }

    /**
     * The three choices of a merge-option combo, mapped to the tri-state [value] persisted by
     * [GitLabCockpitSettings] (`null` = defer to the MR's GitLab default). [toString] renders the
     * localized label, which the combo shows directly.
     */
    private enum class MergeDefault(private val labelKey: String, val value: Boolean?) {
        GITLAB_DEFAULT("settings.merge.option.default", null),
        ALWAYS_ON("settings.merge.option.on", true),
        ALWAYS_OFF("settings.merge.option.off", false);

        override fun toString(): String = CockpitBundle.message(labelKey)

        companion object {
            fun of(value: Boolean?): MergeDefault = values().first { it.value == value }
        }
    }
}
