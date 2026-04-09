package com.tracklesstelemetry.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Event types supported by Trackless.
 */
internal enum class EventType(val value: String) {
    SESSION("session"),
    VIEW("view"),
    FEATURE("feature"),
    FUNNEL("funnel"),
    PERFORMANCE("performance"),
    ERROR("error");
}

/**
 * Error severity levels.
 */
enum class ErrorSeverity(val value: String) {
    DEBUG("debug"),
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    FATAL("fatal");
}

/**
 * Coarse device context — no fingerprinting data.
 *
 * Privacy invariants enforced:
 * - NO GAID (Google Advertising ID)
 * - NO SSAID (Android ID)
 * - NO IMEI, serial number, SIM info
 * - NO exact device model (only "phone" or "tablet")
 * - NO full user agent string
 * - NO screen resolution
 * - Region derived from system Locale only (not IP-based)
 */
internal data class EventContext(
    val platform: String = "android",
    val osVersion: String? = null,
    val deviceClass: String? = null,
    val region: String? = null,
    val language: String? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val daysSinceInstall: Int? = null,
    val sdkVersion: String? = null,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("platform", platform)
        if (osVersion != null) json.put("osVersion", osVersion)
        if (deviceClass != null) json.put("deviceClass", deviceClass)
        if (region != null) json.put("region", region)
        if (language != null) json.put("language", language)
        if (appVersion != null) json.put("appVersion", appVersion)
        if (buildNumber != null) json.put("buildNumber", buildNumber)
        if (daysSinceInstall != null) json.put("daysSinceInstall", daysSinceInstall)
        if (sdkVersion != null) json.put("sdkVersion", sdkVersion)
        return json
    }
}

/**
 * A single event in the payload.
 *
 * Type-specific fields:
 * - `count`: defaults to 1 if omitted. SDKs set count > 1 for client-side rollup.
 * - `detail`: optional detail for view and feature events.
 * - `step` / `stepIndex`: funnel events only.
 * - `duration`: single performance measurement. Mutually exclusive with `durations`.
 * - `durations`: multiple performance measurements from client-side rollup.
 * - `severity` / `code`: error events only.
 */
internal data class TracklessEvent(
    val type: EventType,
    val name: String,
    var count: Int? = null,
    val detail: String? = null,
    val step: String? = null,
    val stepIndex: Int? = null,
    val duration: Double? = null,
    var durations: MutableList<Double>? = null,
    val threshold: Double? = null,
    val severity: ErrorSeverity? = null,
    val code: String? = null,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("type", type.value)
        json.put("name", name)
        if (count != null) json.put("count", count)
        if (detail != null) json.put("detail", detail)
        if (step != null) json.put("step", step)
        if (stepIndex != null) json.put("stepIndex", stepIndex)
        if (duration != null) json.put("duration", duration)
        if (durations != null) {
            val arr = JSONArray()
            durations?.forEach { arr.put(it) }
            json.put("durations", arr)
        }
        if (threshold != null) json.put("threshold", threshold)
        if (severity != null) json.put("severity", severity.value)
        if (code != null) json.put("code", code)
        return json
    }

    /**
     * Build the rollup key for this event (used for aggregation in EventBuffer).
     * Events with the same rollup key can be merged by incrementing count.
     */
    fun rollupKey(): String {
        return when (type) {
            EventType.VIEW, EventType.FEATURE -> "${type.value}|${name}|${detail ?: ""}"
            EventType.ERROR -> "${type.value}|${name}|${severity?.value ?: ""}|${code ?: ""}"
            EventType.PERFORMANCE -> "${type.value}|${name}|${threshold?.let { it.toString() } ?: ""}"
            // session and funnel are not aggregatable
            EventType.SESSION, EventType.FUNNEL -> ""
        }
    }

    /**
     * Whether this event type can be aggregated (merged by incrementing count).
     */
    val isAggregatable: Boolean
        get() = type != EventType.SESSION && type != EventType.FUNNEL
}

/**
 * Full event payload sent to the ingest endpoint.
 */
internal data class EventPayload(
    val date: String,
    val environment: String?,
    val context: EventContext,
    val events: List<TracklessEvent>,
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("date", date)
        if (environment != null) json.put("environment", environment)
        json.put("context", context.toJson())
        val eventsArray = JSONArray()
        for (event in events) {
            eventsArray.put(event.toJson())
        }
        json.put("events", eventsArray)
        return json.toString()
    }
}

/**
 * Response from the ingest endpoint.
 */
internal data class IngestResponse(
    val accepted: Int?,
    val rejected: Int?,
)
