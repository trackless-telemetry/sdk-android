package com.tracklesstelemetry.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trackless — privacy-first analytics SDK for Android.
 *
 * Zero dependencies (Android framework only). Zero device persistence
 * (no SharedPreferences, no files, no database). All state is in-memory only.
 *
 * Usage:
 * ```kotlin
 * Trackless.configure(
 *     context = applicationContext,
 *     config = TracklessConfig(
 *         apiKey = "tl_xxxxxxxxxxxxxxxx",
 *         endpoint = "https://api.tracklesstelemetry.com",
 *     )
 * )
 *
 * Trackless.view("home")
 * Trackless.feature("export", "csv")
 * Trackless.error("api_timeout", "TIMEOUT_500")
 * Trackless.info("tier", "paid")
 * ```
 *
 * Privacy invariants:
 * - NO GAID, SSAID, Android ID, IMEI, serial number, SIM info
 * - NO fingerprinting data (no full user agent, exact device model, screen resolution)
 * - NO device storage for telemetry (no SharedPreferences, files, database)
 * - NO IP processing in application code
 * - Locale derived from Locale.getDefault() only (not IP-based)
 */
object Trackless {

    private val configured = AtomicBoolean(false)
    private val enabled = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    /** One-shot flag: warn at most once per session when the buffer rejects an event. */
    private val bufferFullWarned = AtomicBoolean(false)

    /** One-shot flag: warn at most once when events are recorded while unconfigured. */
    private val preConfigureWarned = AtomicBoolean(false)

    /** Set while a threshold flush is queued, so a burst of events queues only one. */
    private val thresholdFlushQueued = AtomicBoolean(false)

    private var apiKey: String = ""
    private var endpoint: String = ""
    private var environment: TracklessEnvironment = TracklessEnvironment.PRODUCTION
    private var onError: ((Throwable) -> Unit)? = null
    private var flushIntervalSeconds: Long = 60L
    private var debugLogging: Boolean = false
    private var suppressWarnings: Boolean = false
    private var measureLaunch: Boolean = false
    private var launchThresholdSeconds: Double? = null
    private var detectHangs: Boolean = false

    /** Read on the crashing thread, so volatile. */
    @Volatile
    private var captureUncaught: Boolean = false

    /**
     * Names starting with this are reserved for what the SDK sends itself.
     * [performance], [startTimer], [measure], [error] and [info] warn once per
     * process when given one, and send it anyway.
     */
    internal const val RESERVED_NAME_PREFIX = "trackless."

    /** The metric [TracklessConfig.measureLaunch] sends. */
    internal const val LAUNCH_METRIC = "trackless.launch"

    /** The metric [TracklessConfig.detectHangs] sends. */
    internal const val HANG_METRIC = "trackless.hang"

    /** The error name [TracklessConfig.captureUncaught] sends. */
    internal const val UNCAUGHT_ERROR = "trackless.uncaught"

    /** The code sent when an exception's class name does not survive normalization. */
    internal const val UNCAUGHT_UNKNOWN_CODE = "unknown"

    /** How long the crashing thread waits for its send before the previous handler runs. */
    internal const val UNCAUGHT_SEND_CAP_MS = 1_500L

    /** One-shot flag: the reserved-prefix warning is logged once per process. */
    private val reservedPrefixWarned = AtomicBoolean(false)

    private const val TAG = "Trackless"
    private const val BUFFER_FLUSH_THRESHOLD = 100
    private const val MAX_FIELD_LENGTH = 100

    /**
     * Reason text for a name that fails normalization.
     *
     * The raw name is deliberately omitted from the warning and the error.
     * Normalization only fails *after* PII stripping has run, so no
     * PII-stripped form of the name survives to report. The inputs that
     * actually reach this branch are the ones the PII guard does *not*
     * recognize — anything it does recognize is replaced with the literal
     * "[REDACTED]", which normalizes to a non-empty "redacted" and is
     * accepted. What is left is chiefly non-Latin-script text, which includes
     * personal names. Emitting it would write that value to logcat and — via
     * `onError` — hand it to whatever crash reporter the host app uses.
     */
    private const val INVALID_EVENT_NAME_REASON =
        "it normalized to an empty or disallowed value (raw name omitted: it may contain PII)"

    private var context: EventContext = EventContext()
    private var buffer: EventBuffer = EventBuffer()
    private var circuitBreaker: CircuitBreaker = CircuitBreaker()
    private var sessionManager: SessionManager = SessionManager()
    private var featureTracker: FeatureTracker = FeatureTracker()
    private var errorTracker: ErrorTracker = ErrorTracker()

    /**
     * Open timers. Not replaced on [configure], so a timer started before the
     * first configure survives it; a reconfigure discards them through
     * [endCurrentSession].
     */
    private var timers: OpenTimers = OpenTimers()

    /**
     * The [TracklessConfig.measureLaunch] probe. Never replaced on [configure]
     * or [destroy], so it measures once per process.
     */
    private var launchProbe: LaunchProbe = LaunchProbe()

    /** The [TracklessConfig.detectHangs] watchdog. */
    private var hangWatchdog: HangWatchdog = HangWatchdog()

    /**
     * The [TracklessConfig.captureUncaught] handler. Installed at most once per
     * process and never removed; while the option is off it only forwards.
     */
    private var uncaughtCapture: UncaughtCapture = UncaughtCapture()

    /** How the first frame of a resumed Activity is found. Replaced in tests. */
    private var firstFrameWaiter: (Activity, () -> Unit) -> Unit = FirstFrame::await

