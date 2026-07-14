package dev.jota.gitlabcockpit.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores the GitLab PAT in the IDE's PasswordSafe, keyed per instance base URL. The token is
 * never written to the plugin's XML state nor logged.
 */
object TokenStore {

    private const val SUBSYSTEM = "Cockpit for GitLab"

    private fun attributes(baseUrl: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, baseUrl))

    fun get(baseUrl: String): String? =
        PasswordSafe.instance.getPassword(attributes(baseUrl))

    fun set(baseUrl: String, token: String) {
        PasswordSafe.instance.set(attributes(baseUrl), Credentials(baseUrl, token))
    }

    fun clear(baseUrl: String) {
        PasswordSafe.instance.set(attributes(baseUrl), null)
    }
}
