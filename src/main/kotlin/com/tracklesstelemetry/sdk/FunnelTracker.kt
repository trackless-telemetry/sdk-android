package com.tracklesstelemetry.sdk

/**
 * In-memory funnel step deduplication per session.
 *
 * Tracks which step indices have been recorded per funnel name.
 * Prevents the same step from being counted twice in one session.
 *
 * Thread-safe: all methods are synchronized.
 */
internal class FunnelTracker {

    /** Map of funnel name -> set of completed step indices */
    private val funnels = mutableMapOf<String, MutableSet<Int>>()

    /**
     * Check and record a funnel step for deduplication.
     *
     * @param funnelName The funnel identifier
     * @param stepIndex The developer-defined step index (0-based)
     * @return true if the step was newly recorded, false if it was a duplicate
     */
    @Synchronized
    fun step(funnelName: String, stepIndex: Int): Boolean {
        val steps = funnels.getOrPut(funnelName) { mutableSetOf() }
        return steps.add(stepIndex)
    }

    /**
     * Clear all funnel state (e.g., on session end).
     */
    @Synchronized
    fun clear() {
        funnels.clear()
    }
}
