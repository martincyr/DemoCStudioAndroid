package com.martincyr.demoagentsdk.copilotstudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Failure carrying the HTTP status. The response body is never surfaced; it can contain tokens. */
class CopilotStudioException(
    val statusCode: Int,
    message: String
) : IOException(message) {
    val isAuthFailure: Boolean get() = statusCode == 401 || statusCode == 403
}

/**
 * Native Kotlin implementation of the Copilot Studio "Direct to Engine" protocol.
 *
 * Both endpoints are `POST`s that answer with a `text/event-stream` of Bot Framework activities,
 * so each call is exposed as a cold [Flow] that emits activities as they arrive and completes when
 * the stream ends.
 */
class DirectToEngineClient(
    private val connection: CopilotStudioConnection,
    private val tokenSource: TokenSource,
    httpClient: OkHttpClient? = null
) {
    // A streaming response can idle for a long time between activities, so reads must not time out.
    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    var conversationId: String? = null
        private set

    fun startConversation(): Flow<Activity> = stream(
        url = connection.conversationUrl(),
        body = CopilotStudioJson.encodeToString(StartConversationRequest())
    )

    /**
     * Sends an outgoing Activity Protocol message. Keeping the complete [Activity] here allows a
     * caller to send text, value, attachments, suggested actions, or any other protocol fields
     * without adding transport-specific overloads.
     */
    fun sendMessages(activity: Activity): Flow<Activity> = executeTurn(
        if (activity.type == null) activity.copy(type = ActivityTypes.MESSAGE) else activity
    )

    /** Convenience form for the common text-only message. */
    fun sendMessages(text: String): Flow<Activity> =
        sendMessages(Activity(type = ActivityTypes.MESSAGE, text = text))

    /** Convenience form for a text message with one attachment. */
    fun sendMessages(text: String, attachment: Attachment): Flow<Activity> =
        sendMessages(
            Activity(
                type = ActivityTypes.MESSAGE,
                text = text,
                attachments = listOf(attachment)
            )
        )

    /** Convenience form for a structured message value, such as an Adaptive Card submission. */
    fun sendMessages(value: JsonElement): Flow<Activity> =
        sendMessages(Activity(type = ActivityTypes.MESSAGE, value = value))

    private fun executeTurn(activity: Activity): Flow<Activity> {
        val id = checkNotNull(conversationId) { "The conversation has not been started yet." }
        val request = ExecuteTurnRequest(activity = activity, conversationId = id)
        return stream(
            url = connection.conversationUrl(id),
            body = CopilotStudioJson.encodeToString(request)
        )
    }

    private fun stream(url: String, body: String): Flow<Activity> = channelFlow {
        // A token that expired between turns surfaces as 401/403; retry once with a fresh one.
        try {
            execute(url, body, tokenSource.accessToken(forceRefresh = false), channel)
        } catch (error: CopilotStudioException) {
            if (!error.isAuthFailure) throw error
            execute(url, body, tokenSource.accessToken(forceRefresh = true), channel)
        }
    }.flowOn(Dispatchers.IO)

    private fun execute(
        url: String,
        body: String,
        token: String,
        output: SendChannel<Activity>
    ) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "text/event-stream")
            .addHeader("User-Agent", USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = http.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            response.header(HEADER_CONVERSATION_ID)?.let(::rememberConversationId)

            val responseBody = response.body
            val contentType = response.header("Content-Type").orEmpty()

            if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                // Some responses are a plain JSON envelope rather than a stream.
                val envelope = CopilotStudioJson.decodeFromString(
                    ActivitiesEnvelope.serializer(),
                    responseBody.string()
                )
                envelope.conversationId?.let(::rememberConversationId)
                envelope.activities.forEach { publish(it, output) }
                return
            }

            readEventStream(call, response, output)
        }
    }

    /**
     * Consumes the SSE body with OkHttp's `okhttp-sse` reader. [EventSources.processResponse] runs
     * the read loop on the calling thread, so failures are captured and rethrown afterwards rather
     * than escaping through the listener.
     *
     * The reader never auto-reconnects, which matters because these requests carry a body and are
     * not safe to replay.
     */
    private fun readEventStream(
        call: okhttp3.Call,
        response: Response,
        output: SendChannel<Activity>
    ) {
        var failure: Throwable? = null
        var closedIntentionally = false

        try {
            EventSources.processResponse(
                response,
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        when {
                            type.equals(EVENT_END, ignoreCase = true) -> {
                                closedIntentionally = true
                                eventSource.cancel()
                            }

                            type.equals(EVENT_ACTIVITY, ignoreCase = true) && data.isNotBlank() -> {
                                val activity = try {
                                    CopilotStudioJson.decodeFromString(Activity.serializer(), data)
                                } catch (error: Exception) {
                                    failure = error
                                    eventSource.cancel()
                                    return
                                }
                                if (!publish(activity, output)) {
                                    closedIntentionally = true
                                    eventSource.cancel()
                                }
                            }
                        }
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?
                    ) {
                        if (
                            t != null &&
                            !closedIntentionally &&
                            !call.isCanceled() &&
                            !t.isExpectedSseCancellation()
                        ) {
                            failure = t
                        }
                    }
                }
            )
        } catch (error: IOException) {
            if (!closedIntentionally && !error.isExpectedSseCancellation()) {
                failure = error
            }
        }

        failure?.let { throw it }
    }

    /** Returns `false` once the collector has gone away so the SSE reader can stop early. */
    private fun publish(activity: Activity, output: SendChannel<Activity>): Boolean {
        activity.conversation?.id?.let(::rememberConversationId)
        return output.trySendBlocking(activity).isSuccess
    }

    private fun rememberConversationId(id: String) {
        if (id.isNotBlank()) conversationId = id
    }

    private fun Response.toException(): CopilotStudioException {
        val detail = when (code) {
            401 -> "The Copilot Studio access token was rejected (401)."
            403 -> "Access to this agent was denied (403). Check that the app registration has " +
                "the Power Platform CopilotStudio.Copilots.Invoke delegated permission."
            404 -> "The agent was not found (404). Check environmentId and schemaName."
            else -> "The Copilot Studio request failed with status $code."
        }
        return CopilotStudioException(code, detail)
    }

    private fun Throwable.isExpectedSseCancellation(): Boolean =
        this is IOException && message.equals("canceled", ignoreCase = true)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val USER_AGENT = "DemoAgentSDK-NativeCopilotStudioClient/1.0 (Android)"
        const val HEADER_CONVERSATION_ID = "x-ms-conversationid"
        const val EVENT_ACTIVITY = "activity"
        const val EVENT_END = "end"
    }
}
