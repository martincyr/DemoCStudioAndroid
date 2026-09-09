package com.martincyr.demoagentsdk.copilotstudio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Copilot Studio Direct-to-Engine endpoint returns Bot Framework activities whose schema is
 * open-ended, so unknown keys are ignored rather than treated as errors.
 */
val CopilotStudioJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

@Serializable
data class Activity(
    val type: String? = null,
    val id: String? = null,
    val text: String? = null,
    val textFormat: String? = null,
    val speak: String? = null,
    val locale: String? = null,
    val timestamp: String? = null,
    val replyToId: String? = null,
    val name: String? = null,
    val from: ChannelAccount? = null,
    val recipient: ChannelAccount? = null,
    val conversation: ConversationAccount? = null,
    val suggestedActions: SuggestedActions? = null,
    val attachments: List<Attachment> = emptyList(),
    val entities: List<JsonObject> = emptyList(),
    val channelData: JsonObject? = null,
    val value: JsonElement? = null
) {
    val isMessage: Boolean get() = type.equals(ActivityTypes.MESSAGE, ignoreCase = true)
    val isTyping: Boolean get() = type.equals(ActivityTypes.TYPING, ignoreCase = true)

    /**
     * Streaming metadata lives either in a `streaminfo` entity or, for older agents, directly on
     * `channelData`. Both shapes are normalised here.
     */
    val streamInfo: StreamInfo?
        get() {
            val entity = entities.firstOrNull {
                it.string("type").equals(ENTITY_STREAM_INFO, ignoreCase = true)
            }
            val source = entity
                ?: channelData?.takeIf { it.containsKey("streamType") }
                ?: return null
            return StreamInfo(
                streamId = source.string("streamId"),
                streamType = source.string("streamType"),
                streamSequence = (source["streamSequence"] as? JsonPrimitive)?.intOrNull
            )
        }
}

private const val ENTITY_STREAM_INFO = "streaminfo"

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

data class StreamInfo(
    val streamId: String?,
    val streamType: String?,
    val streamSequence: Int?
)

object ActivityTypes {
    const val MESSAGE = "message"
    const val TYPING = "typing"
    const val EVENT = "event"
    const val END_OF_CONVERSATION = "endOfConversation"
}

object StreamTypes {
    /** Transient status text ("Searching documents..."); never appended to the answer. */
    const val INFORMATIVE = "informative"

    /** A partial chunk of the answer; appended in `streamSequence` order. */
    const val STREAMING = "streaming"

    /** The complete answer; replaces anything accumulated so far. */
    const val FINAL = "final"
}

@Serializable
data class ChannelAccount(
    val id: String? = null,
    val name: String? = null,
    val role: String? = null
)

@Serializable
data class ConversationAccount(
    val id: String? = null,
    val name: String? = null
)

@Serializable
data class Attachment(
    val contentType: String? = null,
    val contentUrl: String? = null,
    val name: String? = null,
    val thumbnailUrl: String? = null,
    val content: JsonElement? = null
) {
    val isAdaptiveCard: Boolean
        get() = contentType.equals(ADAPTIVE_CARD_CONTENT_TYPE, ignoreCase = true)

    companion object {
        const val ADAPTIVE_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.adaptive"
    }
}

@Serializable
data class SuggestedActions(
    val to: List<String> = emptyList(),
    val actions: List<CardAction> = emptyList()
)

@Serializable
data class CardAction(
    val type: String? = null,
    val title: String? = null,
    val text: String? = null,
    val displayText: String? = null,
    val value: JsonElement? = null
) {
    /** The text to send back to the agent when the user taps this action. */
    val submitText: String
        get() = text
            ?: (value as? JsonPrimitive)?.contentOrNull
            ?: displayText
            ?: title.orEmpty()
}

@Serializable
internal data class StartConversationRequest(
    val emitStartConversationEvent: Boolean = true,
    val locale: String? = null
)

@Serializable
internal data class ExecuteTurnRequest(
    val activity: Activity,
    val conversationId: String? = null
)

/** Shape returned when the service answers with `application/json` instead of an SSE stream. */
@Serializable
internal data class ActivitiesEnvelope(
    val activities: List<Activity> = emptyList(),
    @SerialName("conversationId") val conversationId: String? = null
)
