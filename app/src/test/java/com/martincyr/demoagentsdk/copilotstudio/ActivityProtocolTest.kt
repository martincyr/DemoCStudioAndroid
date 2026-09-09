package com.martincyr.demoagentsdk.copilotstudio

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityProtocolTest {
    @Test
    fun `message round trips protocol fields and ignores extensions`() {
        val json = """
            {
              "type": "message",
              "text": "Hello",
              "textFormat": "markdown",
              "value": {"intent": "greeting"},
              "attachments": [{
                "contentType": "image/jpeg",
                "contentUrl": "data:image/jpeg;base64,abc",
                "name": "photo.jpg"
              }],
              "suggestedActions": {
                "to": ["user-1"],
                "actions": [{"type": "imBack", "title": "Continue", "value": "go"}]
              },
              "newFieldFromFuture": true
            }
        """.trimIndent()

        val activity = CopilotStudioJson.decodeFromString<Activity>(json)
        val encoded = CopilotStudioJson.encodeToString(activity)

        assertEquals("Hello", activity.text)
        assertEquals("image/jpeg", activity.attachments.single().contentType)
        assertEquals("user-1", activity.suggestedActions?.to?.single())
        assertEquals("go", activity.suggestedActions?.actions?.single()?.submitText)
        assertTrue(encoded.contains("\"text\":\"Hello\""))
        assertTrue(!encoded.contains("newFieldFromFuture"))
    }

    @Test
    fun `attachment preserves structured content`() {
        val attachment = Attachment(
            contentType = Attachment.ADAPTIVE_CARD_CONTENT_TYPE,
            content = buildJsonObject {
                put("type", "AdaptiveCard")
                put("version", "1.6")
                put("customData", JsonPrimitive("kept"))
            }
        )

        val decoded = CopilotStudioJson.decodeFromString<Attachment>(
            CopilotStudioJson.encodeToString(attachment)
        )

        assertTrue(decoded.isAdaptiveCard)
        assertEquals("AdaptiveCard", decoded.content?.jsonObject?.get("type")?.toString()?.trim('"'))
        assertEquals("kept", decoded.content?.jsonObject?.get("customData")?.toString()?.trim('"'))
    }

    @Test
    fun `cards deserialize with actions and nested content`() {
        val hero = CopilotStudioJson.decodeFromString<HeroCard>(
            """
            {
              "title": "Choose a route",
              "images": [{"url": "https://example.test/map.png", "alt": "Map"}],
              "buttons": [{"type": "openUrl", "title": "Open", "value": "https://example.test"}],
              "tap": {"type": "imBack", "title": "Select", "value": "route"}
            }
            """.trimIndent()
        )
        val receipt = CopilotStudioJson.decodeFromString<ReceiptCard>(
            """
            {
              "title": "Order",
              "items": [{"title": "Coffee", "price": "${'$'}4", "quantity": "2"}],
              "facts": [{"key": "Total", "value": "${'$'}8"}],
              "buttons": []
            }
            """.trimIndent()
        )

        assertEquals("Choose a route", hero.title)
        assertEquals("https://example.test", hero.buttons.single().value?.toString()?.trim('"'))
        assertEquals("route", (hero.tap?.value as JsonPrimitive).content)
        assertEquals("Coffee", receipt.items.single().title)
        assertEquals("${'$'}8", receipt.facts.single().value)
    }
}
