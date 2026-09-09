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
 * The Direct-to-Engine endpoint uses the open Activity Protocol schema. Unknown fields are
 * deliberately ignored so newer protocol versions and channel extensions remain interoperable.
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
    val channelId: String? = null,
    val timestamp: String? = null,
    val localTimezone: String? = null,
    val localTimestamp: String? = null,
    val from: ChannelAccount? = null,
    val recipient: ChannelAccount? = null,
    val conversation: ConversationAccount? = null,
    val replyToId: String? = null,
    val entities: List<JsonObject> = emptyList(),
    val channelData: JsonElement? = null,
    val callerId: String? = null,
    val serviceUrl: String? = null,
    val deliveryMode: String? = null,
    val text: String? = null,
    val textFormat: String? = null,
    val locale: String? = null,
    val speak: String? = null,
    val inputHint: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val attachmentLayout: String? = null,
    val summary: String? = null,
    val suggestedActions: SuggestedActions? = null,
    val valueType: String? = null,
    val value: JsonElement? = null,
    val expiration: String? = null,
    val importance: String? = null,
    val listenFor: List<String> = emptyList(),
    val semanticAction: SemanticAction? = null,
    val relatesTo: ConversationReference? = null,
    val name: String? = null,
    val action: String? = null,
    val membersAdded: List<ChannelAccount> = emptyList(),
    val membersRemoved: List<ChannelAccount> = emptyList(),
    val topicName: String? = null,
    val historyDisclosed: Boolean? = null,
    val code: String? = null,
    val reactionsAdded: List<MessageReaction> = emptyList(),
    val reactionsRemoved: List<MessageReaction> = emptyList(),
    val textHighlights: List<TextHighlight> = emptyList(),
    val label: String? = null
) {
    val isMessage: Boolean get() = type.equals(ActivityTypes.MESSAGE, ignoreCase = true)
    val isTyping: Boolean get() = type.equals(ActivityTypes.TYPING, ignoreCase = true)

    /**
     * Streaming metadata lives either in a `streaminfo` entity or, for older agents, directly on
     * `channelData`. Both shapes are normalized here.
     */
    val streamInfo: StreamInfo?
        get() {
            val entity = entities.firstOrNull {
                it.string("type").equals(ENTITY_STREAM_INFO, ignoreCase = true)
            }
            val source = entity
                ?: (channelData as? JsonObject)?.takeIf { it.containsKey("streamType") }
                ?: return null
            return StreamInfo(
                streamId = source.string("streamId"),
                streamType = source.string("streamType"),
                streamSequence = (source["streamSequence"] as? JsonPrimitive)?.intOrNull,
                streamResult = source.string("streamResult")
            )
        }
}

private const val ENTITY_STREAM_INFO = "streaminfo"

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

data class StreamInfo(
    val streamId: String?,
    val streamType: String?,
    val streamSequence: Int?,
    val streamResult: String? = null
)

object ActivityTypes {
    const val MESSAGE = "message"
    const val CONTACT_RELATION_UPDATE = "contactRelationUpdate"
    const val CONVERSATION_UPDATE = "conversationUpdate"
    const val END_OF_CONVERSATION = "endOfConversation"
    const val EVENT = "event"
    const val INVOKE = "invoke"
    const val INSTALLATION_UPDATE = "installationUpdate"
    const val MESSAGE_DELETE = "messageDelete"
    const val MESSAGE_UPDATE = "messageUpdate"
    const val MESSAGE_REACTION = "messageReaction"
    const val SUGGESTION = "suggestion"
    const val TRACE = "trace"
    const val TYPING = "typing"
    const val HANDOFF = "handoff"
    const val COMMAND = "command"
    const val COMMAND_RESULT = "commandResult"
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
    val aadObjectId: String? = null,
    val agenticAppId: String? = null,
    val agenticUserId: String? = null,
    val role: String? = null
)

@Serializable
data class ConversationAccount(
    val id: String? = null,
    val name: String? = null,
    val aadObjectId: String? = null,
    val isGroup: Boolean? = null,
    val conversationType: String? = null,
    val role: String? = null,
    val tenantId: String? = null
)

@Serializable
data class ConversationReference(
    val channelId: String? = null,
    val activityId: String? = null,
    val conversation: ConversationAccount? = null,
    val user: ChannelAccount? = null,
    val bot: ChannelAccount? = null,
    val serviceUrl: String? = null,
    val locale: String? = null
)

