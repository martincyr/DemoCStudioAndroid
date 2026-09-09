package com.martincyr.demoagentsdk.copilotstudio

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAccumulatorTest {
    private val accumulator = StreamAccumulator()

    private fun typing(
        text: String,
        streamId: String? = null,
        streamType: String? = null,
        sequence: Int? = null
    ) = Activity(
        type = ActivityTypes.TYPING,
        text = text,
        entities = listOfNotNull(
            if (streamId == null && streamType == null) {
                null
            } else {
                buildJsonObject {
                    put("type", "streaminfo")
                    streamId?.let { put("streamId", it) }
                    streamType?.let { put("streamType", it) }
                    sequence?.let { put("streamSequence", it) }
                }
            }
        )
    )

    @Test
    fun `accumulates streaming chunks in sequence order`() {
        accumulator.accept(typing("Hello ", "s1", StreamTypes.STREAMING, 1))
        val update = accumulator.accept(typing("world", "s1", StreamTypes.STREAMING, 2))

        assertEquals(StreamAccumulator.Update.Partial("s1", "Hello world"), update)
    }

    @Test
    fun `reorders chunks that arrive out of sequence`() {
        accumulator.accept(typing("world", "s1", StreamTypes.STREAMING, 2))
        val update = accumulator.accept(typing("Hello ", "s1", StreamTypes.STREAMING, 1))

        assertEquals(StreamAccumulator.Update.Partial("s1", "Hello world"), update)
    }

    @Test
    fun `informative chunks are surfaced as status and never accumulated`() {
        val update = accumulator.accept(
            typing("Searching documents...", "s1", StreamTypes.INFORMATIVE, 1)
        )

        assertEquals(StreamAccumulator.Update.Informative("Searching documents..."), update)
    }

    @Test
    fun `message activities pass through complete and end their stream`() {
        accumulator.accept(typing("partial", "s1", StreamTypes.STREAMING, 1))

        val message = Activity(type = ActivityTypes.MESSAGE, text = "Final answer.")
        val update = accumulator.accept(message)

        assertTrue(update is StreamAccumulator.Update.Complete)
        assertEquals("Final answer.", (update as StreamAccumulator.Update.Complete).activity.text)
    }

    @Test
    fun `bare typing indicators are ignored`() {
        assertEquals(StreamAccumulator.Update.Ignored, accumulator.accept(typing("")))
    }

    @Test
    fun `separate streams accumulate independently`() {
        accumulator.accept(typing("A1", "a", StreamTypes.STREAMING, 1))
        accumulator.accept(typing("B1", "b", StreamTypes.STREAMING, 1))
        val update = accumulator.accept(typing("A2", "a", StreamTypes.STREAMING, 2))

        assertEquals(StreamAccumulator.Update.Partial("a", "A1A2"), update)
    }
}
