package com.tracklesstelemetry.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    private var apiKey: String = ""
    private var endpoint: String = ""
    private var environment: TracklessEnvironment = TracklessEnvironment.PRODUCTION
    private var onError: ((Throwable) -> Unit)? = null
    private var flushIntervalSeconds: Long = 60L
    private var debugLogging: Boolean = false
    private var suppressWarnings: Boolean = false

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
    private var funnelTracker: FunnelTracker = FunnelTracker()
    private var featureTracker: FeatureTracker = FeatureTracker()
    private var errorTracker: ErrorTracker = ErrorTracker()

    private var appContext: Context? = null
    private var scheduler: ScheduledExecutorService? = null
    private var flushFuture: ScheduledFuture<*>? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    private val activeActivityCount = AtomicInteger(0)

    /** Test-only hook — receives each warning message that passes the suppression check. */
    internal var onWarning: ((String) -> Unit)? = null

    /**
     * Whether the SDK has been configured and is ready to record events.
     */
    val isConfigured: Boolean
        get() = configured.get() && !destroyed.get()

    /**
     * Configure the Trackless SDK.
     *
     * Must be called once before any event methods.
     * Subsequent calls are ignored (configure is idempotent).
     */
    fun configure(context: Context, config: TracklessConfig) {
        try {
            if (configured.getAndSet(true)) return

            appContext = context.applicationContext
            apiKey = config.apiKey
            endpoint = config.endpoint
            environment = config.environment ?: ContextDetection.detectEnvironment(context)
            onError = config.onError
            flushIntervalSeconds = config.flushIntervalSeconds
            debugLogging = config.debugLogging
            suppressWarnings = config.suppressWarnings

            buffer = EventBuffer()
            circuitBreaker = CircuitBreaker()
            sessionManager = SessionManager()
            funnelTracker = FunnelTracker()
            featureTracker = FeatureTracker()
            errorTracker = ErrorTracker()
            this.context = ContextDetection.detect(context)

            bufferFullWarned.set(false)
            preConfigureWarned.set(false)
            destroyed.set(false)
            enabled.set(config.enabled)

            debug("configured — env=${environment.value} endpoint=$endpoint flush=${flushIntervalSeconds}s")

            if (enabled.get()) {
                startPeriodicFlush()
                registerLifecycleCallbacks()
                startNewSession()
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
        if (buffer.totalSize >= BUFFER_FLUSH_THRESHOLD) {
            performFlush()
        }
        sessionManager.recordActivity()
        debug("feature — $normalizedName${if (normalizedDetail != null) " detail=$normalizedDetail" else ""}")
    }

    /**
     * Record a funnel step event.
     * Deduplicates steps within the same funnel.
     */
    fun funnel(funnelName: String, stepIndex: Int, stepName: String) {
        if (!canRecord()) return
        if (stepIndex < 0) return
        val normalizedFunnel = normalizeName(funnelName) ?: return
        val normalizedStep = normalizeName(stepName) ?: return
        if (!funnelTracker.step(normalizedFunnel, stepIndex)) {
            debug("funnel — $normalizedFunnel/$normalizedStep (duplicate, skipped)")
            return
        }
        sessionManager.recordActivity()
        addToBuffer(
            TracklessEvent(
                type = EventType.FUNNEL,
                name = normalizedFunnel,
                step = normalizedStep,
                stepIndex = stepIndex,
            )
        )
        if (buffer.totalSize >= BUFFER_FLUSH_THRESHOLD) {
            performFlush()
        }
        debug("funnel — $normalizedFunnel/$normalizedStep step=$stepIndex")
    }

    /**
     * Record a performance measurement.
     *
     * @param name Event name for this measurement
     * @param durationSeconds Duration in seconds (e.g., 0.342 for 342ms)
     * @param thresholdSeconds Optional threshold in seconds. Creates a separate row per name/threshold combo.
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
        sessionManager.recordActivity()
        debug("performance — ${recorded.name} duration=${durationSeconds}s${if (thresholdSeconds != null) " threshold=${thresholdSeconds}s" else ""}")
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
        if (buffer.totalSize >= BUFFER_FLUSH_THRESHOLD) {
            performFlush()
        }
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
     * When toggled off, any buffered data is immediately discarded.
     */
    fun setEnabled(isEnabled: Boolean) {
        try {
            debug("setEnabled — $isEnabled")
            enabled.set(isEnabled)
            if (!isEnabled) {
                buffer.clear()
                sessionManager.destroy()
                funnelTracker.clear()
                featureTracker.clear()
                errorTracker.clear()
                stopPeriodicFlush()
                unregisterLifecycleCallbacks()
            } else if (!destroyed.get() && configured.get()) {
                startPeriodicFlush()
                registerLifecycleCallbacks()
                sessionManager.start()
            }
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * Force flush pending events to the ingest endpoint.
     */
    fun flush() {
        try {
            performFlush()
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * Flush remaining events and clean up all resources.
     *
     * After calling destroy(), the SDK is reset and must be re-configured.
     */
    fun destroy() {
        try {
            if (destroyed.getAndSet(true)) return
            debug("destroying")

            endCurrentSession()
            performFlush()
            stopPeriodicFlush()
            unregisterLifecycleCallbacks()
            funnelTracker.clear()
            featureTracker.clear()
            errorTracker.clear()
            configured.set(false)
            enabled.set(false)
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

            // Auto-flush if buffer exceeds threshold
            if (buffer.totalSize >= BUFFER_FLUSH_THRESHOLD) {
                performFlush()
            }
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
     * Perform the actual flush operation.
     */
    private fun performFlush() {
        if (buffer.isEmpty) return
        if (!circuitBreaker.canAttempt()) {
            debug("flush skipped — circuit breaker open")
            return
        }

        val payloads = buffer.drain(environment.value, context)
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
                val result = HttpClient.send(endpoint, apiKey, payload)

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
        val result = sessionManager.end() ?: return
        funnelTracker.clear()
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
            val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "trackless-flush").apply {
                    isDaemon = true
                }
            }
            scheduler = exec
            flushFuture = exec.scheduleAtFixedRate(
                {
                    try {
                        performFlush()
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
                    val count = activeActivityCount.incrementAndGet()
                    if (count == 1) {
                        // App is coming to the foreground — start new session
                        startNewSession()
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    val count = activeActivityCount.decrementAndGet()
                    if (count <= 0) {
                        // App is now in the background — end session and flush
                        try {
                            scheduler?.submit {
                                try {
                                    endCurrentSession()
                                    performFlush()
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
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
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
            activeActivityCount.set(0)
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
     * Current buffer size for testing.
     */
    internal fun bufferSizeForTesting(): Int = buffer.totalSize

    /**
     * Reset internal state for testing.
     */
    internal fun resetForTesting() {
        try {
            stopPeriodicFlush()
            unregisterLifecycleCallbacks()
            configured.set(false)
            enabled.set(false)
            destroyed.set(false)
            bufferFullWarned.set(false)
            preConfigureWarned.set(false)
            onWarning = null
            buffer = EventBuffer()
            circuitBreaker = CircuitBreaker()
            sessionManager = SessionManager()
            funnelTracker = FunnelTracker()
            featureTracker = FeatureTracker()
            errorTracker = ErrorTracker()
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
