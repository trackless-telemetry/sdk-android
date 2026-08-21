package com.tracklesstelemetry.sdk

/**
 * In-memory error first-occurrence deduplication per session.
 *
 * Tracks which normalized error names have already occurred in the current
 * session so the first occurrence of each name can be marked with
 * `firstOccurrences = 1`, feeding server-side session-reach analytics (the
 * share of sessions that hit an error at least once). Subsequent occurrences
 * of the same name within the session are not marked.
 *
 * Dedup is keyed on the normalized error `name` only (not `severity` or
 * `code`), so a session that reports one error at several severities or with
 * several codes contributes exactly one first occurrence — matching the reach
 * metric's per-name semantics and mirroring [FeatureTracker].
 *
 * The set lives in session state (not the event buffer): it must survive buffer
 * flushes and reset only at session end. Cleared exactly where [FeatureTracker]
 * clears.
 *
 * Thread-safe: all methods are synchronized.
 */
internal class ErrorTracker {

    /** Set of normalized error names seen so far in the current session. */
    private val seen = mutableSetOf<String>()

    /**
     * Check and record the first occurrence of an error name for this session.
     *
     * @param name The normalized error name
     * @return true if this is the first occurrence of the name in the session,
     *   false if it has already occurred
     */
    @Synchronized
    fun firstOccurrence(name: String): Boolean {
        return seen.add(name)
    }

    /**
     * Clear all first-occurrence state (e.g., on session end).
     */
    @Synchronized
    fun clear() {
        seen.clear()
    }
}
