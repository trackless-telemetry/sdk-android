package com.tracklesstelemetry.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory event aggregation buffer.
 *
 * Aggregates events by rollup key (type + name + type-specific fields).
 * Non-aggregatable events (session, funnel) are stored individually.
 * Performance events aggregate durations into a list.
 *
 * Bounded to [maxItems] (default 1000) unique entries.
 *
 * Thread-safe: uses [ConcurrentHashMap] with synchronized mutation.
 */
internal class EventBuffer(
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
) {

    companion object {
        const val DEFAULT_MAX_ITEMS = 1000
        const val MAX_EVENTS_PER_PAYLOAD = 100
    }

    private val aggregated = ConcurrentHashMap<String, TracklessEvent>()
    private val individual = mutableListOf<TracklessEvent>()
    private val individualLock = Any()

    /**
     * Local-time date formatter. Events are bucketed by the device's
     * local calendar date. SimpleDateFormat is not thread-safe,
     * so we create a new instance per call.
     */
    internal fun localDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }

    /**
     * Add an event to the buffer.
     *
     * @return true if the event was accepted, false if dropped (buffer full)
     */
    fun add(event: TracklessEvent): Boolean {
        if (!event.isAggregatable) {
            return addIndividual(event)
        }

        if (event.type == EventType.PERFORMANCE) {
            return addPerformance(event)
        }

        return addAggregatable(event)
    }

    private fun addAggregatable(event: TracklessEvent): Boolean {
        val key = event.rollupKey()
        if (key.isEmpty()) return false

        val existing = aggregated[key]
        if (existing != null) {
            synchronized(existing) {
                existing.count = (existing.count ?: 1) + (event.count ?: 1)
            }
            return true
        }

        if (totalSize >= maxItems) return false

        val copy = event.copy(count = event.count ?: 1)
        val previous = aggregated.putIfAbsent(key, copy)
        if (previous != null) {
            synchronized(previous) {
                previous.count = (previous.count ?: 1) + (event.count ?: 1)
            }
        }
        return true
    }

    private fun addPerformance(event: TracklessEvent): Boolean {
        val key = event.rollupKey()
        if (key.isEmpty()) return false

        val existing = aggregated[key]
        if (existing != null) {
            synchronized(existing) {
                existing.count = (existing.count ?: 1) + 1
                val newDurations = event.durations ?: if (event.duration != null) mutableListOf(event.duration) else null
                if (newDurations != null) {
                    if (existing.durations == null) {
                        existing.durations = mutableListOf()
                    }
                    existing.durations!!.addAll(newDurations)
                }
            }
            return true
        }

        if (totalSize >= maxItems) return false

        val copy = event.copy(
            count = 1,
            durations = if (event.duration != null && event.durations == null) {
                mutableListOf(event.duration)
            } else {
                event.durations?.toMutableList()
            },
            duration = null,
        )
        val previous = aggregated.putIfAbsent(key, copy)
        if (previous != null) {
            synchronized(previous) {
                previous.count = (previous.count ?: 1) + 1
                val newDurations = copy.durations
                if (newDurations != null) {
                    if (previous.durations == null) {
                        previous.durations = mutableListOf()
                    }
                    previous.durations!!.addAll(newDurations)
                }
            }
        }
        return true
    }

    private fun addIndividual(event: TracklessEvent): Boolean {
        synchronized(individualLock) {
            if (totalSize >= maxItems) return false
            individual.add(event)
            return true
        }
    }

    /**
     * Drain the buffer into [EventPayload] list and clear it.
     *
     * Splits events into chunks of [MAX_EVENTS_PER_PAYLOAD].
     *
     * @param environment Environment string (sandbox/production)
     * @param context Device context
     * @return List of payloads ready to send
     */
    fun drain(environment: String, context: EventContext): List<EventPayload> {
        val date = localDate()
        val allEvents = mutableListOf<TracklessEvent>()

        // Collect aggregated events
        val keys = aggregated.keys().toList()
        for (key in keys) {
            val event = aggregated.remove(key) ?: continue
            allEvents.add(event)
        }

        // Collect individual events
        synchronized(individualLock) {
            allEvents.addAll(individual)
            individual.clear()
        }

        if (allEvents.isEmpty()) return emptyList()

        // Split into chunks
        return allEvents.chunked(MAX_EVENTS_PER_PAYLOAD).map { chunk ->
            EventPayload(
                date = date,
                environment = environment,
                context = context,
                events = chunk,
            )
        }
    }

    /**
     * Clear the buffer without draining.
     */
    fun clear() {
        aggregated.clear()
        synchronized(individualLock) {
            individual.clear()
        }
    }

    /**
     * Current total number of unique entries in the buffer.
     */
    val totalSize: Int
        get() = aggregated.size + synchronized(individualLock) { individual.size }

    /**
     * Check if the buffer is empty.
     */
    val isEmpty: Boolean
        get() = totalSize == 0
}