    /**
     * The settings the running configuration was built from, compared on a
     * repeat [configure]. `onError` is left out: a caller usually passes a new
     * lambda each time, so it can never compare equal.
     */
    private data class ActiveSettings(
        val apiKey: String,
        val endpoint: String,
        val environment: TracklessEnvironment,
        val enabled: Boolean,
        val flushIntervalSeconds: Long,
        val debugLogging: Boolean,
        val suppressWarnings: Boolean,
        val measureLaunch: Boolean,
        val launchThresholdSeconds: Double?,
        val detectHangs: Boolean,
        val captureUncaught: Boolean,
    )

    /**
     * Everything a flush sends with, captured when the flush is requested. A
     * flush queued before a reconfigure still runs after it, and must send the
     * old buffer under the old key, not the new one.
     */
    private class FlushTarget(
        val buffer: EventBuffer,
        val circuitBreaker: CircuitBreaker,
        val endpoint: String,
        val apiKey: String,
        val environment: TracklessEnvironment,
        val context: EventContext,
    )

    private var activeSettings: ActiveSettings? = null

    private var appContext: Context? = null

    /**
     * The flush thread. Every network send runs here, never on the caller's
     * thread: Android throws `NetworkOnMainThreadException` for a send from the
     * main thread, which would lose the batch and trip the circuit breaker.
     */
    private var scheduler: ScheduledExecutorService? = null
    private var flushFuture: ScheduledFuture<*>? = null

    /**
     * Flush threads handed off by [configure] or [destroy], each finishing its
     * final flush before it stops. Held only so tests can wait for them;
     * terminated ones are pruned on every hand-off.
     */
    private val retiredExecutors = ConcurrentLinkedQueue<ExecutorService>()
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    /**
     * The Activities this SDK has seen start and not yet seen stop, held weakly
     * so a leaked callback never keeps an Activity alive.
     *
     * A set rather than a counter, because registration can happen while an
     * Activity is already showing — a `configure()` from a screen, or
     * `setEnabled(true)` from a settings screen. A counter starting at 0 then
     * went to -1 when that screen stopped, and the next start only brought it
     * back to 0, so no session began until the count passed 1 again. A stop for
     * an Activity the set never held leaves it unchanged; when the set is empty
     * after a stop the app is in the background, which is true whichever way
     * the screen got there.
     */
    private val startedActivities: MutableSet<Activity> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Test-only hook — receives each warning message that passes the suppression check. */
    internal var onWarning: ((String) -> Unit)? = null

    /**
     * Whether the SDK has been configured and is ready to record events.
     */
    val isConfigured: Boolean
        get() = configured.get() && !destroyed.get()

    /**
     * Configure the Trackless SDK and start a new session.
     *
     * Call it once at launch, before any event methods. Calling it again with
     * different settings — a new API key when the user switches organization,
     * say — ends the current session, sends its buffered events under the
     * previous settings on the flush thread, and starts a new session under the
     * new ones. A repeat call with identical settings keeps the current session
     * and only replaces [TracklessConfig.onError].
     */
    @Synchronized
    fun configure(context: Context, config: TracklessConfig) {
        try {
            // The probe arms on the first configure that asks for it; it ignores every later one.
            // A background start (the process not in the foreground now) finishes it instead.
            if (config.measureLaunch) launchProbe.arm()
            val validThreshold = config.launchThresholdSeconds?.takeIf { it.isFinite() && it > 0 }
            val settings = ActiveSettings(
                apiKey = config.apiKey,
                endpoint = config.endpoint,
                environment = config.environment ?: ContextDetection.detectEnvironment(context),
                enabled = config.enabled,
                flushIntervalSeconds = config.flushIntervalSeconds,
                debugLogging = config.debugLogging,
                suppressWarnings = config.suppressWarnings,
                measureLaunch = config.measureLaunch,
                launchThresholdSeconds = validThreshold,
                detectHangs = config.detectHangs,
                captureUncaught = config.captureUncaught,
            )

            if (isConfigured) {
                // Compare against the live enabled state: configure(enabled = true) after
                // setEnabled(false) asks for recording, so it is a change.
                if (settings == activeSettings?.copy(enabled = enabled.get())) {
                    onError = config.onError
                    debug("configure — settings unchanged, keeping the current session")
                    return
                }
                retireCurrentConfiguration()
                debug("reconfigured — previous session ended and flushed under the previous settings")
            }

            configured.set(true)
            activeSettings = settings
            appContext = context.applicationContext
            apiKey = config.apiKey
            endpoint = config.endpoint
            environment = settings.environment
            onError = config.onError
            flushIntervalSeconds = config.flushIntervalSeconds
            debugLogging = config.debugLogging
            suppressWarnings = config.suppressWarnings
            measureLaunch = config.measureLaunch
            launchThresholdSeconds = validThreshold
            detectHangs = config.detectHangs
            captureUncaught = config.captureUncaught

            buffer = EventBuffer()
            circuitBreaker = CircuitBreaker()
            sessionManager = SessionManager()
            featureTracker = FeatureTracker()
            errorTracker = ErrorTracker()
            val detected = ContextDetection.detectReportingOmissions(context)
            this.context = detected.context
            detected.omitted.forEach { warn(ContextDetection.omittedWarning(it)) }

            bufferFullWarned.set(false)
            preConfigureWarned.set(false)
            destroyed.set(false)
            enabled.set(config.enabled)

            debug("configured — env=${environment.value} endpoint=$endpoint flush=${flushIntervalSeconds}s")
            if (config.launchThresholdSeconds != null && validThreshold == null) {
                warn("launchThresholdSeconds is not a positive number — trackless.launch is sent without a threshold")
            }

            if (enabled.get()) {
                startPeriodicFlush()
                registerLifecycleCallbacks()
                startNewSession()
                enableHangDetectionIfWanted()
                installUncaughtHandlerIfWanted()
            }
        } catch (_: Throwable) {
            // Configure never throws
        }
    }

