package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [SessionManager].
 */
@DisplayName("SessionManager")
class SessionManagerTest {

    private lateinit var session: SessionManager

    @BeforeEach
    fun setUp() {
        session = SessionManager()
    }

    @Test
    @DisplayName("Start returns true for new session")
    fun startReturnsTrue() {
        assertTrue(session.start())
        assertTrue(session.isActive)
    }

    @Test
    @DisplayName("Start returns false if already active")
    fun startReturnsFalseIfActive() {
        assertTrue(session.start())
        assertFalse(session.start())
    }

    @Test
    @DisplayName("End returns result with duration and depth")
    fun endReturnsDurationAndDepth() {
        session.start()
        session.recordActivity()
        session.recordActivity()
        session.recordActivity()

        val result = session.end()
        assertNotNull(result)
        assertEquals(3, result?.depth)
        assertTrue((result?.duration ?: -1) >= 0)
    }

    @Test
    @DisplayName("End returns null if no active session")
    fun endReturnsNullIfNoSession() {
        assertNull(session.end())
    }

    @Test
    @DisplayName("Record activity increments depth")
    fun recordActivityIncrementsDepth() {
        session.start()
        session.recordActivity()
        session.recordActivity()

        val result = session.end()
        assertEquals(2, result?.depth)
    }

    @Test
    @DisplayName("Record activity is no-op when no active session")
    fun recordActivityNoOpWhenInactive() {
        session.recordActivity() // Should not crash
    }

    @Test
    @DisplayName("Destroy resets session state")
    fun destroyResetsState() {
        session.start()
        session.recordActivity()
        session.destroy()

        assertFalse(session.isActive)
        assertNull(session.end())
    }
}
