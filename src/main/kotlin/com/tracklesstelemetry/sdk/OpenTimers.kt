package com.tracklesstelemetry.sdk

import android.os.SystemClock

/**
 * A started timer, or the start of one [Trackless.measure] call. Published so
 * the inline `measure` can hold one; it has no public members.
 */
@PublishedApi
internal class TimerStart(internal val atMs: Long, internal val sequence: Long)

/**
 * The open timers behind [Trackless.startTimer] / [Trackless.stopTimer], in
 * memory only.
 *
 * **Clock.** `SystemClock.elapsedRealtime()`: monotonic, counts deep sleep, and
 * is not affected by the user or the network changing the wall clock. The
 * elapsed bounds in [Trackless] still drop anything outside `0..1 day`, so the
 * three SDKs share one rule.
 *
 * **Discard.** Session end, `setEnabled(false)`, a reconfigure and `destroy()`
 * call [discardAll]: a timer that spans a background would measure time the app
 * was not running. Each start, and each [Trackless.measure] call, takes a
 * sequence number; a discard records the highest one it covers, so a `measure`
 * whose block was running when the session ended sends nothing either.
 *
 * Thread-safe: all methods are synchronized.
 *
 * @param clock Millisecond monotonic clock — injectable for testing.
 */
internal class OpenTimers(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    sealed class StartResult {
        object Started : StartResult()
        object Restarted : StartResult()

        /** The cap was reached and the start ignored. */
        data class CapReached(val shouldWarn: Boolean) : StartResult()
    }

    private val open = HashMap<String, TimerStart>()
    private var sequence = 0L
    private var discardedThrough = 0L
    private var warnedCap = false

    /** Start (or restart) the timer for an already-normalized [name]. */
    @Synchronized
    fun start(name: String): StartResult {
        val restarting = open.containsKey(name)
        if (!restarting && open.size >= MAX_OPEN) {
            val shouldWarn = !warnedCap
            warnedCap = true
            return StartResult.CapReached(shouldWarn)
        }
        open[name] = TimerStart(clock(), ++sequence)
        return if (restarting) StartResult.Restarted else StartResult.Started
    }

    /**
     * Close the timer for an already-normalized [name] and return its elapsed
     * seconds, or null when no timer of that name is open.
     */
    @Synchronized
    fun stop(name: String): Double? {
        val start = open.remove(name) ?: return null
        return (clock() - start.atMs) / 1000.0
    }

    /** Drop the timer for an already-normalized [name], if one is open. */
    @Synchronized
    fun cancel(name: String) {
        open.remove(name)
    }

    /** Start one [Trackless.measure] call. It does not enter the map or count toward the cap. */
    @Synchronized
    fun mark(): TimerStart = TimerStart(clock(), ++sequence)

    /** Elapsed seconds since [start], or null when a discard has happened since. */
    @Synchronized
    fun elapsedSince(start: TimerStart): Double? {
        if (start.sequence <= discardedThrough) return null
        return (clock() - start.atMs) / 1000.0
    }

    /** Discard every open timer and invalidate every `measure` in progress. */
    @Synchronized
    fun discardAll() {
        discardedThrough = sequence
        open.clear()
        warnedCap = false
    }

    /** Number of open timers (test support). */
    val count: Int
        @Synchronized get() = open.size

    companion object {
        /** At most this many timers are open at once. A start past it is ignored. */
        const val MAX_OPEN = 50

        /** Elapsed times above this — one day — are dropped. */
        const val MAX_ELAPSED_SECONDS = 86_400.0
    }
}
