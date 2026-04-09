package com.tracklesstelemetry.sdk

/**
 * In-memory session manager.
 *
 * Tracks session duration and depth (screen/activity count).
 *
 * Thread-safe: all methods are synchronized.
 */
internal class SessionManager {

    data class SessionResult(
        val duration: Int,
        val depth: Int,
    )

    @Volatile
    private var active = false

    @Volatile
    private var startTimeMs = 0L

    @Volatile
    private var depth = 0

    /**
     * Start a new session.
     *
     * @return true if a new session was started, false if already active
     */
    @Synchronized
    fun start(): Boolean {
        if (active) return false
        active = true
        startTimeMs = System.currentTimeMillis()
        depth = 0
        return true
    }

    /**
     * End the current session.
     *
     * @return session result with duration (seconds) and depth, or null if no active session
     */
    @Synchronized
    fun end(): SessionResult? {
        if (!active) return null
        active = false
        val durationMs = System.currentTimeMillis() - startTimeMs
        val result = SessionResult(
            duration = (durationMs / 1000).toInt(),
            depth = depth,
        )
        depth = 0
        startTimeMs = 0L
        return result
    }

    /**
     * Record user activity (e.g., screen view). Increments depth.
     */
    @Synchronized
    fun recordActivity() {
        if (!active) return
        depth++
    }

    /**
     * Destroy the session without returning a result.
     */
    @Synchronized
    fun destroy() {
        active = false
        depth = 0
        startTimeMs = 0L
    }

    /**
     * Whether a session is currently active.
     */
    val isActive: Boolean
        get() = active
}
