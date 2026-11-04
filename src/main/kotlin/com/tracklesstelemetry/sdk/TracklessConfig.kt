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
 * @param measureLaunch Send the launch time once per process as the performance metric
 *   `trackless.launch`: seconds from `Process.getStartElapsedRealtime()` to the first frame
 *   of the first resumed Activity. Nothing is sent when [Trackless.configure] runs after an
 *   Activity has already resumed, when the process is not in the foreground as
 *   [Trackless.configure] runs (it was started for a broadcast, job, service or content
 *   provider and opened later; the SDK reads this process's own importance once, at that
 *   configure), or when the launch is longer than 60 seconds, which is dropped, not clipped.
 *   Call configure in `Application.onCreate`. Off by default; turning it on
 *   means declaring Performance Data (App info and performance → Diagnostics) in the Play
 *   Data safety form.
 * @param launchThresholdSeconds Optional threshold for `trackless.launch`, so the breach
 *   count and performance alerts work on it. A value that is not a positive number is
 *   ignored, with a warning.
 * @param detectHangs Send each main-thread hang of 250 ms or more as one `trackless.hang`
 *   sample, in seconds, when the main thread recovers. The watchdog runs only while the app
 *   is in the foreground. Off by default; same Data safety note as [measureLaunch].
 * @param captureUncaught Count exceptions no code caught, as the error `trackless.uncaught`
 *   with the exception's class name as its code (`java.lang.illegalstateexception`). The SDK
 *   chains a default uncaught-exception handler in front of the previous one, sends the
 *   count, the session's end and the rest of the buffer, waiting at most 1.5 seconds, and then
 *   always calls the previous handler. No message, stack trace or thread is read. ANRs and
 *   native crashes are not exceptions and are not counted; R8-obfuscated classes arrive under
 *   their obfuscated names. Off by default; turning it on means declaring Crash logs (App info
 *   and performance) in the Play Data safety form.
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
    val measureLaunch: Boolean = false,
    val launchThresholdSeconds: Double? = null,
    val detectHangs: Boolean = false,
    val captureUncaught: Boolean = false,
) {
    companion object {
        /** Default production ingest endpoint. */
        const val DEFAULT_ENDPOINT = "https://api.tracklesstelemetry.com"
    }
}
