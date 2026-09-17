package com.martincyr.demoagentsdk.copilotstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** One entry in the transcript. */
data class TranscriptItem(
    val id: String = UUID.randomUUID().toString(),
    val author: Author,
    val text: String,
    val suggestedActions: List<CardAction> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val isStreaming: Boolean = false,
    val textFormat: String? = null,
    val activityMarkers: List<ActivityMarker> = emptyList()
) {
    /** Copilot Studio agents usually answer in markdown; plain text is the explicit opt-out. */
    val isMarkdown: Boolean
        get() = author == Author.Agent && !textFormat.equals("plain", ignoreCase = true)

    enum class Author { User, Agent }
}

data class ActivityMarker(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val json: String
)

enum class ConnectionState { Idle, Connecting, Ready, Failed }

/**
 * Drives a Copilot Studio conversation and exposes it as Compose state.
 *
 * This is a [ViewModel] so the conversation — including the server-side `conversationId` and the
 * transcript — survives configuration changes. Recreating it on every rotation would abandon the
 * conversation and start a new one.
 *
 * The transcript is a snapshot list so partial streaming updates can mutate the last agent entry
 * in place instead of appending a new bubble per chunk.
 */
class NativeChatViewModel(
    private val client: DirectToEngineClient
) : ViewModel() {
    private val accumulator = StreamAccumulator()
    private var activeJob: Job? = null
    private var streamingItemId: String? = null

    val transcript: SnapshotStateList<TranscriptItem> = mutableStateListOf()

    var connectionState by mutableStateOf(ConnectionState.Idle)
        private set

    var statusText by mutableStateOf<String?>(null)
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    var isBusy by mutableStateOf(false)
        private set

    val canSend: Boolean
        get() = connectionState == ConnectionState.Ready && !isBusy

    /** Safe to call on every recomposition; only the first call actually connects. */
    fun startIfNeeded() {
        if (connectionState != ConnectionState.Idle) return
        start()
    }

    fun start() {
        if (connectionState == ConnectionState.Connecting || activeJob?.isActive == true) return
        transcript.clear()
        accumulator.reset()
        errorText = null
        connectionState = ConnectionState.Connecting
        run("Starting the conversation...") { client.startConversation() }
    }

    fun sendMessages(activity: Activity) {
        if (!canSend) return
        val text = activity.text.orEmpty().trim()
        val displayText = text.ifBlank {
            when {
                activity.attachments.isNotEmpty() -> "Attachment sent"
                activity.value != null -> "(card submitted)"
                else -> ""
            }
        }
        transcript += TranscriptItem(
            author = TranscriptItem.Author.User,
            text = displayText,
            attachments = activity.attachments,
            textFormat = activity.textFormat
        )
        clearSuggestedActions()
        val status = if (activity.attachments.isNotEmpty()) {
            "Sending attachment to the agent..."
        } else {
            "Waiting for the agent..."
        }
        run(status) { client.sendMessages(activity) }
    }

    fun sendMessages(text: String) {
        sendMessages(Activity(type = ActivityTypes.MESSAGE, text = text))
    }

    fun sendMessages(text: String, attachment: Attachment) {
        sendMessages(
            Activity(
                type = ActivityTypes.MESSAGE,
                text = text,
                attachments = listOf(attachment)
            )
        )
    }

    fun sendMessages(value: JsonElement) {
        sendMessages(Activity(type = ActivityTypes.MESSAGE, value = value))
    }

    fun retry() {
        errorText = null
        if (client.conversationId == null) {
            connectionState = ConnectionState.Idle
            start()
        } else {
            statusText = null
        }
    }

    override fun onCleared() {
        activeJob?.cancel()
        activeJob = null
    }

    private fun run(status: String, request: () -> Flow<Activity>) {
        isBusy = true
        statusText = status
        errorText = null
        streamingItemId = null

        activeJob = viewModelScope.launch {
            try {
                request().collect(::consume)
                finishStreamingItem()
                connectionState = ConnectionState.Ready
                statusText = null
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                finishStreamingItem()
                errorText = error.message ?: "The Copilot Studio request failed."
                statusText = null
                if (connectionState != ConnectionState.Ready) {
                    connectionState = ConnectionState.Failed
                }
            } finally {
                isBusy = false
            }
        }
    }

    private fun consume(activity: Activity) {
        when (val update = accumulator.accept(activity)) {
            is StreamAccumulator.Update.Informative -> {
                statusText = update.text
                appendActivityMarker(update.activity)
            }

            is StreamAccumulator.Update.Partial -> upsertStreamingItem(update.text, update.activity)

            is StreamAccumulator.Update.Complete -> {
                val text = update.activity.text.orEmpty()
                val actions = update.activity.suggestedActions?.actions.orEmpty()
                val attachments = update.activity.attachments
                val marker = update.activity.toMarker()
                if (text.isBlank() && actions.isEmpty() && attachments.isEmpty()) {
                    appendActivityMarker(marker)
                    return
                }

                // A final message supersedes the text streamed for the same turn.
                val streamingIndex = streamingItemId?.let { id ->
                    transcript.indexOfFirst { it.id == id }
                } ?: -1
                val item = TranscriptItem(
                    author = TranscriptItem.Author.Agent,
                    text = text,
                    suggestedActions = actions,
                    attachments = attachments,
                    textFormat = update.activity.textFormat,
                    activityMarkers = listOf(marker)
                )
                if (streamingIndex >= 0) {
                    val current = transcript[streamingIndex]
                    transcript[streamingIndex] = item.copy(
                        id = current.id,
                        activityMarkers = current.activityMarkers + marker
                    )
                } else {
                    transcript += item
                }
                streamingItemId = null
                statusText = null
            }

            is StreamAccumulator.Update.Ignored -> appendActivityMarker(update.activity)
        }
    }

    private fun upsertStreamingItem(text: String, activity: Activity) {
        statusText = null
        val marker = activity.toMarker()
        val id = streamingItemId
        val index = id?.let { current -> transcript.indexOfFirst { it.id == current } } ?: -1
        if (index >= 0) {
            transcript[index] = transcript[index].copy(
                text = text,
                isStreaming = true,
                activityMarkers = transcript[index].activityMarkers + marker
            )
        } else {
            val item = TranscriptItem(
                author = TranscriptItem.Author.Agent,
                text = text,
                isStreaming = true,
                activityMarkers = listOf(marker)
            )
            transcript += item
            streamingItemId = item.id
        }
    }

    private fun appendActivityMarker(activity: Activity) {
        appendActivityMarker(activity.toMarker())
    }

    private fun appendActivityMarker(marker: ActivityMarker) {
        transcript += TranscriptItem(
            author = TranscriptItem.Author.Agent,
            text = "",
            activityMarkers = listOf(marker)
        )
    }

    private fun finishStreamingItem() {
        val id = streamingItemId ?: return
        val index = transcript.indexOfFirst { it.id == id }
        if (index >= 0) {
            transcript[index] = transcript[index].copy(isStreaming = false)
        }
        streamingItemId = null
    }

    private fun clearSuggestedActions() {
        transcript.indices
            .filter { transcript[it].suggestedActions.isNotEmpty() }
            .forEach { transcript[it] = transcript[it].copy(suggestedActions = emptyList()) }
    }

    /**
     * The client depends on runtime configuration (app settings and the MSAL provider), so the
     * view model cannot be constructed reflectively.
     */
    class Factory(
        private val clientProvider: () -> DirectToEngineClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeChatViewModel(clientProvider()) as T
    }
}

private fun Activity.toMarker(): ActivityMarker {
    val labelParts = listOfNotNull(
        type?.takeIf { it.isNotBlank() },
        name?.takeIf { it.isNotBlank() }
    )
    return ActivityMarker(
        label = labelParts.joinToString(": ").ifBlank { "activity" },
        json = toPrettyJson()
    )
}
