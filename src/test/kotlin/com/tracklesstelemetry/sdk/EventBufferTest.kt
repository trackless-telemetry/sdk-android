package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Tests for [EventBuffer].
 */
@DisplayName("EventBuffer")
class EventBufferTest {

    private val context = EventContext(
        platform = "android",
        osVersion = "34",
        deviceClass = "phone",
        region = "US",
    )

    private lateinit var buffer: EventBuffer

    @BeforeEach
    fun setUp() {
        buffer = EventBuffer()
    }

    @Test
    @DisplayName("Feature events aggregate count")
    fun featureEventsAggregateCount() {
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))

        assertEquals(1, buffer.totalSize)

        val payloads = buffer.drain("production", context)
        assertEquals(1, payloads.size)
        assertEquals(1, payloads[0].events.size)
        assertEquals(3, payloads[0].events[0].count)
    }

    @Test
    @DisplayName("View events aggregate count")
    fun viewEventsAggregateCount() {
        buffer.add(TracklessEvent(type = EventType.VIEW, name = "home"))
        buffer.add(TracklessEvent(type = EventType.VIEW, name = "home"))

        assertEquals(1, buffer.totalSize)

        val payloads = buffer.drain("production", context)
        assertEquals(2, payloads[0].events[0].count)
    }

    @Test
    @DisplayName("View events with different details create separate entries")
    fun viewEventsWithDifferentDetailsCreateSeparateEntries() {
        buffer.add(TracklessEvent(type = EventType.VIEW, name = "home"))
        buffer.add(TracklessEvent(type = EventType.VIEW, name = "home", detail = "tab_a"))
        buffer.add(TracklessEvent(type = EventType.VIEW, name = "home", detail = "tab_b"))

        assertEquals(3, buffer.totalSize)
    }

    @Test
    @DisplayName("Feature events with detail aggregate by detail")
    fun featureEventsWithDetailAggregateByDetail() {
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export", detail = "csv"))
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export", detail = "csv"))
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export", detail = "pdf"))

        assertEquals(2, buffer.totalSize)

        val payloads = buffer.drain("production", context)
        val events = payloads.flatMap { it.events }
        val csvEvent = events.find { it.detail == "csv" }
        assertEquals(2, csvEvent?.count)
    }

    @Test
    @DisplayName("Different features create separate entries")
    fun differentFeaturesCreateSeparateEntries() {
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "import_clicked"))

        assertEquals(2, buffer.totalSize)
    }

    @Test
    @DisplayName("Error events aggregate by severity and code")
    fun errorEventsAggregateBySeverityAndCode() {
        buffer.add(TracklessEvent(type = EventType.ERROR, name = "crash", severity = ErrorSeverity.FATAL, code = "E001"))
        buffer.add(TracklessEvent(type = EventType.ERROR, name = "crash", severity = ErrorSeverity.FATAL, code = "E001"))
        buffer.add(TracklessEvent(type = EventType.ERROR, name = "crash", severity = ErrorSeverity.FATAL, code = "E002"))

        assertEquals(2, buffer.totalSize)
    }

    @Test
    @DisplayName("Performance events merge durations")
    fun performanceEventsMergeDurations() {
        buffer.add(TracklessEvent(type = EventType.PERFORMANCE, name = "api_call", duration = 100.0))
        buffer.add(TracklessEvent(type = EventType.PERFORMANCE, name = "api_call", duration = 200.0))
        buffer.add(TracklessEvent(type = EventType.PERFORMANCE, name = "api_call", duration = 300.0))

        assertEquals(1, buffer.totalSize)

        val payloads = buffer.drain("production", context)
        val event = payloads[0].events[0]
        assertEquals(3, event.count)
        assertEquals(3, event.durations?.size)
        assertTrue(event.durations?.containsAll(listOf(100.0, 200.0, 300.0)) == true)
    }

    @Test
    @DisplayName("Session events are stored individually (not aggregated)")
    fun sessionEventsStoredIndividually() {
        buffer.add(TracklessEvent(type = EventType.SESSION, name = "start"))
        buffer.add(TracklessEvent(type = EventType.SESSION, name = "end", duration = 120.0, stepIndex = 5))

        assertEquals(2, buffer.totalSize)

        val payloads = buffer.drain("production", context)
        val events = payloads.flatMap { it.events }
        assertEquals(2, events.size)
    }

    @Test
    @DisplayName("Funnel events are stored individually (not aggregated)")
    fun funnelEventsStoredIndividually() {
        buffer.add(TracklessEvent(type = EventType.FUNNEL, name = "checkout", step = "cart", stepIndex = 0))
        buffer.add(TracklessEvent(type = EventType.FUNNEL, name = "checkout", step = "payment", stepIndex = 1))

        assertEquals(2, buffer.totalSize)
    }

    @Test
    @DisplayName("Bounded memory: buffer drops new entries at max capacity")
    fun boundedMemoryDropsNewEntries() {
        val smallBuffer = EventBuffer(100)

        for (i in 0 until 100) {
            assertTrue(smallBuffer.add(TracklessEvent(type = EventType.FEATURE, name = "feature_$i")))
        }
        assertEquals(100, smallBuffer.totalSize)

        // New feature should be dropped
        assertFalse(smallBuffer.add(TracklessEvent(type = EventType.FEATURE, name = "new_feature")))
        assertEquals(100, smallBuffer.totalSize)
    }

    @Test
    @DisplayName("Existing entries still accepted when buffer is full")
    fun existingEntriesAcceptedWhenFull() {
        val smallBuffer = EventBuffer(100)

        for (i in 0 until 100) {
            smallBuffer.add(TracklessEvent(type = EventType.FEATURE, name = "feature_$i"))
        }

        // Existing feature should still increment
        assertTrue(smallBuffer.add(TracklessEvent(type = EventType.FEATURE, name = "feature_0")))
    }

    @Test
    @DisplayName("Drain returns payloads and clears buffer")
    fun drainReturnsPayloadsAndClears() {
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "import_clicked"))

        assertEquals(2, buffer.totalSize)

        val payloads = buffer.drain("production", context)
        assertTrue(payloads.isNotEmpty())
        assertTrue(buffer.isEmpty)
    }

    @Test
    @DisplayName("Empty buffer produces empty payload list")
    fun emptyBufferProducesEmptyPayload() {
        assertTrue(buffer.isEmpty)
        val payloads = buffer.drain("production", context)
        assertTrue(payloads.isEmpty())
    }

    @Test
    @DisplayName("Clear discards all buffered data")
    fun clearDiscardsAllData() {
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))
        buffer.add(TracklessEvent(type = EventType.SESSION, name = "start"))
        assertTrue(buffer.totalSize > 0)

        buffer.clear()
        assertTrue(buffer.isEmpty)
    }

    @Test
    @DisplayName("Thread safety under concurrent access")
    fun threadSafetyUnderConcurrentAccess() {
        val threadCount = 10
        val eventsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until eventsPerThread) {
                        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "concurrent_event"))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals(1, buffer.totalSize)

        val payloads = buffer.drain("production", context)
        val totalCount = payloads.flatMap { it.events }.sumOf { it.count ?: 1 }
        assertEquals(threadCount * eventsPerThread, totalCount)
    }

    @Test
    @DisplayName("Payload includes correct environment and context")
    fun payloadIncludesCorrectEnvironmentAndContext() {
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))

        val payloads = buffer.drain("sandbox", context)
        assertEquals("sandbox", payloads[0].environment)
        assertEquals("android", payloads[0].context.platform)
        assertEquals("14.0", payloads[0].context.osVersion)
    }

    @Test
    @DisplayName("Payload has correct structure")
    fun payloadHasCorrectStructure() {
        buffer.add(TracklessEvent(type = EventType.FEATURE, name = "export_clicked"))

        val payloads = buffer.drain("production", context)
        val payload = payloads[0]

        // Verify structure (JSON serialization requires real org.json, not available in unit tests)
        assertEquals("production", payload.environment)
        assertEquals("android", payload.context.platform)
        assertEquals("14.0", payload.context.osVersion)
        assertEquals(1, payload.events.size)
        assertEquals(EventType.FEATURE, payload.events[0].type)
        assertEquals("export_clicked", payload.events[0].name)
    }

    @Test
    @DisplayName("Large buffer splits into multiple payloads")
    fun largeBufferSplitsIntoMultiplePayloads() {
        // Add more events than MAX_EVENTS_PER_PAYLOAD
        for (i in 0 until 150) {
            buffer.add(TracklessEvent(type = EventType.FEATURE, name = "feature_$i"))
        }

        val payloads = buffer.drain("production", context)
        assertTrue(payloads.size >= 2, "Should split into multiple payloads")
        val totalEvents = payloads.sumOf { it.events.size }
        assertEquals(150, totalEvents)
    }
}
