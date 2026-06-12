package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [EventBuffer.splitBySize] — the 50KB serialized request body limit.
 */
@DisplayName("Payload Size Limit")
class PayloadSizeTest {

    private val testContext = EventContext(platform = "android")

    private fun makePayload(events: List<TracklessEvent>) = EventPayload(
        date = "2026-06-10",
        environment = "production",
        context = testContext,
        events = events,
    )

    private fun encodedSize(payload: EventPayload): Int =
        payload.toJsonString().toByteArray(Charsets.UTF_8).size

    /** A single event whose encoded form exceeds the 50KB body limit on its own. */
    private val oversizedEvent: TracklessEvent
        get() = TracklessEvent(
            type = EventType.PERFORMANCE,
            name = "giant_trace",
            durations = MutableList(8000) { 123.456789 },
        )

    @Test
    @DisplayName("Limit matches the server's 50KB request body limit")
    fun limitMatchesServer() {
        assertEquals(50 * 1024, EventBuffer.MAX_PAYLOAD_BYTES)
    }

    @Test
    @DisplayName("Payload under the limit passes through unchanged")
    fun underLimitPassesThrough() {
        val payload = makePayload(
            listOf(
                TracklessEvent(type = EventType.FEATURE, name = "export_clicked", count = 3),
                TracklessEvent(type = EventType.VIEW, name = "home", count = 1),
            )
        )

        val result = EventBuffer.splitBySize(payload)
        assertEquals(listOf(payload), result.payloads)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    @DisplayName("Oversized payload splits recursively into size-compliant payloads preserving event order")
    fun oversizedPayloadSplits() {
        val events = (0 until 40).map {
            TracklessEvent(type = EventType.FEATURE, name = "feature_${"%02d".format(it)}", count = 1)
        }
        val payload = makePayload(events)
        val limit = 600
        assertTrue(encodedSize(payload) > limit)

        val result = EventBuffer.splitBySize(payload, limit)
        assertTrue(result.dropped.isEmpty())
        assertTrue(result.payloads.size > 1)

        for (sized in result.payloads) {
            assertTrue(encodedSize(sized) <= limit)
            // Wire format unchanged — same envelope on every split payload.
            assertEquals(payload.date, sized.date)
            assertEquals(payload.environment, sized.environment)
            assertEquals(payload.context, sized.context)
        }

        val flattened = result.payloads.flatMap { it.events }
        assertEquals(events, flattened)
    }

    @Test
    @DisplayName("Single event exceeding the limit is dropped")
    fun oversizedSingleEventDropped() {
        val huge = oversizedEvent
        val payload = makePayload(listOf(huge))
        assertTrue(encodedSize(payload) > EventBuffer.MAX_PAYLOAD_BYTES)

        val result = EventBuffer.splitBySize(payload)
        assertTrue(result.payloads.isEmpty())
        assertEquals(listOf(huge), result.dropped)
    }

    @Test
    @DisplayName("Oversized single event is dropped while surrounding events are kept")
    fun mixedOversizedEventDropped() {
        val small1 = TracklessEvent(type = EventType.FEATURE, name = "small_one", count = 5)
        val huge = oversizedEvent
        val small2 = TracklessEvent(type = EventType.VIEW, name = "small_two", count = 2)
        val payload = makePayload(listOf(small1, huge, small2))

        val result = EventBuffer.splitBySize(payload)
        assertEquals(listOf(huge), result.dropped)

        val kept = result.payloads.flatMap { it.events }
        assertEquals(listOf(small1, small2), kept)

        for (sized in result.payloads) {
            assertTrue(encodedSize(sized) <= EventBuffer.MAX_PAYLOAD_BYTES)
        }
    }
}
