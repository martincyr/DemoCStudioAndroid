package com.martincyr.demoagentsdk.foundry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

data class FoundryEvent(
    val eventType: String,
    val text: String?,
    val rawJson: String
)

class FoundryException(val statusCode: Int, message: String) : IOException(message) {
    val isAuthFailure: Boolean get() = statusCode == 401 || statusCode == 403
}

class FoundryClient(
    private val settings: FoundryConnection,
    private val tokenSource: suspend (Boolean) -> String,
    httpClient: OkHttpClient? = null
) {
    private val http = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var threadId: String? = null

    fun start(): Flow<FoundryEvent> = channelFlow {
        try {
            createThread(tokenSource(false))
        } catch (error: FoundryException) {
            if (!error.isAuthFailure) throw error
            createThread(tokenSource(true))
        }
        publish(FoundryEvent("thread.created", null, "{\"thread_id\":\"$threadId\"}"), channel)
    }.flowOn(Dispatchers.IO)

    fun send(text: String): Flow<FoundryEvent> = channelFlow {
        val id = checkNotNull(threadId) { "The Foundry thread has not been started." }
        val token = tokenSource(false)
        try {
            postMessage(id, text, token)
            streamRun(id, token, channel)
        } catch (error: FoundryException) {
            if (!error.isAuthFailure) throw error
            postMessage(id, text, tokenSource(true))
            streamRun(id, tokenSource(false), channel)
        }
    }.flowOn(Dispatchers.IO)

    private fun createThread(token: String) {
        val response = execute(
            Request.Builder()
                .url(settings.url("threads"))
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON))
                .build()
        )
        response.use {
            val body = it.body.string()
            threadId = Json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content
                ?: throw IOException("Foundry did not return a thread id.")
        }
    }

    private fun postMessage(thread: String, text: String, token: String) {
        execute(
            Request.Builder()
                .url(settings.url("threads/$thread/messages"))
                .header("Authorization", "Bearer $token")
                .post("""{"role":"user","content":${Json.encodeToString(text)}}""".toRequestBody(JSON))
                .build()
        ).close()
    }

    private fun streamRun(thread: String, token: String, output: SendChannel<FoundryEvent>) {
        val request = Request.Builder()
            .url(settings.url("threads/$thread/runs?stream=true"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post("""{"assistant_id":"${settings.agentId}","stream":true}""".toRequestBody(JSON))
            .build()
        val call = http.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            var failure: Throwable? = null
            var ended = false
            EventSources.processResponse(response, object : EventSourceListener() {
                override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                    if (type.equals("done", true) || data == "[DONE]") {
                        ended = true
                        source.cancel()
                        return
                    }
                    if (data.isBlank()) return
                    val text = extractText(data)
                    if (!publish(FoundryEvent(type.orEmpty(), text, data), output)) {
                        ended = true
                        source.cancel()
                    }
                }

                override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
                    if (t != null && !ended && !call.isCanceled()) failure = t
                }
            })
            failure?.let { throw it }
        }
    }

    private fun execute(request: Request): Response {
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val error = response.toException()
            response.close()
            throw error
        }
        return response
    }

    private fun publish(event: FoundryEvent, output: SendChannel<FoundryEvent>): Boolean =
        output.trySendBlocking(event).isSuccess

    private fun extractText(raw: String): String? {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return null
        return root["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: root["text"]?.jsonPrimitive?.contentOrNull
            ?: root["content"]?.jsonPrimitive?.contentOrNull
    }

    private fun Response.toException() =
        FoundryException(code, "The Foundry request failed with status $code.")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

data class FoundryConnection(
    val projectEndpoint: String,
    val apiVersion: String,
    val agentId: String
) {
    init {
        require(projectEndpoint.isNotBlank()) { "Foundry projectEndpoint is required." }
        require(agentId.isNotBlank()) { "Foundry agentId is required." }
    }

    fun url(path: String): String =
        "${projectEndpoint.trimEnd('/')}/$path${if ('?' in path) '&' else '?'}api-version=$apiVersion"
}
