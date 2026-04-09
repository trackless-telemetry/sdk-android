package com.tracklesstelemetry.sdk

/**
 * Circuit breaker with exponential backoff for flush failures.
 *
 * Only 5xx and network errors trigger backoff.
 * 4xx errors discard the batch but do NOT trigger the circuit breaker.
 * A single successful flush resets the failure count and backoff to zero.
 *
 * Backoff schedule:
 * | Consecutive Failures | Cooldown Before Next Attempt |
 * | 1                    | 30 seconds                   |
 * | 2                    | 1 minute                     |
 * | 3                    | 5 minutes                    |
 * | 4                    | 15 minutes                   |
 * | 5+                   | 60 minutes (max)             |
 */
internal class CircuitBreaker {

    companion object {
        /** Backoff delays in milliseconds: 30s, 1m, 5m, 15m, 60m */
        internal val DELAYS_MS = longArrayOf(
            30_000L,
            60_000L,
            300_000L,
            900_000L,
            3_600_000L,
        )
    }

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var nextRetryAt = 0L

    /**
     * Can we attempt a flush right now?
     */
    fun canAttempt(): Boolean {
        if (consecutiveFailures == 0) return true
        return System.currentTimeMillis() >= nextRetryAt
    }

    /**
     * Record a successful flush — resets backoff entirely.
     */
    fun recordSuccess() {
        consecutiveFailures = 0
        nextRetryAt = 0L
    }

    /**
     * Record a flush failure — advances backoff schedule.
     */
    fun recordFailure() {
        consecutiveFailures++
        val delayIndex = (consecutiveFailures - 1).coerceAtMost(DELAYS_MS.size - 1)
        nextRetryAt = System.currentTimeMillis() + DELAYS_MS[delayIndex]
    }

    /**
     * Current consecutive failure count (for testing).
     */
    val failures: Int
        get() = consecutiveFailures
}
