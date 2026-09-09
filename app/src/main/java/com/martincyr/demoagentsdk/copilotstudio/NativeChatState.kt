package com.martincyr.demoagentsdk.copilotstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

/** One entry in the transcript. */
data class TranscriptItem(
    val id: String = UUID.randomUUID().toString(),
    val author: Author,
    val text: String,
    val suggestedActions: List<CardAction> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val isStreaming: Boolean = false
) {
    enum class Author { User, Agent }
}

enum class ConnectionState { Idle, Connecting, Ready, Failed }

/**
 * Drives a Copilot Studio conversation and exposes it as Compose state.
 *
 * The transcript is a snapshot list so partial streaming updates can mutate the last agent entry
 * in place instead of appending a new bubble per chunk.
 */
class NativeChatState(
    private val client: DirectToEngineClient,
    private val scope: CoroutineScope
) {
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

    fun start() {
        if (connectionState == ConnectionState.Connecting || activeJob?.isActive == true) return
        transcript.clear()
        accumulator.reset()
        errorText = null
        connectionState = ConnectionState.Connecting
        run("Starting the conversation...") { client.startConversation() }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !canSend) return
        transcript += TranscriptItem(author = TranscriptItem.Author.User, text = trimmed)
        // Once the user replies, the previous turn's suggestions are stale.
        clearSuggestedActions()
        run("Waiting for the agent...") { client.sendMessage(trimmed) }
    }

    fun retry() {
        errorText = null
        if (client.conversationId == null) start() else statusText = null
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        isBusy = false
    }

    private fun run(status: String, request: () -> Flow<Activity>) {
        isBusy = true
        statusText = status
        errorText = null
        streamingItemId = null

        activeJob = scope.launch {
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
            is StreamAccumulator.Update.Informative -> statusText = update.text

            is StreamAccumulator.Update.Partial -> upsertStreamingItem(update.text)

            is StreamAccumulator.Update.Complete -> {
                val text = update.activity.text.orEmpty()
                val actions = update.activity.suggestedActions?.actions.orEmpty()
                val attachments = update.activity.attachments
                if (text.isBlank() && actions.isEmpty() && attachments.isEmpty()) return

                // A final message supersedes the text streamed for the same turn.
                val streamingIndex = streamingItemId?.let { id ->
                    transcript.indexOfFirst { it.id == id }
                } ?: -1
                val item = TranscriptItem(
                    author = TranscriptItem.Author.Agent,
                    text = text,
                    suggestedActions = actions,
                    attachments = attachments
                )
                if (streamingIndex >= 0) {
                    transcript[streamingIndex] = item.copy(id = transcript[streamingIndex].id)
                } else {
                    transcript += item
                }
                streamingItemId = null
                statusText = null
            }

            StreamAccumulator.Update.Ignored -> Unit
        }
    }

    private fun upsertStreamingItem(text: String) {
        statusText = null
        val id = streamingItemId
        val index = id?.let { current -> transcript.indexOfFirst { it.id == current } } ?: -1
        if (index >= 0) {
            transcript[index] = transcript[index].copy(text = text, isStreaming = true)
        } else {
            val item = TranscriptItem(
                author = TranscriptItem.Author.Agent,
                text = text,
                isStreaming = true
            )
            transcript += item
            streamingItemId = item.id
        }
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
}
