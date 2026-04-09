package com.tracklesstelemetry.sdk

/**
 * Configuration for the Trackless SDK.
 *
 * @param apiKey API key in `tl_*` format (required)
 * @param endpoint Ingest endpoint URL (default: https://api.tracklesstelemetry.com)
 * @param environment Environment tag. Auto-detected from FLAG_DEBUGGABLE if null.
 * @param enabled Enable/disable event recording (default: true)
 * @param onError Optional error callback for debugging. Never called on the main thread.
 * @param flushIntervalSeconds Flush interval in seconds (default: 60)
 * @param debugLogging Enable debug logging for happy-path events via android.util.Log (default: false)
 * @param suppressWarnings Suppress warning and error logging (default: false)
 */
data class TracklessConfig(
    val apiKey: String,
    val endpoint: String = DEFAULT_ENDPOINT,
    val environment: TracklessEnvironment? = null,
    val enabled: Boolean = true,
    val onError: ((Throwable) -> Unit)? = null,
    val flushIntervalSeconds: Long = 60L,
    val debugLogging: Boolean = false,
    val suppressWarnings: Boolean = false,
) {
    companion object {
        /** Default production ingest endpoint. */
        const val DEFAULT_ENDPOINT = "https://api.tracklesstelemetry.com"
    }
}
