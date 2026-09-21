package com.martincyr.demoagentsdk

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.agents.client.android.models.AppSettings

data class FoundrySettings(
    val projectEndpoint: String = "",
    val projectName: String = "",
    val agentId: String = "",
    val apiVersion: String = "v1",
    val scope: String = "https://ai.azure.com/.default"
) {
    val isConfigured: Boolean
        get() = projectEndpoint.isNotBlank() && agentId.isNotBlank()
}

data class AppConfiguration(
    val appSettings: AppSettings,
    val foundry: FoundrySettings
)

fun loadAppConfiguration(context: Context): AppConfiguration {
    val resourceId = context.resources.getIdentifier(
        "appsettings_local",
        "raw",
        context.packageName
    ).takeIf { it != 0 } ?: R.raw.appsettings
    val json = context.resources.openRawResource(resourceId)
        .bufferedReader()
        .use { it.readText() }
    val root = JsonParser.parseString(json).asJsonObject
    val appSettings = Gson().fromJson(root, AppSettings::class.java)
    val foundry = root.getAsJsonObject("foundry")
        ?.let { Gson().fromJson(it, FoundrySettings::class.java) }
        ?: FoundrySettings()
    return AppConfiguration(appSettings, foundry)
}
