package com.martincyr.demoagentsdk.copilotstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotStudioConnectionTest {
    @Test
    fun `prod hosts split the last two characters of the environment id`() {
        val host = CopilotStudioConnection.buildHost(
            "A47151CF-4F34-488F-B377-EBE84E17B478",
            PowerPlatformCloud.Prod
        )

        assertEquals(
            "a47151cf4f34488fb377ebe84e17b4.78.environment.api.powerplatform.com",
            host
        )
    }

    @Test
    fun `non-prod clouds split only the last character`() {
        val host = CopilotStudioConnection.buildHost(
            "A47151CF-4F34-488F-B377-EBE84E17B478",
            PowerPlatformCloud.Preprod
        )

        assertEquals(
            "a47151cf4f34488fb377ebe84e17b47.8.environment.api.preprod.powerplatform.com",
            host
        )
    }

    @Test
    fun `conversation urls carry the api version and encode the conversation id`() {
        val connection = CopilotStudioConnection(
            environmentId = "A47151CF-4F34-488F-B377-EBE84E17B478",
            schemaName = "cr7ab_myAgent"
        )

        assertEquals(
            "https://a47151cf4f34488fb377ebe84e17b4.78.environment.api.powerplatform.com" +
                "/copilotstudio/dataverse-backed/authenticated/bots/cr7ab_myAgent/conversations" +
                "?api-version=2022-03-01-preview",
            connection.conversationUrl()
        )

        assertTrue(connection.conversationUrl("a b/c").contains("/conversations/a%20b%2Fc?"))
    }

    @Test
    fun `token scope follows the cloud endpoint suffix`() {
        val prod = CopilotStudioConnection("A47151CF-4F34-488F-B377-EBE84E17B478", "bot")
        assertEquals("https://api.powerplatform.com/.default", prod.tokenScope)

        val mooncake = CopilotStudioConnection(
            "A47151CF-4F34-488F-B377-EBE84E17B478",
            "bot",
            PowerPlatformCloud.Mooncake
        )
        assertEquals(
            "https://api.powerplatform.partner.microsoftonline.cn/.default",
            mooncake.tokenScope
        )
    }

    @Test
    fun `unknown or blank cloud names fall back to prod`() {
        assertEquals(PowerPlatformCloud.Prod, PowerPlatformCloud.fromName(null))
        assertEquals(PowerPlatformCloud.Prod, PowerPlatformCloud.fromName(""))
        assertEquals(PowerPlatformCloud.Prod, PowerPlatformCloud.fromName("nonsense"))
        assertEquals(PowerPlatformCloud.Preprod, PowerPlatformCloud.fromName("preprod"))
    }
}
