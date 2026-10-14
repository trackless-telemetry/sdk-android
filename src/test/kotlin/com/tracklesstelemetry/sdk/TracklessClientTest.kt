package com.tracklesstelemetry.sdk

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * Tests for [Trackless] static singleton API.
 */
@DisplayName("Trackless")
class TracklessClientTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var resources: Resources
    private lateinit var configuration: Configuration
    private lateinit var appInfo: ApplicationInfo
    private lateinit var packageManager: PackageManager
    private lateinit var packageInfo: PackageInfo

    private val testApiKey = "tl_0123456789abcdef0123456789abcdef"
    private val testEndpoint = "https://api.test.com"

    @BeforeEach
    fun setUp() {
        application = mockk(relaxed = true)
        context = mockk(relaxed = true)
        resources = mockk(relaxed = true)
        configuration = Configuration()
        appInfo = ApplicationInfo()
        packageManager = mockk(relaxed = true)
        packageInfo = PackageInfo()

        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        appInfo.flags = 0 // non-debuggable (production)
        packageInfo.versionName = "1.0.0"
        @Suppress("DEPRECATION")
        packageInfo.versionCode = 1

        every { context.resources } returns resources
        every { resources.configuration } returns configuration
        every { context.applicationInfo } returns appInfo
        every { context.applicationContext } returns application
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.test.app"
        every { packageManager.getPackageInfo("com.test.app", 0) } returns packageInfo
        every { application.applicationInfo } returns appInfo
        every { application.resources } returns resources
        every { application.packageManager } returns packageManager
        every { application.packageName } returns "com.test.app"

        // Mock HttpClient to prevent actual network calls
        mockkObject(HttpClient)
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 200)

        // Reset singleton state
        Trackless.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        Trackless.resetForTesting()
        unmockkAll()
    }

    private fun configure(
        config: TracklessConfig = TracklessConfig(
            apiKey = testApiKey,
            endpoint = testEndpoint,
            flushIntervalSeconds = 999_999L,
        )
    ) {
        Trackless.configure(context, config)
    }

    // ─── Flush sends correct format ─────────────────────────────────────────

    @Test
    @DisplayName("Flush sends correct HTTP request format")
    fun flushSendsCorrectFormat() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val payload = payloadSlot.captured
        val featureEvents = payload.events.filter { it.type == EventType.FEATURE }
        assertEquals(1, featureEvents.size)
        assertEquals("export_clicked", featureEvents[0].name)
        assertEquals("production", payload.environment)
        assertEquals("android", payload.context.platform)

        verify { HttpClient.send(testEndpoint, testApiKey, any()) }
    }

    // ─── Empty buffer -> no HTTP request ────────────────────────────────────

    @Test
    @DisplayName("Empty buffer does not make HTTP request")
    fun emptyBufferNoHttpRequest() {
        configure()
        // Flush the session:start event first
        flushAndWait()
        io.mockk.clearMocks(HttpClient, answers = false)

        flushAndWait()
        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    // ─── Environment auto-detection ─────────────────────────────────────────

    @Test
    @DisplayName("Debuggable app auto-detects SANDBOX environment")
    fun debuggableAppAutoDetectsSandbox() {
        appInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        assertEquals("sandbox", payloadSlot.captured.environment)
    }

    @Test
    @DisplayName("Non-debuggable app auto-detects PRODUCTION environment")
    fun nonDebuggableAppAutoDetectsProduction() {
        appInfo.flags = 0
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        assertEquals("production", payloadSlot.captured.environment)
    }

    @Test
    @DisplayName("Explicit environment overrides auto-detection")
    fun explicitEnvironmentOverridesAutoDetection() {
        appInfo.flags = 0
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                environment = TracklessEnvironment.SANDBOX,
                flushIntervalSeconds = 999_999L,
            )
        )
        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        assertEquals("sandbox", payloadSlot.captured.environment)
    }

    // ─── Invalid names silently ignored ──────────────────────────────────────

    @Test
    @DisplayName("Invalid event names are silently ignored")
    fun invalidNamesAreIgnored() {
        configure()
        // Flush the initial session:start event
        flushAndWait()
        io.mockk.clearMocks(HttpClient, answers = false)

        Trackless.feature("")
        Trackless.feature("!!!")
        Trackless.feature("@#\$%^&")

        flushAndWait()

        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    @Test
    @DisplayName("Uppercase feature names are normalized to lowercase")
    fun uppercaseNamesNormalized() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("ExportClicked")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val featureEvents = payloadSlot.captured.events.filter { it.type == EventType.FEATURE }
        assertEquals("exportclicked", featureEvents[0].name)
    }

    // ─── Disabled mode ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Disabled mode: events are silently ignored")
    fun disabledModeEventsIgnored() {
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                enabled = false,
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("export_clicked")
        flushAndWait()

        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    // ─── setEnabled ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("setEnabled(false) discards buffered data immediately")
    fun setEnabledFalseDiscardsData() {
        configure()

        Trackless.feature("export_clicked")
        Trackless.setEnabled(false)
        flushAndWait()

        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    @Test
    @DisplayName("setEnabled(true) resumes with an empty buffer and a new session")
    fun setEnabledTrueResumesWithEmptyBuffer() {
        val sends = recordSends()
        configure()

        Trackless.feature("export_clicked")
        Trackless.setEnabled(false)
        Trackless.setEnabled(true)
        Trackless.feature("new_event")
        flushAndWait()

        val events = sends.flatMap { it.second.events }
        assertEquals(listOf("new_event"), events.filter { it.type == EventType.FEATURE }.map { it.name })
        assertEquals(listOf("start"), events.filter { it.type == EventType.SESSION }.map { it.name })
    }

    // ─── onError ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onError receives flush errors")
    fun onErrorReceivesFlushErrors() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = -1, isNetworkError = true)

        val errors = mutableListOf<Throwable>()
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                onError = { errors.add(it) },
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("export_clicked")
        flushAndWait()

        assertEquals(1, errors.size)
        assertTrue(errors[0].message?.contains("network error") == true)
    }

    @Test
    @DisplayName("onError receives validation errors")
    fun onErrorReceivesValidationErrors() {
        val errors = mutableListOf<Throwable>()
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                onError = { errors.add(it) },
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("!!!")
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalArgumentException)
    }

    // ─── Circuit Breaker ────────────────────────────────────────────────────

    @Test
    @DisplayName("5xx response triggers circuit breaker backoff")
    fun fiveXxTriggersCircuitBreaker() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 500)

        configure()
        Trackless.feature("export_clicked")
        flushAndWait()

        Trackless.feature("import_clicked")
        flushAndWait()

        verify(exactly = 1) { HttpClient.send(any(), any(), any()) }
    }

    @Test
    @DisplayName("4xx response discards batch with NO backoff")
    fun fourXxDiscardsWithNoBackoff() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 400)

        configure()
        Trackless.feature("export_clicked")
        flushAndWait()

        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 200)

        Trackless.feature("import_clicked")
        flushAndWait()

        verify(exactly = 2) { HttpClient.send(any(), any(), any()) }
    }

    // ─── Typed Events ───────────────────────────────────────────────────────

    @Test
    @DisplayName("View events are recorded correctly")
    fun viewEventsRecorded() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.view("home")
        Trackless.view("home")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val viewEvents = payloadSlot.captured.events.filter { it.type == EventType.VIEW }
        assertEquals(1, viewEvents.size)
        assertEquals(2, viewEvents[0].count)
    }

    @Test
    @DisplayName("View events include detail when provided")
    fun viewEventsIncludeDetail() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.view("home", "tab_a")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val viewEvents = payloadSlot.captured.events.filter { it.type == EventType.VIEW }
        assertEquals(1, viewEvents.size)
        assertEquals("tab_a", viewEvents[0].detail)
    }

    @Test
    @DisplayName("Feature events include detail when provided")
    fun featureEventsIncludeDetail() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export", "csv")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val featureEvents = payloadSlot.captured.events.filter { it.type == EventType.FEATURE }
        assertEquals(1, featureEvents.size)
        assertEquals("csv", featureEvents[0].detail)
    }

    @Test
    @DisplayName("Error events include the stored severity and code")
    fun errorEventsIncludeSeverityAndCode() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("crash", "E001")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val errorEvents = payloadSlot.captured.events.filter { it.type == EventType.ERROR }
        assertEquals(1, errorEvents.size)
        assertEquals(ErrorSeverity.ERROR, errorEvents[0].severity)
        assertEquals("e001", errorEvents[0].code)
    }

    @Test
    @DisplayName("Performance events include duration")
    fun performanceEventsIncludeDuration() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.performance("api_call", 150.0)
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val perfEvents = payloadSlot.captured.events.filter { it.type == EventType.PERFORMANCE }
        assertEquals(1, perfEvents.size)
        assertTrue(perfEvents[0].durations?.contains(150.0) == true)
    }

    @Test
    @DisplayName("Funnel events deduplicate and include stepIndex")
    fun funnelEventsDeduplicateAndIncludeStepIndex() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.funnel("checkout", 0, "cart")
        Trackless.funnel("checkout", 1, "payment")
        Trackless.funnel("checkout", 0, "cart") // duplicate — should be ignored

        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val funnelEvents = payloadSlot.captured.events.filter { it.type == EventType.FUNNEL }
        assertEquals(2, funnelEvents.size)
    }

    // ─── 4xx error callback ───────────────────────────────────────────────

    @Test
    @DisplayName("4xx response calls onError with rejection message")
    fun fourXxCallsOnError() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 400)

        val errors = mutableListOf<Throwable>()
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                onError = { errors.add(it) },
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(errors.any { it.message?.contains("400") == true })
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Lifecycle callbacks are registered on configure")
    fun lifecycleCallbacksRegistered() {
        configure()
        verify { application.registerActivityLifecycleCallbacks(any()) }
    }

    // ─── Never Throws ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Event methods never throw")
    fun eventMethodsNeverThrow() {
        configure()

        Trackless.feature("valid_name")
        Trackless.feature("")
        Trackless.feature("   ")
        Trackless.view("home")
        Trackless.view("home", "tab_a")
        Trackless.feature("export", "csv")
        Trackless.performance("api", 100.0)
        Trackless.error("crash")
    }

    @Test
    @DisplayName("flush() never throws")
    fun flushNeverThrows() {
        every { HttpClient.send(any(), any(), any()) } throws RuntimeException("Unexpected error")

        configure()
        Trackless.feature("export_clicked")
        flushAndWait()
    }

    @Test
    @DisplayName("destroy() never throws")
    fun destroyNeverThrows() {
        every { HttpClient.send(any(), any(), any()) } throws RuntimeException("Unexpected error")

        configure()
        Trackless.feature("export_clicked")
        destroyAndWait()
    }

    // ─── Destroy ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Events after destroy are ignored")
    fun eventsAfterDestroyIgnored() {
        configure()
        destroyAndWait()

        Trackless.feature("export_clicked")
        flushAndWait()
    }

    // ─── Reconfigure ────────────────────────────────────────────────────────

    private val otherApiKey = "tl_fedcba9876543210fedcba9876543210"

    /** Record every send as (apiKey, payload), from whichever thread it runs on. */
    private fun recordSends(): MutableList<Pair<String, EventPayload>> {
        val sends = Collections.synchronizedList(mutableListOf<Pair<String, EventPayload>>())
        every { HttpClient.send(any(), any(), any()) } answers {
            sends.add(secondArg<String>() to thirdArg<EventPayload>())
            SendResult(statusCode = 200)
        }
        return sends
    }

    private fun List<Pair<String, EventPayload>>.eventsFor(apiKey: String) =
        filter { it.first == apiKey }.flatMap { it.second.events }

    private fun sessionEvents(events: List<TracklessEvent>, name: String) =
        events.count { it.type == EventType.SESSION && it.name == name }

    @Test
    @DisplayName("configure() with a new API key ends the session under the old key and starts one under the new")
    fun reconfigureWithNewKeySwitchesKeyAndSession() {
        val sends = recordSends()

        configure()
        Trackless.feature("before_switch")
        configure(TracklessConfig(apiKey = otherApiKey, endpoint = testEndpoint, flushIntervalSeconds = 999_999L))
        Trackless.feature("after_switch")
        flushAndWait()

        val underA = sends.eventsFor(testApiKey)
        val underB = sends.eventsFor(otherApiKey)
        assertEquals(listOf("before_switch"), underA.filter { it.type == EventType.FEATURE }.map { it.name })
        assertEquals(listOf("after_switch"), underB.filter { it.type == EventType.FEATURE }.map { it.name })
        assertEquals(1, sessionEvents(underA, "start"))
        assertEquals(1, sessionEvents(underA, "end"))
        assertEquals(1, sessionEvents(underB, "start"))
        assertEquals(0, sessionEvents(underB, "end"))
        assertTrue(Trackless.isConfigured)
    }

    @Test
    @DisplayName("configure() with identical settings keeps the current session")
    fun identicalReconfigureKeepsSession() {
        val sends = recordSends()

        configure()
        Trackless.feature("one")
        configure()
        Trackless.feature("two")
        flushAndWait()

        val events = sends.flatMap { it.second.events }
        assertEquals(1, sessionEvents(events, "start"))
        assertEquals(0, sessionEvents(events, "end"))
        assertEquals(2, events.count { it.type == EventType.FEATURE })
    }

    @Test
    @DisplayName("configure() with identical settings replaces onError")
    fun identicalReconfigureReplacesOnError() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 500)
        val first = mutableListOf<Throwable>()
        val second = Collections.synchronizedList(mutableListOf<Throwable>())
        val base = TracklessConfig(apiKey = testApiKey, endpoint = testEndpoint, flushIntervalSeconds = 999_999L)

        configure(base.copy(onError = { first.add(it) }))
        configure(base.copy(onError = { second.add(it) }))
        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(first.isEmpty())
        assertTrue(second.isNotEmpty())
    }

    @Test
    @DisplayName("configure() asking for recording after setEnabled(false) resumes it")
    fun reconfigureAfterSetEnabledFalseResumes() {
        val sends = recordSends()

        configure()
        Trackless.setEnabled(false)
        Trackless.feature("while_disabled")
        configure()
        Trackless.feature("after_reconfigure")
        flushAndWait()

        val events = sends.flatMap { it.second.events }
        assertEquals(listOf("after_reconfigure"), events.filter { it.type == EventType.FEATURE }.map { it.name })
        assertEquals(1, sessionEvents(events, "start"))
    }

    @Test
    @DisplayName("configure() after destroy() starts a new session under the new key")
    fun configureAfterDestroy() {
        val sends = recordSends()

        configure()
        destroyAndWait()
        configure(TracklessConfig(apiKey = otherApiKey, endpoint = testEndpoint, flushIntervalSeconds = 999_999L))
        Trackless.feature("after_destroy")
        flushAndWait()

        val underB = sends.eventsFor(otherApiKey)
        assertEquals(1, sessionEvents(underB, "start"))
        assertEquals(listOf("after_destroy"), underB.filter { it.type == EventType.FEATURE }.map { it.name })
    }

    // ─── Opt-out ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setEnabled(false) drops the session, so destroy() sends nothing")
    fun optOutThenDestroySendsNothing() {
        val sends = recordSends()

        configure()
        Trackless.feature("before_opt_out")
        Trackless.setEnabled(false)
        destroyAndWait()

        assertTrue(sends.isEmpty())
    }

    @Test
    @DisplayName("setEnabled(true) after an opt-out starts a new session")
    fun optInStartsNewSession() {
        val sends = recordSends()

        configure()
        Trackless.feature("before_opt_out")
        Trackless.setEnabled(false)
        Trackless.setEnabled(true)
        Trackless.feature("after_opt_in")
        destroyAndWait()

        val sessions = sends.flatMap { it.second.events }.filter { it.type == EventType.SESSION }
        assertEquals(listOf("start", "end"), sessions.map { it.name })
        assertEquals(1, sessions.last().stepIndex)
    }

    // ─── Off the caller's thread ────────────────────────────────────────────

    private fun recordSendThreads(): MutableList<Thread> {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        every { HttpClient.send(any(), any(), any()) } answers {
            threads.add(Thread.currentThread())
            SendResult(statusCode = 200)
        }
        return threads
    }

    @Test
    @DisplayName("flush() sends on the flush thread, not the caller's")
    fun flushSendsOffCallerThread() {
        val threads = recordSendThreads()

        configure()
        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(threads.isNotEmpty())
        threads.forEach { assertNotEquals(Thread.currentThread(), it) }
    }

    @Test
    @DisplayName("destroy() sends its final flush on the flush thread, not the caller's")
    fun destroySendsOffCallerThread() {
        val threads = recordSendThreads()

        configure()
        Trackless.feature("export_clicked")
        destroyAndWait()

        assertTrue(threads.isNotEmpty())
        threads.forEach { assertNotEquals(Thread.currentThread(), it) }
    }

    @Test
    @DisplayName("The buffer-threshold flush sends on the flush thread, not the caller's")
    fun thresholdFlushSendsOffCallerThread() {
        val threads = recordSendThreads()

        configure()
        repeat(120) { Trackless.feature("feature_$it") }
        Trackless.awaitFlushesForTesting()

        assertTrue(threads.isNotEmpty())
        threads.forEach { assertNotEquals(Thread.currentThread(), it) }
    }

    // ─── Session Depth ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Rejected events do not increment session depth")
    fun rejectedEventsDoNotIncrementSessionDepth() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("valid_feature")
        Trackless.feature("!!!") // rejected — invalid name
        Trackless.view("@#\$%^&") // rejected — invalid name
        Trackless.performance("api_call", -1.0) // rejected — negative duration
        destroyAndWait() // ends the session and flushes

        val endEvent = payloads.flatMap { it.events }
            .find { it.type == EventType.SESSION && it.name == "end" }
        assertEquals(1, endEvent?.stepIndex)
    }

    @Test
    @DisplayName("Accepted events increment session depth")
    fun acceptedEventsIncrementSessionDepth() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("valid_feature")
        Trackless.view("home")
        Trackless.performance("api_call", 1.0)
        Trackless.error("crash")
        Trackless.funnel("checkout", 0, "cart")
        destroyAndWait() // ends the session and flushes

        val endEvent = payloads.flatMap { it.events }
            .find { it.type == EventType.SESSION && it.name == "end" }
        assertEquals(5, endEvent?.stepIndex)
    }

    // ─── Multiple Events ────────────────────────────────────────────────────

    @Test
    @DisplayName("Multiple feature events aggregate correctly")
    fun multipleEventsAggregate() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        Trackless.feature("import_clicked")
        Trackless.feature("export_clicked")
        flushAndWait()

        assertTrue(payloadSlot.isCaptured)
        val featureEvents = payloadSlot.captured.events.filter { it.type == EventType.FEATURE }
        assertEquals(2, featureEvents.size)

        val exportItem = featureEvents.find { it.name == "export_clicked" }
        val importItem = featureEvents.find { it.name == "import_clicked" }
        assertEquals(2, exportItem?.count)
        assertEquals(1, importItem?.count)
    }

    // ─── Feature Reach (firstUses) ──────────────────────────────────────────

    @Test
    @DisplayName("First feature use carries firstUses=1; a repeat in the same session omits it across a flush")
    fun firstUseMarkedOncePerSessionAndSurvivesFlush() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked") // first use → firstUses=1
        flushAndWait() // drains the buffer — the first-use set must survive
        Trackless.feature("export_clicked") // same session, already seen → no firstUses
        flushAndWait()

        val featureEvents = payloads.flatMap { it.events }
            .filter { it.type == EventType.FEATURE && it.name == "export_clicked" }
        assertEquals(2, featureEvents.size)
        assertEquals(1, featureEvents.count { it.firstUses == 1 })
        assertEquals(1, featureEvents.count { it.firstUses == null })
    }

    @Test
    @DisplayName("First-use set resets when the session ends")
    fun firstUseSetResetsOnSessionEnd() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked") // session 1 first use → firstUses=1
        destroyAndWait() // ends session 1 (clears the set) and flushes

        configure()
        Trackless.feature("export_clicked") // session 2 first use → firstUses=1 again
        flushAndWait()

        val firstUses = payloads.flatMap { it.events }
            .filter { it.type == EventType.FEATURE && it.name == "export_clicked" }
            .map { it.firstUses }
        assertEquals(2, firstUses.size)
        assertTrue(firstUses.all { it == 1 })
    }

    @Test
    @DisplayName("Normalize before dedup: raw and normalized spellings dedup together")
    fun normalizeBeforeDedup() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("Dark Mode") // normalizes to "dark_mode" → first use → firstUses=1
        flushAndWait()
        Trackless.feature("dark_mode") // same normalized name → firstUses omitted
        flushAndWait()

        val featureEvents = payloads.flatMap { it.events }
            .filter { it.type == EventType.FEATURE && it.name == "dark_mode" }
        assertEquals(2, featureEvents.size)
        assertEquals(1, featureEvents.count { it.firstUses == 1 })
        assertEquals(1, featureEvents.count { it.firstUses == null })
    }

    @Test
    @DisplayName("Distinct feature names each count as a first use")
    fun distinctNamesEachFirstUse() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        Trackless.feature("import_clicked")
        flushAndWait()

        val featureEvents = payloadSlot.captured.events.filter { it.type == EventType.FEATURE }
        assertEquals(2, featureEvents.size)
        assertTrue(featureEvents.all { it.firstUses == 1 })
    }

    @Test
    @DisplayName("firstUses is emitted only on feature events")
    fun firstUsesOnlyOnFeatureEvents() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.view("home")
        Trackless.performance("api_call", 1.0)
        Trackless.error("crash")
        Trackless.funnel("checkout", 0, "cart")
        Trackless.feature("export_clicked")
        flushAndWait()

        val events = payloadSlot.captured.events
        val nonFeature = events.filter { it.type != EventType.FEATURE }
        assertTrue(nonFeature.isNotEmpty())
        assertTrue(nonFeature.all { it.firstUses == null })
        assertEquals(1, events.single { it.type == EventType.FEATURE }.firstUses)
    }

    // ─── Error Reach (firstOccurrences) ─────────────────────────────────────

    @Test
    @DisplayName("First error carries firstOccurrences=1; a repeat in the same session omits it across a flush")
    fun firstOccurrenceMarkedOncePerSessionAndSurvivesFlush() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("payment_failed") // first occurrence → firstOccurrences=1
        flushAndWait() // drains the buffer — the first-occurrence set must survive
        Trackless.error("payment_failed") // same session, already seen → no firstOccurrences
        flushAndWait()

        val errorEvents = payloads.flatMap { it.events }
            .filter { it.type == EventType.ERROR && it.name == "payment_failed" }
        assertEquals(2, errorEvents.size)
        assertEquals(1, errorEvents.count { it.firstOccurrences == 1 })
        assertEquals(1, errorEvents.count { it.firstOccurrences == null })
    }

    @Test
    @DisplayName("First-occurrence set resets when the session ends")
    fun firstOccurrenceSetResetsOnSessionEnd() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("payment_failed") // session 1 first occurrence → firstOccurrences=1
        destroyAndWait() // ends session 1 (clears the set) and flushes

        configure()
        Trackless.error("payment_failed") // session 2 first occurrence → firstOccurrences=1 again
        flushAndWait()

        val firstOccurrences = payloads.flatMap { it.events }
            .filter { it.type == EventType.ERROR && it.name == "payment_failed" }
            .map { it.firstOccurrences }
        assertEquals(2, firstOccurrences.size)
        assertTrue(firstOccurrences.all { it == 1 })
    }

    @Test
    @DisplayName("Normalize before dedup: raw and normalized error spellings dedup together")
    fun normalizeBeforeErrorDedup() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("Payment Failed") // normalizes to "payment_failed" → firstOccurrences=1
        flushAndWait()
        Trackless.error("payment_failed") // same normalized name → firstOccurrences omitted
        flushAndWait()

        val errorEvents = payloads.flatMap { it.events }
            .filter { it.type == EventType.ERROR && it.name == "payment_failed" }
        assertEquals(2, errorEvents.size)
        assertEquals(1, errorEvents.count { it.firstOccurrences == 1 })
        assertEquals(1, errorEvents.count { it.firstOccurrences == null })
    }

    @Test
    @DisplayName("Distinct error names each count as a first occurrence")
    fun distinctErrorNamesEachFirstOccurrence() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("payment_failed")
        Trackless.error("api_timeout")
        flushAndWait()

        val errorEvents = payloadSlot.captured.events.filter { it.type == EventType.ERROR }
        assertEquals(2, errorEvents.size)
        assertTrue(errorEvents.all { it.firstOccurrences == 1 })
    }

    @Test
    @DisplayName("Severity and code variants of one name share a single first occurrence")
    @Suppress("DEPRECATION") // exercises the legacy severity parameter on purpose
    fun severityAndCodeVariantsShareOneFirstOccurrence() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("payment_failed", ErrorSeverity.ERROR, "E001")
        Trackless.error("payment_failed", ErrorSeverity.WARNING, "E002")
        Trackless.error("payment_failed", ErrorSeverity.FATAL)
        flushAndWait()

        // Every severity now stores as `error`, so the keys differ only by code:
        // three distinct rollup keys, one first occurrence total.
        val errorEvents = payloadSlot.captured.events.filter { it.type == EventType.ERROR }
        assertEquals(3, errorEvents.size)
        assertTrue(errorEvents.all { it.severity == ErrorSeverity.ERROR })
        assertEquals(1, errorEvents.sumOf { it.firstOccurrences ?: 0 })
    }

    @Test
    @DisplayName("firstOccurrences is emitted only on error events")
    fun firstOccurrencesOnlyOnErrorEvents() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.view("home")
        Trackless.performance("api_call", 1.0)
        Trackless.feature("export_clicked")
        Trackless.funnel("checkout", 0, "cart")
        Trackless.error("crash")
        flushAndWait()

        val events = payloadSlot.captured.events
        val nonError = events.filter { it.type != EventType.ERROR }
        assertTrue(nonError.isNotEmpty())
        assertTrue(nonError.all { it.firstOccurrences == null })
        assertEquals(1, events.single { it.type == EventType.ERROR }.firstOccurrences)
    }

    @Test
    @DisplayName("A repeat-only error rollup omits firstOccurrences on the wire")
    fun repeatOnlyErrorRollupOmitsFirstOccurrencesOnWire() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("payment_failed") // first occurrence, flushed away
        flushAndWait()
        Trackless.error("payment_failed")
        Trackless.error("payment_failed")
        flushAndWait()

        val repeatEvent = payloads.last().events.single { it.type == EventType.ERROR }
        assertEquals(2, repeatEvent.count)

        val json = repeatEvent.toJson()
        assertFalse(json.has("firstOccurrences"))
        assertEquals(2, json.getInt("count"))
    }
}
