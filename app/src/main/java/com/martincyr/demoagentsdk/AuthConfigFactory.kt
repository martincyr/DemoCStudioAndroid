package com.martincyr.demoagentsdk

import android.content.Context
import com.google.gson.Gson
import com.microsoft.agents.client.android.models.AppSettings
import java.io.File

/**
 * Builds the MSAL configuration file shared by the native SDK path and the WebChat token provider.
 */
object AuthConfigFactory {
    private const val AUTH_CONFIG_FILE_NAME = "auth_config_temp.json"

    fun createAuthConfigFile(context: Context, appSettings: AppSettings): File {
        val auth = appSettings.user.auth
        val tenantId = auth.tenantId.ifBlank { "common" }
        val config: Map<String, Any> = mapOf(
            "client_id" to auth.clientId,
            "authorization_user_agent" to "WEBVIEW",
            "redirect_uri" to auth.redirectUri,
            "account_mode" to "SINGLE",
            "broker_redirect_uri_registered" to true,
            "authorities" to listOf(
                mapOf(
                    "type" to "AAD",
                    "authority_url" to "https://login.microsoftonline.com/$tenantId",
                    "audience" to mapOf(
                        "type" to "AzureADandPersonalMicrosoftAccount",
                        "tenant_id" to tenantId
                    )
                )
            )
        )

        return File(context.cacheDir, AUTH_CONFIG_FILE_NAME).apply {
            writeText(Gson().toJson(config))
        }
    }
}
