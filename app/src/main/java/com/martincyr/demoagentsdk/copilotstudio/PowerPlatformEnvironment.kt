package com.martincyr.demoagentsdk.copilotstudio

import java.net.URLEncoder

/**
 * Power Platform clouds and the API host suffix that identifies each one.
 */
enum class PowerPlatformCloud(val endpointSuffix: String, val idSuffixLength: Int) {
    Prod("api.powerplatform.com", 2),
    FirstRelease("api.powerplatform.com", 2),
    Preprod("api.preprod.powerplatform.com", 1),
    Test("api.test.powerplatform.com", 1),
    Dev("api.dev.powerplatform.com", 1),
    Exp("api.exp.powerplatform.com", 1),
    Prv("api.prv.powerplatform.com", 1),
    Local("api.powerplatform.localhost", 1),
    Gov("api.gov.powerplatform.microsoft.us", 1),
    GovFR("api.gov.powerplatform.microsoft.us", 1),
    High("api.high.powerplatform.microsoft.us", 1),
    DoD("api.appsplatform.us", 1),
    Mooncake("api.powerplatform.partner.microsoftonline.cn", 1),
    Ex("api.powerplatform.eaglex.ic.gov", 1),
    Rx("api.powerplatform.microsoft.scloud", 1);

    companion object {
        fun fromName(value: String?): PowerPlatformCloud {
            if (value.isNullOrBlank()) return Prod
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Prod
        }
    }
}

/**
 * Builds the Direct-to-Engine endpoints for a Copilot Studio agent.
 *
 * The environment host is derived from the environment id by lowercasing it, dropping the dashes
 * and inserting a `.` before the trailing segment (2 characters in Prod, 1 elsewhere), which is
 * the same transformation the official clients perform.
 */
class CopilotStudioConnection(
    environmentId: String,
    private val schemaName: String,
    val cloud: PowerPlatformCloud = PowerPlatformCloud.Prod
) {
    init {
        require(environmentId.isNotBlank()) { "environmentId is required." }
        require(schemaName.isNotBlank()) { "schemaName is required." }
    }

    private val host: String = buildHost(environmentId, cloud)

    /** The MSAL scope that produces a token accepted by this environment. */
    val tokenScope: String = "https://${cloud.endpointSuffix}/.default"

    fun conversationUrl(conversationId: String? = null): String {
        val builder = StringBuilder("https://")
            .append(host)
            .append("/copilotstudio/dataverse-backed/authenticated/bots/")
            .append(schemaName.urlEncoded())
            .append("/conversations")
        if (!conversationId.isNullOrBlank()) {
            builder.append('/').append(conversationId.urlEncoded())
        }
        builder.append("?api-version=").append(API_VERSION)
        return builder.toString()
    }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val API_VERSION = "2022-03-01-preview"

        internal fun buildHost(environmentId: String, cloud: PowerPlatformCloud): String {
            val normalized = environmentId.lowercase().replace("-", "")
            require(normalized.length > cloud.idSuffixLength) {
                "environmentId '$environmentId' is too short to be a valid environment id."
            }
            val split = normalized.length - cloud.idSuffixLength
            val prefix = normalized.substring(0, split)
            val suffix = normalized.substring(split)
            return "$prefix.$suffix.environment.${cloud.endpointSuffix}"
        }
    }
}