    // ─── Typed Event Methods ──────────────────────────────────────────────────

    /**
     * Record a view event.
     */
    fun view(name: String, detail: String? = null) {
        if (!canRecord()) return
        val recorded = addEvent(
            TracklessEvent(type = EventType.VIEW, name = name, detail = detail.takeIf { !it.isNullOrEmpty() })
        ) ?: return
        sessionManager.recordActivity()
        debug("view — ${recorded.name}${if (recorded.detail != null) " detail=${recorded.detail}" else ""}")
    }

    /**
     * Record a feature usage event.
     *
     * The name is normalized in-method (mirroring [funnel]) so the first-use
     * dedup keys on the normalized name: the first use of each feature name in
     * a session is marked with `firstUses = 1` (feeding server-side session
     * reach), and later uses of the same name — including different `detail`
     * variants — omit the field. The dedup set survives buffer flushes and
     * resets at session end.
     */
    fun feature(name: String, detail: String? = null) {
        if (!canRecord()) return
        val normalizedName = normalizeName(name) ?: return
        val normalizedDetail = detail?.takeIf { it.isNotEmpty() }?.let { FeatureValidator.normalize(it) }
        val isFirstUse = featureTracker.firstUse(normalizedName)
        addToBuffer(
            TracklessEvent(
                type = EventType.FEATURE,
                name = normalizedName,
                detail = normalizedDetail,
                firstUses = if (isFirstUse) 1 else null,
            )
        )
        flushIfOverThreshold()
        sessionManager.recordActivity()
        debug("feature — $normalizedName${if (normalizedDetail != null) " detail=$normalizedDetail" else ""}")
    }

    /**
     * Record a funnel step event.
     *
     * Every call is counted, including a step index already sent this
     * session. Each call is its own event with no `count`: funnel events are
     * never rolled up, because ingest rejects `count > 1` on a funnel.
     */
    fun funnel(funnelName: String, stepIndex: Int, stepName: String) {
        if (!canRecord()) return
        if (stepIndex < 0) return
        val normalizedFunnel = normalizeName(funnelName) ?: return
        val normalizedStep = normalizeName(stepName) ?: return
        sessionManager.recordActivity()
        addToBuffer(
            TracklessEvent(
                type = EventType.FUNNEL,
                name = normalizedFunnel,
                step = normalizedStep,
                stepIndex = stepIndex,
            )
        )
        flushIfOverThreshold()
        debug("funnel — $normalizedFunnel/$normalizedStep step=$stepIndex")
    }

    /**
     * Record a performance measurement.
     *
     * @param name Event name for this measurement
     * @param durationSeconds Duration in seconds (e.g., 0.342 for 342ms)
     * @param thresholdSeconds Optional threshold in seconds. Creates a separate row per name/threshold combo.
     *
     * Names starting with `trackless.` are reserved for the metrics the SDK
     * measures itself (`trackless.launch`, `trackless.hang`). Passing one logs
     * a warning once per process; the measurement is still sent.
     */
    fun performance(name: String, durationSeconds: Double, thresholdSeconds: Double? = null) {
        if (!canRecord()) return
        if (durationSeconds < 0) return
        if (thresholdSeconds != null && thresholdSeconds <= 0) return
        val recorded = addEvent(
            TracklessEvent(
                type = EventType.PERFORMANCE,
                name = name,
                duration = durationSeconds,
                threshold = thresholdSeconds,
            )
        ) ?: return
        warnIfReserved(recorded.name)
        sessionManager.recordActivity()
        debug("performance — ${recorded.name} duration=${durationSeconds}s${if (thresholdSeconds != null) " threshold=${thresholdSeconds}s" else ""}")
    }

    // ─── Timers ──────────────────────────────────────────────────────────────