@Serializable
data class Attachment(
    val contentType: String? = null,
    val content: JsonElement? = null,
    val contentUrl: String? = null,
    val name: String? = null,
    val thumbnailUrl: String? = null
) {
    val isAdaptiveCard: Boolean
        get() = contentType.equals(ADAPTIVE_CARD_CONTENT_TYPE, ignoreCase = true)

    companion object {
        const val ADAPTIVE_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.adaptive"
        const val HERO_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.hero"
        const val THUMBNAIL_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.thumbnail"
        const val ANIMATION_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.animation"
        const val AUDIO_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.audio"
        const val VIDEO_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.video"
        const val RECEIPT_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.receipt"
        const val SIGNIN_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.signin"
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
    val image: String? = null,
    val imageAltText: String? = null,
    val text: String? = null,
    val displayText: String? = null,
    val value: JsonElement? = null,
    val channelData: JsonElement? = null
) {
    /** The text to send back to the agent when the user taps this action. */
    val submitText: String
        get() = text
            ?: (value as? JsonPrimitive)?.contentOrNull
            ?: displayText
            ?: title.orEmpty()
}

@Serializable
data class MessageReaction(
    val type: String? = null
)

@Serializable
data class TextHighlight(
    val text: String? = null,
    val occurrence: Int? = null
)

@Serializable
data class SemanticAction(
    val state: String? = null,
    val id: String? = null,
    val entities: JsonObject? = null
)

/*
 * Activity Protocol Cards. Card payloads are carried in Attachment.content, which remains a
 * JsonElement because the attachment content type selects the card schema at runtime.
 */
@Serializable
data class HeroCard(
    val title: String? = null,
    val subtitle: String? = null,
    val text: String? = null,
    val images: List<CardImage> = emptyList(),
    val buttons: List<CardAction> = emptyList(),
    val tap: CardAction? = null
)

@Serializable
data class ThumbnailCard(
    val title: String? = null,
    val subtitle: String? = null,
    val text: String? = null,
    val images: List<CardImage> = emptyList(),
    val buttons: List<CardAction> = emptyList(),
    val tap: CardAction? = null
)

@Serializable
data class AnimationCard(
    val title: String? = null,
    val subtitle: String? = null,
    val image: ThumbnailUrl? = null,
    val media: List<MediaUrl> = emptyList(),
    val buttons: List<CardAction> = emptyList(),
    val shareable: Boolean? = null,
    val autoloop: Boolean? = null,
    val autostart: Boolean? = null,
    val aspect: String? = null,
    val duration: String? = null,
    val value: JsonElement? = null
)

@Serializable
data class AudioCard(
    val title: String? = null,
    val subtitle: String? = null,
    val image: ThumbnailUrl? = null,
    val media: List<MediaUrl> = emptyList(),
    val buttons: List<CardAction> = emptyList(),
    val shareable: Boolean? = null,
    val autoloop: Boolean? = null,
    val autostart: Boolean? = null,
    val aspect: String? = null,
    val duration: String? = null,
    val value: JsonElement? = null
)

@Serializable
data class VideoCard(
    val title: String? = null,
    val subtitle: String? = null,
    val image: ThumbnailUrl? = null,
    val media: List<MediaUrl> = emptyList(),
    val buttons: List<CardAction> = emptyList(),
    val shareable: Boolean? = null,
    val autoloop: Boolean? = null,
    val autostart: Boolean? = null,
    val aspect: String? = null,
    val duration: String? = null,
    val value: JsonElement? = null
)

@Serializable
data class ReceiptCard(
    val title: String? = null,
    val items: List<ReceiptItem> = emptyList(),
    val facts: List<Fact> = emptyList(),
    val tap: CardAction? = null,
    val total: String? = null,
    val tax: String? = null,
    val vat: String? = null,
    val buttons: List<CardAction> = emptyList()
)

@Serializable
data class SigninCard(
    val text: String? = null,
    val buttons: List<CardAction> = emptyList()
)

@Serializable
data class ThumbnailUrl(
    val url: String? = null,
    val alt: String? = null
)

@Serializable
data class CardImage(
    val url: String? = null,
    val alt: String? = null,
    val tap: CardAction? = null
)

@Serializable
data class MediaUrl(
    val url: String? = null,
    val profile: String? = null
)

@Serializable
data class ReceiptItem(
    val title: String? = null,
    val subtitle: String? = null,
    val text: String? = null,
    val image: CardImage? = null,
    val price: String? = null,
    val quantity: String? = null,
    val tap: CardAction? = null
)

@Serializable
data class Fact(
    val key: String? = null,
    val value: String? = null
)

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
