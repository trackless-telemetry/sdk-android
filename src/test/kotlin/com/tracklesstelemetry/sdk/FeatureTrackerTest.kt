package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [FeatureTracker] — per-session first-use deduplication.
 */
@DisplayName("FeatureTracker")
class FeatureTrackerTest {

    private lateinit var tracker: FeatureTracker

    @BeforeEach
    fun setUp() {
        tracker = FeatureTracker()
    }

    @Test
    @DisplayName("First use of a name returns true")
    fun firstUseReturnsTrue() {
        assertTrue(tracker.firstUse("export_clicked"))
    }

    @Test
    @DisplayName("Repeat use of the same name returns false")
    fun repeatUseReturnsFalse() {
        assertTrue(tracker.firstUse("export_clicked"))
        assertFalse(tracker.firstUse("export_clicked"))
        assertFalse(tracker.firstUse("export_clicked"))
    }

    @Test
    @DisplayName("Distinct names dedup independently")
    fun distinctNamesDedupIndependently() {
        assertTrue(tracker.firstUse("export_clicked"))
        assertTrue(tracker.firstUse("import_clicked"))
        assertFalse(tracker.firstUse("export_clicked"))
        assertFalse(tracker.firstUse("import_clicked"))
    }

    @Test
    @DisplayName("Clear resets first-use state")
    fun clearResetsState() {
        tracker.firstUse("export_clicked")
        tracker.clear()

        // After clear, the same name is a first use again.
        assertTrue(tracker.firstUse("export_clicked"))
    }
}
