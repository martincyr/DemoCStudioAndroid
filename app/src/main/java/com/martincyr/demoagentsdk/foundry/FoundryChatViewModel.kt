package com.martincyr.demoagentsdk.foundry

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class FoundryTranscriptItem(
    val user: Boolean,
    val text: String,
    val rawJson: String = ""
)

class FoundryChatViewModel(
    private val client: FoundryClient
) : ViewModel() {
    val transcript = mutableStateListOf<FoundryTranscriptItem>()
    var isReady = mutableStateOf(false)
        private set
    var isBusy = mutableStateOf(false)
        private set
    var error = mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true || isReady.value) return
        run {
            client.start().collect { event ->
                if (event.eventType == "conversation.created") isReady.value = true
            }
        }
    }

    fun send(text: String) {
        val value = text.trim()
        if (value.isEmpty() || !isReady.value || isBusy.value) return
        transcript += FoundryTranscriptItem(true, value)
        run {
            client.send(value).collect { event ->
                val response = event.text.orEmpty()
                if (response.isNotEmpty()) {
                    val last = transcript.lastOrNull()
                    if (last?.user == false) {
                        transcript[transcript.lastIndex] = last.copy(
                            text = last.text + response,
                            rawJson = event.rawJson
                        )
                    } else {
                        transcript += FoundryTranscriptItem(false, response, event.rawJson)
                    }
                }
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        isBusy.value = true
        error.value = null
        job = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error.value = failure.message ?: "The Foundry request failed."
            } finally {
                isBusy.value = false
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
