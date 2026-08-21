package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ErrorTracker] — per-session first-occurrence deduplication.
 */
@DisplayName("ErrorTracker")
class ErrorTrackerTest {

    private lateinit var tracker: ErrorTracker

    @BeforeEach
    fun setUp() {
        tracker = ErrorTracker()
    }

    @Test
    @DisplayName("First occurrence of a name returns true")
    fun firstOccurrenceReturnsTrue() {
        assertTrue(tracker.firstOccurrence("payment_failed"))
    }

    @Test
    @DisplayName("Repeat occurrence of the same name returns false")
    fun repeatOccurrenceReturnsFalse() {
        assertTrue(tracker.firstOccurrence("payment_failed"))
        assertFalse(tracker.firstOccurrence("payment_failed"))
        assertFalse(tracker.firstOccurrence("payment_failed"))
    }

    @Test
    @DisplayName("Distinct names dedup independently")
    fun distinctNamesDedupIndependently() {
        assertTrue(tracker.firstOccurrence("payment_failed"))
        assertTrue(tracker.firstOccurrence("api_timeout"))
        assertFalse(tracker.firstOccurrence("payment_failed"))
        assertFalse(tracker.firstOccurrence("api_timeout"))
    }

    @Test
    @DisplayName("Clear resets first-occurrence state")
    fun clearResetsState() {
        tracker.firstOccurrence("payment_failed")
        tracker.clear()

        // After clear, the same name is a first occurrence again.
        assertTrue(tracker.firstOccurrence("payment_failed"))
    }
}
