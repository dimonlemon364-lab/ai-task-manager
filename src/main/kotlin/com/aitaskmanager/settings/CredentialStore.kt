package com.aitaskmanager.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object CredentialStore {
    private const val SUBSYSTEM = "AiTaskManager"

    private fun attributes(key: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, key))

    fun setSecret(key: String, secret: String?) {
        val attrs = attributes(key)
        if (secret.isNullOrEmpty()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(key, secret))
        }
    }

    fun getSecret(key: String): String? =
        PasswordSafe.instance.get(attributes(key))?.getPasswordAsString()
}
