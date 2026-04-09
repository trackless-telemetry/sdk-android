package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [FunnelTracker].
 */
@DisplayName("FunnelTracker")
class FunnelTrackerTest {

    private lateinit var tracker: FunnelTracker

    @BeforeEach
    fun setUp() {
        tracker = FunnelTracker()
    }

    @Test
    @DisplayName("Accepts explicit step indices")
    fun acceptsExplicitIndices() {
        assertTrue(tracker.step("checkout", 0))
        assertTrue(tracker.step("checkout", 1))
        assertTrue(tracker.step("checkout", 2))
    }

    @Test
    @DisplayName("Deduplicates repeated step indices")
    fun deduplicatesRepeatedStepIndices() {
        assertTrue(tracker.step("checkout", 0))
        assertFalse(tracker.step("checkout", 0))
    }

    @Test
    @DisplayName("Tracks independent funnels")
    fun tracksIndependentFunnels() {
        assertTrue(tracker.step("checkout", 0))
        assertTrue(tracker.step("onboarding", 0))
        assertTrue(tracker.step("checkout", 1))
    }

    @Test
    @DisplayName("Clear resets all funnels")
    fun clearResetsAllFunnels() {
        tracker.step("checkout", 0)
        tracker.step("checkout", 1)
        tracker.clear()

        // After clear, same step indices should be accepted again
        assertTrue(tracker.step("checkout", 0))
    }
}
