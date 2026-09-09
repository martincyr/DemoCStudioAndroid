package com.martincyr.demoagentsdk.copilotstudio

/**
 * Reassembles text that the agent streams in chunks.
 *
 * Only `typing` activities carry partial text. Each chunk is tagged with a `streamId` and a
 * `streamSequence`; chunks may arrive out of order, so they are stored per stream and joined in
 * sequence order. `message` activities are always complete and pass through untouched.
 */
class StreamAccumulator {
    private val chunks = mutableMapOf<String, MutableMap<Int, String>>()

    /** The result of folding one activity into the accumulator. */
    sealed interface Update {
        /** Transient status text such as "Searching documents...". Replaced, never appended. */
        data class Informative(val text: String) : Update

        /** Cumulative answer text so far for [streamId]. */
        data class Partial(val streamId: String, val text: String) : Update

        /** A complete activity ready to be added to the transcript. */
        data class Complete(val activity: Activity) : Update

        /** Nothing renderable, e.g. a bare typing indicator. */
        data object Ignored : Update
    }

    fun accept(activity: Activity): Update {
        val info = activity.streamInfo

        if (activity.isMessage) {
            // The final message ends the stream it belongs to.
            info?.streamId?.let { chunks.remove(it) }
            return Update.Complete(activity)
        }

        if (!activity.isTyping) {
            return if (activity.text.isNullOrBlank()) {
                Update.Ignored
            } else {
                Update.Complete(activity)
            }
        }

        val text = activity.text.orEmpty()
        return when {
            info?.streamType.equals(StreamTypes.INFORMATIVE, ignoreCase = true) ->
                if (text.isBlank()) Update.Ignored else Update.Informative(text)

            info?.streamId != null && info.streamSequence != null -> {
                val stream = chunks.getOrPut(info.streamId) { sortedMapOf() }
                stream[info.streamSequence] = text
                Update.Partial(
                    streamId = info.streamId,
                    text = stream.entries.sortedBy { it.key }.joinToString("") { it.value }
                )
            }

            text.isNotBlank() && info?.streamId != null -> Update.Partial(info.streamId, text)

            else -> Update.Ignored
        }
    }

    fun reset() = chunks.clear()
}
