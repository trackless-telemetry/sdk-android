package com.tracklesstelemetry.sdk

/**
 * In-memory feature first-use deduplication per session.
 *
 * Tracks which normalized feature names have already been used in the current
 * session so the first use of each name can be marked with `firstUses = 1`,
 * feeding server-side session-reach analytics. Subsequent uses of the same name
 * within the session are not marked.
 *
 * Dedup is keyed on the normalized feature `name` only (not `detail`), so a
 * session that uses several `detail` variants of one feature contributes exactly
 * one first-use — matching the reach metric's per-name semantics.
 *
 * The set lives in session state (not the event buffer): it must survive buffer
 * flushes and reset only at session end. Cleared exactly where [FunnelTracker]
 * clears.
 *
 * Thread-safe: all methods are synchronized.
 */
internal class FeatureTracker {

    /** Set of normalized feature names used so far in the current session. */
    private val seen = mutableSetOf<String>()

    /**
     * Check and record the first use of a feature name for this session.
     *
     * @param name The normalized feature name
     * @return true if this is the first use of the name in the session,
     *   false if it was already used
     */
    @Synchronized
    fun firstUse(name: String): Boolean {
        return seen.add(name)
    }

    /**
     * Clear all first-use state (e.g., on session end).
     */
    @Synchronized
    fun clear() {
        seen.clear()
    }
}
