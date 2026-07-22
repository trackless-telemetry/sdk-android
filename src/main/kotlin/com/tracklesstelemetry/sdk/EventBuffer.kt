package com.tracklesstelemetry.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of splitting a payload by serialized size: size-compliant payloads
 * plus any single events too large to send at all.
 */
internal data class PayloadSplitResult(
    val payloads: List<EventPayload>,
    val dropped: List<TracklessEvent>,
)

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

        /**
         * Max serialized request body size accepted by the ingest endpoint: 50 KB.
         * Mirrors MAX_REQUEST_BODY_SIZE_BYTES in @trackless/shared-config.
         */
        const val MAX_PAYLOAD_BYTES = 50 * 1024

        /**
         * Split a payload into payloads whose serialized size fits the ingest
         * request body limit. Oversized payloads have their events halved
         * recursively; a single-event payload that still exceeds the limit is
         * dropped. The wire format is unchanged — only the batching boundaries
         * move.
         */
        fun splitBySize(payload: EventPayload, limit: Int = MAX_PAYLOAD_BYTES): PayloadSplitResult {
            val size = try {
                payload.toJsonString().toByteArray(Charsets.UTF_8).size
            } catch (_: Throwable) {
                // Serialization failures surface through the HTTP layer; pass through unchanged.
                return PayloadSplitResult(listOf(payload), emptyList())
            }
            if (size <= limit) return PayloadSplitResult(listOf(payload), emptyList())
            if (payload.events.size <= 1) return PayloadSplitResult(emptyList(), payload.events)

            val mid = payload.events.size / 2
            val first = splitBySize(payload.copy(events = payload.events.take(mid)), limit)
            val second = splitBySize(payload.copy(events = payload.events.drop(mid)), limit)
            return PayloadSplitResult(
                payloads = first.payloads + second.payloads,
                dropped = first.dropped + second.dropped,
            )
        }
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
                existing.firstUses = sumFirstUses(existing.firstUses, event.firstUses)
            }
            return true
        }

        if (totalSize >= maxItems) return false

        val copy = event.copy(count = event.count ?: 1)
        val previous = aggregated.putIfAbsent(key, copy)
        if (previous != null) {
            synchronized(previous) {
                previous.count = (previous.count ?: 1) + (event.count ?: 1)
                previous.firstUses = sumFirstUses(previous.firstUses, event.firstUses)
            }
        }
        return true
    }

    /**
     * Sum two nullable first-use counters, treating null as 0. Returns null when
     * the total is 0 so a rollup carrying no first-use stays absent on the wire
     * (the backend rejects `firstUses:0`).
     */
    private fun sumFirstUses(a: Int?, b: Int?): Int? {
        val sum = (a ?: 0) + (b ?: 0)
        return if (sum > 0) sum else null
    }

    private fun addPerformance(event: TracklessEvent): Boolean {
        val key = event.rollupKey()
        if (key.isEmpty()) return false

        // No count field on performance events — the durations list length
        // is the sample count (matches web/iOS).
        val existing = aggregated[key]
        if (existing != null) {
            synchronized(existing) {
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
            durations = if (event.duration != null) {
                mutableListOf(event.duration)
            } else {
                event.durations?.toMutableList() ?: mutableListOf()
            },
            duration = null,
        )
        val previous = aggregated.putIfAbsent(key, copy)
        if (previous != null) {
            synchronized(previous) {
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