    /**
     * Start a timer for a performance measurement.
     *
     * The name is normalized here, exactly as [performance] normalizes its
     * name, so `stopTimer("Checkout")` closes `startTimer("checkout")`. A name
     * normalization rejects is ignored with the usual warning. Starting a name
     * that is already open restarts it. At most 50 timers are open at once; a
     * start past that is ignored, with one warning per session.
     *
     * Open timers live in memory only. **Session end discards every open
     * timer** — the session ends when the app's last activity stops — as do
     * `setEnabled(false)`, a [configure] with changed settings and [destroy],
     * so a timer never measures time the app was in the background.
     *
     * Elapsed time comes from `SystemClock.elapsedRealtime()`. Safe to call
     * from any thread.
     */
    fun startTimer(name: String) {
        try {
            val normalized = normalizeName(name) ?: return
            warnIfReserved(normalized)
            val result = timers.start(normalized)
            if (result is OpenTimers.StartResult.CapReached && result.shouldWarn) {
                warn(
                    "timer \"$normalized\" not started — ${OpenTimers.MAX_OPEN} timers are already " +
                        "open (logged once per session)"
                )
            }
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * Stop the timer [name] and send its elapsed time, in seconds, as
     * `performance(name, elapsed, thresholdSeconds)`.
     *
     * Does nothing when no timer of that name is open: never started, already
     * stopped or cancelled, or discarded when the session ended. An elapsed
     * time below zero or above a day is dropped.
     */
    fun stopTimer(name: String, thresholdSeconds: Double? = null) {
        try {
            val normalized = FeatureValidator.normalize(name) ?: return
            val elapsed = timers.stop(normalized) ?: return
            recordTimer(normalized, elapsed, thresholdSeconds)
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * Drop the timer [name] without sending anything — the operation was
     * abandoned, or failed in a way its duration should not describe.
     */
    fun cancelTimer(name: String) {
        try {
            val normalized = FeatureValidator.normalize(name) ?: return
            timers.cancel(normalized)
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * Time [block] and send its duration as a performance measurement,
     * returning the block's value.
     *
     * The measurement is sent **only when [block] completes normally**. If it
     * throws, nothing is sent and the exception propagates unchanged; a
     * non-local `return` out of the block sends nothing either. Nothing is sent
     * when the session ended while the block ran. `measure` does not use the
     * open-timer map, so it never counts toward the 50-timer cap.
     *
     * ```kotlin
     * val rows = Trackless.measure("csv_parse", thresholdSeconds = 0.5) {
     *     parser.parse(file)
     * }
     * ```
     */
    inline fun <T> measure(name: String, thresholdSeconds: Double? = null, block: () -> T): T {
        val start = measureStart()
        val result = block()
        measureEnd(name, start, thresholdSeconds)
        return result
    }

    @PublishedApi
    internal fun measureStart(): TimerStart? = try {
        timers.mark()
    } catch (_: Throwable) {
        null
    }

    @PublishedApi
    internal fun measureEnd(name: String, start: TimerStart?, thresholdSeconds: Double?) {
        try {
            if (start == null) return
            val elapsed = timers.elapsedSince(start) ?: return
            val normalized = normalizeName(name) ?: return
            warnIfReserved(normalized)
            recordTimer(normalized, elapsed, thresholdSeconds)
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * The send behind [stopTimer] and [measure]: [performance] with an elapsed
     * time the SDK measured, under a name that is already normalized.
     */
    private fun recordTimer(normalizedName: String, elapsedSeconds: Double, thresholdSeconds: Double?) {
        if (!canRecord()) return
        if (elapsedSeconds < 0 || elapsedSeconds > OpenTimers.MAX_ELAPSED_SECONDS) {
            warn(
                "dropped performance \"$normalizedName\" — timer elapsed time ${elapsedSeconds}s is " +
                    "outside 0–${OpenTimers.MAX_ELAPSED_SECONDS.toLong()}s"
            )
            return
        }
        if (thresholdSeconds != null && thresholdSeconds <= 0) return
        addToBuffer(
            TracklessEvent(
                type = EventType.PERFORMANCE,
                name = normalizedName,
                duration = elapsedSeconds,
                threshold = thresholdSeconds,
            )
        )
        flushIfOverThreshold()
        sessionManager.recordActivity()
        debug("performance — $normalizedName duration=${elapsedSeconds}s${if (thresholdSeconds != null) " threshold=${thresholdSeconds}s" else ""} (timer)")
    }

    // ─── Reserved names, launch time and hangs ────────────────────────────────

    /**
     * Warn once per process when a developer's normalized name has the reserved
     * prefix. [kind] names the call in the message: `performance`, `error` or
     * `info`.
     */
    private fun warnIfReserved(normalizedName: String, kind: String = "performance") {
        if (!normalizedName.startsWith(RESERVED_NAME_PREFIX)) return
        if (reservedPrefixWarned.getAndSet(true)) return
        warn(
            "$kind name \"$normalizedName\" starts with \"$RESERVED_NAME_PREFIX\", which is " +
                "reserved for names the SDK sends itself — sent anyway (logged once)"
        )
    }

    /** Recording, without the pre-configure warning [canRecord] logs. */
    private fun isRecording(): Boolean = enabled.get() && !destroyed.get() && configured.get()

    /**
     * A resumed Activity: feed the launch probe, and wait for the Activity's
     * first frame while the probe is waiting for one.
     */
    private fun launchActivityResumed(activity: Activity) {
        if (!launchProbe.onActivityResumed()) return
        firstFrameWaiter(activity) {
            if (launchProbe.onFirstFrame()) deliverLaunch()
        }
    }

    /**
     * Send the launch time the probe measured, once — only while recording
     * with `measureLaunch` on. A launch measured while recording is disabled,
     * or after a reconfigure turned the option off, is dropped.
     */
    private fun deliverLaunch() {
        val seconds = launchProbe.takeMeasurement() ?: return
        if (!isRecording() || !measureLaunch) return
        recordSdkMetric(LAUNCH_METRIC, seconds, launchThresholdSeconds)
    }

    /** Switch the watchdog on when `detectHangs` is set; it starts at the next resumed Activity. */
    private fun enableHangDetectionIfWanted() {
        if (!detectHangs) return
        hangWatchdog.enable { seconds ->
            if (isRecording() && detectHangs) recordSdkMetric(HANG_METRIC, seconds, null)
        }
    }

    /**
     * Buffer one metric the SDK measured itself. Same bounds as a timer; no
     * reserved-name warning, and no session activity — it describes the app,
     * not anything the user did.
     */
    private fun recordSdkMetric(name: String, seconds: Double, thresholdSeconds: Double?) {
        try {
            if (seconds < 0 || seconds > OpenTimers.MAX_ELAPSED_SECONDS) {
                warn(
                    "dropped performance \"$name\" — measured ${seconds}s is outside " +
                        "0–${OpenTimers.MAX_ELAPSED_SECONDS.toLong()}s"
                )
                return
            }
            addToBuffer(
                TracklessEvent(
                    type = EventType.PERFORMANCE,
                    name = name,
                    duration = seconds,
                    threshold = thresholdSeconds,
                )
            )
            flushIfOverThreshold()
            debug("performance — $name duration=${seconds}s${if (thresholdSeconds != null) " threshold=${thresholdSeconds}s" else ""} (SDK)")
        } catch (_: Throwable) {
            // Never throws
        }
    }

    // ─── Uncaught exceptions ──────────────────────────────────────────────────

    /**
     * Install the uncaught-exception handler when `captureUncaught` is set. Once
     * per process: later calls, and a later configure that turns the option
     * off, leave it in place, and it then only forwards.
     */
    private fun installUncaughtHandlerIfWanted() {
        if (!captureUncaught) return
        uncaughtCapture.installOnce(::recordUncaught)
    }

    /**
     * Runs on the crashing thread, before the previous handler. With capture on
     * and recording enabled, it adds `error("trackless.uncaught", code: <class
     * name>)` and the session's end to the buffer, hands a flush of the whole
     * buffer to the flush thread (the crashing thread is often main, where a
     * send throws) and waits for it at most [UNCAUGHT_SEND_CAP_MS]. A failed or
     * slow send is dropped; nothing is kept for a later launch.
     *
     * Only the throwable's class name is read: never its message, cause chain
     * or stack trace, and nothing about the thread.
     */
    private fun recordUncaught(throwable: Throwable) {
        if (!isRecording() || !captureUncaught) return
        val code = FeatureValidator.normalize(throwable.javaClass.name) ?: UNCAUGHT_UNKNOWN_CODE
        // The SDK's own send: no reserved-name warning, and no session activity —
        // it describes the app, not anything the user did.
        val isFirstOccurrence = errorTracker.firstOccurrence(UNCAUGHT_ERROR)
        addToBuffer(
            TracklessEvent(
                type = EventType.ERROR,
                name = UNCAUGHT_ERROR,
                severity = ErrorSeverity.ERROR,
                code = code,
                firstOccurrences = if (isFirstOccurrence) 1 else null,
            )
        )
        debug("error — $UNCAUGHT_ERROR code=$code (uncaught)")
        endCurrentSession()

        // Captured without the lock: the thread holding it may be the one
        // that is stuck, and the process is about to end either way.
        val target = FlushTarget(buffer, circuitBreaker, endpoint, apiKey, environment, context)
        if (target.buffer.isEmpty) return
        val done = CountDownLatch(1)
        submitTask {
            try {
                flushTo(target)
            } finally {
                done.countDown()
            }
        }
        if (!uncaughtCapture.sendWait.await(done, UNCAUGHT_SEND_CAP_MS)) {
            debug("uncaught — send not finished after ${UNCAUGHT_SEND_CAP_MS}ms, handing over")
        }
    }

    /**
     * Record an error event — something went wrong.
     *
     * Counts toward errors per session and toward every alert.
     *
     * @param name Stable error name, e.g. `"api_timeout"`
     * @param code Optional error code, e.g. an HTTP status or exception type
     */
    fun error(name: String, code: String? = null) {
        recordErrorEvent(name, ErrorSeverity.ERROR, code)
    }

    /**
     * Record an error event with an explicit severity.
     */
    @Deprecated(
        "The severity parameter is deprecated. Call error(name, code) for something that went " +
            "wrong and info(name, detail) for something that did not. `info` and `debug` are " +
            "sent as `info`; every other value is sent as `error`.",
    )
    fun error(name: String, severity: ErrorSeverity, code: String? = null) {
        recordErrorEvent(name, severity, code)
    }

    /**
     * Record an info event — something worth counting that the user did not do
     * and that did not go wrong.
     *
     * Counted separately from errors: an info event never contributes to errors
     * per session and never triggers an alert. Report configuration many
     * sessions share (a tier, a unit preference, a fallback path that fired),
     * never anything about the person. Do not share a name between [error] and
     * [info].
     *
     * @param name Stable name for the thing being counted, e.g. `"tier"`
     * @param detail Optional value from a closed set, e.g. `"paid"`
     */
    fun info(name: String, detail: String? = null) {
        recordErrorEvent(name, ErrorSeverity.INFO, detail)
    }

    /**
     * Shared path behind [error] and [info].
     *
     * The severity is mapped to one of the two stored levels here — the single
     * choke point — so the buffer's rollup key collapses one name reported at
     * several legacy severities into a single entry, and nothing but `error` or
     * `info` ever reaches the wire.
     *
     * The name is normalized in-method (mirroring [feature]) so the
     * first-occurrence dedup keys on the normalized name: the first occurrence
     * of each name in a session is marked with `firstOccurrences = 1` (feeding
     * server-side session reach), and later occurrences of the same name —
     * including different `code` variants — omit the field. [error] and [info]
     * share that set, which is why a name must not be shared between them. The
     * set survives buffer flushes and resets at session end.
     */
    private fun recordErrorEvent(name: String, severity: ErrorSeverity, code: String?) {
        if (!canRecord()) return
        val normalizedName = normalizeName(name) ?: return
        val stored = storedSeverity(severity)
        warnIfReserved(normalizedName, stored.value)
        val normalizedCode = code?.takeIf { it.isNotEmpty() }?.let { FeatureValidator.normalize(it) }
        val isFirstOccurrence = errorTracker.firstOccurrence(normalizedName)
        addToBuffer(
            TracklessEvent(
                type = EventType.ERROR,
                name = normalizedName,
                severity = stored,
                code = normalizedCode,
                firstOccurrences = if (isFirstOccurrence) 1 else null,
            )
        )
        flushIfOverThreshold()
        sessionManager.recordActivity()
        debug("${stored.value} — $normalizedName${if (normalizedCode != null) " code=$normalizedCode" else ""}")
    }

    /**
     * Map a severity a caller passed to the one that goes on the wire.
     *
     * Mirrors `storedSeverity()` in `@trackless/shared-config`, which ingest
     * applies on the write path — the SDK has no dependencies, so the rule
     * lives twice on purpose. Matching on [ErrorSeverity.value] rather than the
     * entries keeps the SDK's own code clear of the deprecated ones.
     */
    internal fun storedSeverity(sent: ErrorSeverity): ErrorSeverity =
        if (sent.value == "info" || sent.value == "debug") ErrorSeverity.INFO else ErrorSeverity.ERROR

    // ─── Control Methods ─────────────────────────────────────────────────────

    /**
     * Toggle event recording on/off.
     *
     * When toggled off, any buffered data is immediately discarded and the
     * session is dropped without sending its end. Toggling back on starts a
     * new session.
     */
    fun setEnabled(isEnabled: Boolean) {
        try {
            debug("setEnabled — $isEnabled")
            enabled.set(isEnabled)
            if (!isEnabled) {
                timers.discardAll()
                buffer.clear()
                sessionManager.destroy()
                featureTracker.clear()
                errorTracker.clear()
                stopPeriodicFlush()
                unregisterLifecycleCallbacks()
                hangWatchdog.disable()
            } else if (!destroyed.get() && configured.get()) {
                startPeriodicFlush()
                registerLifecycleCallbacks()
                startNewSession()
                enableHangDetectionIfWanted()
                installUncaughtHandlerIfWanted()
            }
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * Send pending events to the ingest endpoint now.
     *
     * Returns at once: the send runs on the SDK's flush thread, so this is
     * safe to call from the main thread.
     */
    fun flush() {
        try {
            submitFlush(currentFlushTarget())
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * End the session, send remaining events, and release all resources.
     *
     * Returns at once: the final send runs on the SDK's flush thread, which
     * stops once it is done, so this is safe to call from the main thread.
     * Events recorded afterwards are dropped until [configure] is called again.
     */
    @Synchronized
    fun destroy() {
        try {
            if (destroyed.getAndSet(true)) return
            debug("destroying")

            retireCurrentConfiguration()
            featureTracker.clear()
            errorTracker.clear()
            configured.set(false)
            enabled.set(false)
            activeSettings = null
            appContext = null
        } catch (_: Throwable) {
            // Never throws
        }
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    /**
     * Add an event to the buffer with validation.
     *
     * Callers pass a raw, caller-supplied [event] name here (see [view] and
     * [performance]), so the rejection warning must not echo it back.
     *
     * @return the normalized event that was buffered, or null if it was
     *   rejected. Returning the normalized event lets callers log the
     *   PII-stripped name rather than the raw input they passed in.
     */
    private fun addEvent(event: TracklessEvent): TracklessEvent? {
        try {
            if (!enabled.get() || destroyed.get() || !configured.get()) return null

            val normalizedName = FeatureValidator.normalize(event.name)
            if (normalizedName == null) {
                warn("event name rejected — $INVALID_EVENT_NAME_REASON")
                notifyError(IllegalArgumentException("Invalid event name — $INVALID_EVENT_NAME_REASON"))
                return null
            }

            val normalizedEvent = event.copy(
                name = normalizedName,
                detail = event.detail?.let { FeatureValidator.normalize(it) },
                step = event.step?.let { FeatureValidator.normalize(it) },
                code = event.code?.let { FeatureValidator.normalize(it) },
            )
            addToBuffer(normalizedEvent)

            flushIfOverThreshold()
            return normalizedEvent
        } catch (_: Throwable) {
            // Never throws
            return null
        }
    }

    /**
     * Add an event to the buffer, warning at most once per session
     * when the buffer rejects an event because it is full.
     */
    private fun addToBuffer(event: TracklessEvent) {
        if (!buffer.add(event) && !bufferFullWarned.getAndSet(true)) {
            warn("event buffer full — new events are dropped until the next flush")
        }
    }

    /**
     * Queue one flush when the buffer reaches [BUFFER_FLUSH_THRESHOLD]. Events
     * recorded before that flush runs do not queue more of them.
     */
    private fun flushIfOverThreshold() {
        if (buffer.totalSize < BUFFER_FLUSH_THRESHOLD) return
        if (thresholdFlushQueued.getAndSet(true)) return
        val target = currentFlushTarget()
        submitTask {
            thresholdFlushQueued.set(false)
            flushTo(target)
        }
    }

    /** Capture the running configuration's buffer and send settings. */
    @Synchronized
    private fun currentFlushTarget(): FlushTarget =
        FlushTarget(buffer, circuitBreaker, endpoint, apiKey, environment, context)

    private fun submitFlush(target: FlushTarget) {
        if (target.buffer.isEmpty) return
        submitTask { flushTo(target) }
    }

    /**
     * Run [task] on the flush thread. With no flush thread running — the
     * scheduler failed to start — it gets a thread of its own rather than
     * running on the caller's.
     */
    private fun submitTask(task: () -> Unit) {
        val guarded = guarded(task)
        try {
            scheduler?.let {
                it.execute(guarded)
                return
            }
        } catch (_: Throwable) {
            // Shut down between the read and the execute — fall through
        }
        handOff(newFlushExecutor() ?: return, guarded)
    }

    /**
     * Give [executor] one last [task], then let it stop once the task is done.
     */
    private fun handOff(executor: ExecutorService, task: Runnable) {
        try {
            executor.execute(task)
        } catch (_: Throwable) {
            // Already shut down — nothing more to run
        }
        executor.shutdown()
        retiredExecutors.removeIf { it.isTerminated }
        retiredExecutors.add(executor)
    }

    /**
     * Close the running configuration: end its session, stop its timer and
     * lifecycle callbacks, and hand its buffer to its flush thread for one last
     * send under its own key and endpoint. The caller is never blocked, and
     * nothing it records afterwards lands in the retired buffer.
     */
    @Synchronized
    private fun retireCurrentConfiguration() {
        endCurrentSession()
        unregisterLifecycleCallbacks()
        hangWatchdog.disable()
        val target = currentFlushTarget()

        flushFuture?.cancel(false)
        flushFuture = null
        val executor = scheduler
        scheduler = null
        thresholdFlushQueued.set(false)

        if (executor == null && target.buffer.isEmpty) return
        handOff(executor ?: newFlushExecutor() ?: return, guarded { flushTo(target) })
    }

    private fun guarded(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (_: Throwable) {
            // Never let a flush escape its thread
        }
    }

    /**
     * Send [target]'s buffered events. Runs on a flush thread only.
     */
    private fun flushTo(target: FlushTarget) {
        val buffer = target.buffer
        val circuitBreaker = target.circuitBreaker
        if (buffer.isEmpty) return
        if (!circuitBreaker.canAttempt()) {
            debug("flush skipped — circuit breaker open")
            return
        }

        val payloads = buffer.drain(target.environment.value, target.context)
        if (payloads.isEmpty()) return

        // Enforce the server's request body size limit on each chunk before sending.
        val sizedPayloads = mutableListOf<EventPayload>()
        for (payload in payloads) {
            val split = EventBuffer.splitBySize(payload)
            sizedPayloads.addAll(split.payloads)
            repeat(split.dropped.size) {
                warn("event dropped — serialized payload exceeds the request body size limit")
            }
        }

        for (payload in sizedPayloads) {
            debug("flush — ${payload.events.size} events")
            try {
                val result = HttpClient.send(target.endpoint, target.apiKey, payload)

                when {
                    result.isNetworkError -> {
                        circuitBreaker.recordFailure()
                        warn("flush failed — network error")
                        notifyError(Exception("Flush failed: network error"))
                    }
                    result.statusCode >= 500 -> {
                        circuitBreaker.recordFailure()
                        warn("flush failed — status=${result.statusCode}")
                        notifyError(Exception("Flush failed with status ${result.statusCode}"))
                    }
                    result.statusCode >= 400 -> {
                        // 4xx — discard, no backoff
                        warn("flush rejected — status=${result.statusCode}")
                        notifyError(Exception("Flush rejected with status ${result.statusCode}"))
                    }
                    else -> {
                        circuitBreaker.recordSuccess()
                        debug("flush success — status=${result.statusCode}")
                    }
                }
            } catch (error: Throwable) {
                circuitBreaker.recordFailure()
                warn("flush failed — network error")
                notifyError(error)
            }
        }
    }

    private fun canRecord(): Boolean {
        if (!configured.get() && !destroyed.get() && !preConfigureWarned.getAndSet(true)) {
            warn("event dropped — SDK is not configured (call Trackless.configure() first)")
        }
        return enabled.get() && !destroyed.get() && configured.get()
    }

    /**
     * Normalize a caller-supplied event name, warning when it is rejected.
     *
     * The name is omitted from both the warning and the error — see
     * [INVALID_EVENT_NAME_REASON].
     */
    private fun normalizeName(name: String): String? {
        val normalized = FeatureValidator.normalize(name)
        if (normalized == null) {
            warn("event name rejected — $INVALID_EVENT_NAME_REASON")
            notifyError(IllegalArgumentException("Invalid event name — $INVALID_EVENT_NAME_REASON"))
            return null
        }
        return normalized
    }

    private fun notifyError(error: Throwable) {
        try {
            onError?.invoke(error)
        } catch (_: Throwable) {
            // onError itself must not crash the host app
        }
    }

    private fun debug(msg: String) {
        if (debugLogging) Log.d(TAG, msg)
    }

    private fun warn(msg: String) {
        if (suppressWarnings) return
        onWarning?.invoke(msg)
        Log.w(TAG, msg)
    }

    private fun endCurrentSession() {
        // Before the return: a reconfigure or destroy() after setEnabled(false)
        // has no session to end, but may still hold timers started since.
        timers.discardAll()
        val result = sessionManager.end() ?: return
        featureTracker.clear()
        errorTracker.clear()
        addToBuffer(
            TracklessEvent(
                type = EventType.SESSION,
                name = "end",
                duration = result.duration.toDouble(),
                stepIndex = result.depth,
            )
        )
        debug("session ended — duration=${result.duration}s depth=${result.depth}")
    }

    private fun startNewSession() {
        if (sessionManager.start()) {
            // Re-arm the buffer-full warning for the new session
            bufferFullWarned.set(false)
            addToBuffer(TracklessEvent(type = EventType.SESSION, name = "start"))
            debug("session started")
        }
    }

    // ─── Periodic Flush ──────────────────────────────────────────────────────

    @Synchronized
    private fun startPeriodicFlush() {
        if (flushFuture != null) return

        try {
            val exec = newFlushExecutor() ?: return
            scheduler = exec
            flushFuture = exec.scheduleAtFixedRate(
                {
                    try {
                        flushTo(currentFlushTarget())
                    } catch (_: Throwable) {
                        // Never let the scheduled task die
                    }
                },
                flushIntervalSeconds,
                flushIntervalSeconds,
                TimeUnit.SECONDS,
            )
        } catch (_: Throwable) {
            // Failed to create scheduler — silent failure
        }
    }

    /** A single daemon flush thread, or null when one cannot be created. */
    private fun newFlushExecutor(): ScheduledExecutorService? = try {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "trackless-flush").apply {
                isDaemon = true
            }
        }
    } catch (_: Throwable) {
        null
    }

    @Synchronized
    private fun stopPeriodicFlush() {
        try {
            flushFuture?.cancel(false)
            flushFuture = null
            scheduler?.shutdownNow()
            scheduler = null
        } catch (_: Throwable) {
            // Never throws
        }
    }

    // ─── Lifecycle Callbacks ─────────────────────────────────────────────────

    @Synchronized
    private fun registerLifecycleCallbacks() {
        if (lifecycleCallbacks != null) return

        try {
            val app = appContext as? Application ?: return

            lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities.add(activity)
                    if (startedActivities.size == 1) {
                        // App is coming to the foreground — start a new session.
                        // A no-op when one is already open (a configure() before
                        // the first Activity, or a rotation that kept it).
                        startNewSession()
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    try {
                        launchActivityResumed(activity)
                        hangWatchdog.appBecameActive()
                    } catch (_: Throwable) {
                        // Never throws into the host
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    launchProbe.onActivityPausedOrStopped()
                }

                override fun onActivityStopped(activity: Activity) {
                    launchProbe.onActivityPausedOrStopped()
                    startedActivities.remove(activity)
                    // A rotation stops the old instance before the new one
                    // starts; that is not the app leaving the screen.
                    if (startedActivities.isEmpty() && !activity.isChangingConfigurations) {
                        // On the main thread, before the hand-off: the watchdog
                        // stops now, and settles a hang the user left during.
                        hangWatchdog.appResignedActive()
                        // App is now in the background — end session and flush
                        try {
                            scheduler?.submit {
                                try {
                                    endCurrentSession()
                                    flushTo(currentFlushTarget())
                                } catch (_: Throwable) {
                                    // Never throws
                                }
                            }
                        } catch (_: Throwable) {
                            // Scheduler may be shut down
                        }
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }

            app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        } catch (_: Throwable) {
            // Failed to register — silent failure
        }
    }

    @Synchronized
    private fun unregisterLifecycleCallbacks() {
        try {
            val app = appContext as? Application ?: return
            lifecycleCallbacks?.let {
                app.unregisterActivityLifecycleCallbacks(it)
            }
            lifecycleCallbacks = null
            startedActivities.clear()
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * Replace the buffer with a smaller one for testing.
     */
    internal fun replaceBufferForTesting(maxItems: Int) {
        buffer = EventBuffer(maxItems)
    }

    /**
     * Replace the open timers with a set reading [clock] (milliseconds).
     * `SystemClock` returns 0 in JVM unit tests.
     */
    internal fun replaceTimerClockForTesting(clock: () -> Long) {
        timers = OpenTimers(clock)
    }

    /**
     * Replace the launch probe with one reading [processStartMs] and [clock]
     * (milliseconds) and this process's importance from [importance], and the
     * first-frame wait with [frameWaiter].
     */
    internal fun replaceLaunchProbeForTesting(
        processStartMs: () -> Long,
        clock: () -> Long,
        importance: () -> Int?,
        frameWaiter: (Activity, () -> Unit) -> Unit,
    ) {
        launchProbe = LaunchProbe(processStartMs, clock, importance)
        firstFrameWaiter = frameWaiter
    }

    /** Replace the hang watchdog with one on [scheduler] and [clock] (milliseconds). */
    internal fun replaceHangWatchdogForTesting(scheduler: WatchdogScheduler, clock: () -> Long) {
        hangWatchdog.disable()
        hangWatchdog = HangWatchdog(scheduler, clock)
    }

    /**
     * Replace the uncaught-exception hook with one installing into [slot] and
     * waiting on its send through [sendWait]. The new hook is not installed
     * until a configure with `captureUncaught` asks for it.
     */
    internal fun replaceUncaughtCaptureForTesting(
        slot: DefaultHandlerSlot,
        sendWait: UncaughtSendWait = UncaughtCapture().sendWait,
    ) {
        uncaughtCapture = UncaughtCapture(slot, sendWait)
    }

    /** Whether the uncaught-exception handler is installed, for testing. */
    internal val uncaughtHandlerInstalledForTesting: Boolean
        get() = uncaughtCapture.isInstalled

    /** The hang watchdog, for testing. */
    internal val hangWatchdogForTesting: HangWatchdog
        get() = hangWatchdog

    /** Whether the launch probe is still waiting, for testing. */
    internal val launchProbeWaitingForTesting: Boolean
        get() = launchProbe.isWaiting

    /** Number of open timers for testing. */
    internal fun openTimerCountForTesting(): Int = timers.count

    /**
     * Current buffer size for testing.
     */
    internal fun bufferSizeForTesting(): Int = buffer.totalSize

    /**
     * Wait for every flush already queued — on the running flush thread and on
     * any retired by [configure] or [destroy] — to finish.
     */
    internal fun awaitFlushesForTesting(timeoutMs: Long = 5_000L) {
        try {
            scheduler?.submit {}?.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Shut down meanwhile — its work is covered by the retired executors below
        }
        while (true) {
            val executor = retiredExecutors.poll() ?: break
            executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Reset internal state for testing.
     */
    internal fun resetForTesting() {
        try {
            awaitFlushesForTesting()
            stopPeriodicFlush()
            unregisterLifecycleCallbacks()
            configured.set(false)
            enabled.set(false)
            destroyed.set(false)
            bufferFullWarned.set(false)
            preConfigureWarned.set(false)
            thresholdFlushQueued.set(false)
            activeSettings = null
            onWarning = null
            buffer = EventBuffer()
            circuitBreaker = CircuitBreaker()
            sessionManager = SessionManager()
            featureTracker = FeatureTracker()
            errorTracker = ErrorTracker()
            timers = OpenTimers()
            hangWatchdog.disable()
            hangWatchdog = HangWatchdog()
            launchProbe = LaunchProbe()
            firstFrameWaiter = FirstFrame::await
            reservedPrefixWarned.set(false)
            measureLaunch = false
            launchThresholdSeconds = null
            detectHangs = false
            captureUncaught = false
            // A fresh hook for the next test. One a test installed in the JVM's
            // real slot stays there, forwarding, since nothing ever unhooks.
            uncaughtCapture = UncaughtCapture()
            context = EventContext()
            apiKey = ""
            endpoint = ""
            onError = null
            debugLogging = false
            suppressWarnings = false
            appContext = null
        } catch (_: Throwable) {
            // Never throws
        }
    }
}
